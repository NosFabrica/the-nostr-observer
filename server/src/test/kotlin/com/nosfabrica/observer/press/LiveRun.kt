package com.nosfabrica.observer.press

import com.nosfabrica.observer.Press
import com.nosfabrica.observer.nostr.Names
import com.nosfabrica.observer.nostr.Relays
import com.nosfabrica.observer.press.publish.Announce
import com.nosfabrica.observer.press.publish.Blossom
import com.nosfabrica.observer.press.publish.Countersign
import com.nosfabrica.observer.press.publish.Templates
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import com.vitorpamplona.quartz.nipB7Blossom.BlossomServersEvent
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The publish path against the real network, end to end, once.
 *
 * NOT A TEST, and it is in the test source set only so that it never ships in
 * the server jar. It has no `@Test`, so `./gradlew test` does not run it: it
 * writes to somebody else's relay and somebody else's media server, and a
 * thing that does that must be started by a person on purpose.
 *
 *     ./gradlew :server:liveRun -PliveArgs="<page.html> wss://nos.lol https://blossom.nostr.build"
 *
 * WHY THIS RATHER THAN A MOCK. The alternative was a hand-rolled websocket
 * server answering `["OK", <id>, true, ""]`, which would have proved that
 * `Relays.publish` maps a frame we wrote ourselves into a `Triple` we wrote
 * ourselves. It could not have told us whether a real relay accepts a
 * `kind 35128`, whether a real Blossom server accepts our BUD-01 header, or
 * whether an edition published to a reader's outbox can be read back from it —
 * which are the three things that have to be true for any of this to work.
 *
 * The key is minted here and thrown away. The server holds no key by design
 * (see [Countersign]), so there is no "our" key to run this with, and asking a
 * person for an nsec to run a test with is the thing this codebase refuses at
 * its own front door. The cost is that a throwaway pubkey is unknown to every
 * media server that gates on who is asking, and a refusal there is a real
 * answer about that server rather than a fault in this code.
 *
 * It reuses a page that was already generated. Regenerating one costs about a
 * dollar and proves nothing about publishing.
 */
fun main(args: Array<String>) =
    runBlocking {
        val page = File(args.getOrElse(0) { "edition.html" })
        val relay = args.getOrElse(1) { "wss://nos.lol" }
        val blossom = args.getOrElse(2) { "https://blossom.nostr.build" }
        require(page.isFile) { "no page at ${page.path}" }
        val blob = page.readBytes()

        val keys = KeyPair()
        val signer = NostrSignerSync(keys)
        val pubkey = keys.pubKey.toHexKey()

        fun sign(t: EventTemplate<Event>): Event = signer.sign(t.createdAt, t.kind, t.tags, t.content)

        say("A throwaway reader", "${Names.npub(pubkey)}")
        say("Page", "${page.name}, ${blob.size} bytes, sha256 ${Blossom.sha256(blob).take(12)}")

        Relays().use { relays ->
            // The search relay is pointed at the same host on purpose. In
            // production this is our own relay, and a run that published a
            // throwaway reader's setup events there would be leaving test data
            // on a shared machine other people are reading from.
            val press = Press(relays, relay)
            val announce = Announce(relays, relay, press)
            val now = Instant.now().epochSecond

            // 1. Be a reader: say where the outbox is and where files go.
            step("Publishing this reader's kind 10002 and kind 10063 to $relay")
            val setup =
                listOf(
                    sign(
                        Event.build(AdvertisedRelayListEvent.KIND, "", now) {
                            add(arrayOf("r", relay))
                        },
                    ),
                    sign(
                        Event.build(BlossomServersEvent.KIND, "", now) {
                            add(arrayOf("server", blossom))
                        },
                    ),
                )
            setup.forEach { event ->
                relays.publish(event, listOf(relay)).forEach { (host, ok, message) ->
                    say("  kind ${event.kind} -> $host", if (ok) "accepted" else "REFUSED: $message")
                }
            }

            // 2. Read it back the way the publish path does, not from the
            //    variables above. This is the whole point: `writeRelaysOf`
            //    parses the 10002 through quartz, and `servers` re-reads the
            //    10063 from those relays rather than from anything we kept.
            step("Reading it back through the real path")
            val writes = press.writeRelaysOf(pubkey)
            say("  outbox (kind 10002)", writes.joinToString().ifBlank { "NOTHING — the publish path would stop here" })
            val servers = announce.servers(pubkey, writes)
            say("  storage (kind 10063)", servers.joinToString().ifBlank { "NOTHING — the publish path would stop here" })
            if (writes.isEmpty() || servers.isEmpty()) return@use

            // 3. Upload, under an authorization bound to these exact bytes.
            step("Uploading to ${servers.joinToString()}")
            val sha = Blossom.sha256(blob)
            val auth = sign(Templates.uploadAuth(sha, blob.size.toLong(), now, now + 600))
            val uploads = Blossom().upload(servers, blob, auth)
            uploads.forEach {
                say(
                    "  ${it.server}",
                    if (it.ok) "stored, served at ${it.url ?: "(no url given)"}" else "REFUSED: ${it.detail}",
                )
            }
            if (uploads.none { it.ok }) {
                say("Stopped", "nothing is stored, so a manifest would announce a link to a 404")
                return@use
            }

            // 4. The manifest, signed and then checked against its own template
            //    exactly as the server checks a browser's answer.
            val day = DateTimeFormatter.ofPattern("yyyy-MM-dd").format(Instant.ofEpochSecond(now).atZone(ZoneId.of("UTC")))
            val template = Templates.manifest(day, sha, servers, "The Nostr Observer", headline(blob), now)
            when (val checked = Countersign.check(sign(template), template, pubkey)) {
                is Countersign.Result.No -> {
                    say("Countersign refused", checked.reason)
                }

                is Countersign.Result.Ok -> {
                    step("Announcing $day to the outbox")
                    announce.publish(checked.event, writes).forEach {
                        say("  ${it.relay}", if (it.ok) "accepted" else "REFUSED: ${it.message}")
                    }

                    // 5. What the archive screen would show. Read from the
                    //    reader's own relays, after a pause, because a relay
                    //    that has just accepted an event has not necessarily
                    //    finished indexing it.
                    step("Reading the archive back, the way the console does")
                    Thread.sleep(2_000)
                    val editions = announce.editions(pubkey, press.writeRelaysOf(pubkey))
                    if (editions.isEmpty()) {
                        say("  archive", "EMPTY — published and not readable back")
                    } else {
                        editions.forEach {
                            say("  ${it.day}", "${it.hash.take(12)} | ${it.headline ?: "(no headline)"} | ${it.servers.joinToString()}")
                        }
                    }
                    say(
                        "  round trip",
                        if (editions.any {
                                it.day == day && it.hash == sha
                            }
                        ) {
                            "the page we uploaded is the page the archive names"
                        } else {
                            "MISMATCH"
                        },
                    )
                    say("  address", Templates.address(pubkey, day) ?: "(could not encode)")
                }
            }
            // `press.close()` is deliberately not called: closing a Press starts
            // the browser it never needed, and this run has no page to render.
        }
    }

/** The lead headline, for the manifest's description tag. */
private fun headline(blob: ByteArray): String? =
    Regex("<h1[^>]*>(.*?)</h1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        .findAll(String(blob))
        .map {
            it.groupValues[1]
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }.firstOrNull { it.isNotBlank() }

private fun step(what: String) = println("\n== $what")

private fun say(
    what: String,
    detail: String,
) = println("$what: $detail")
