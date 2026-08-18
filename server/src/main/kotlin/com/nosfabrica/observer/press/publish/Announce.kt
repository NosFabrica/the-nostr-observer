package com.nosfabrica.observer.press.publish

import com.nosfabrica.observer.nostr.Relays
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip5aStaticWebsites.NamedSiteEvent
import com.vitorpamplona.quartz.nipB7Blossom.BlossomServersEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Where a reader's paper lives, according to the reader.
 *
 * Both halves come off Nostr rather than out of our database: the servers that
 * will hold the blob (BUD-03, `kind 10063`) and the relays that will carry the
 * manifest (NIP-65, `kind 10002`). That is the point of the design. We are not
 * the registry; we look up what they have already published about themselves,
 * and if they move house tomorrow we find out by asking again.
 */
class Announce(
    private val relays: Relays,
    private val readRelay: String,
    private val press: com.nosfabrica.observer.Press,
) {
    /**
     * The reader's Blossom servers, from the generator's reader.
     *
     * This used to parse the kind 10063 itself, which meant two parsers for one
     * question — and the storage readiness chain would have been the second
     * place to disagree about what counts as a usable server. An empty list is
     * still a hard stop at publish: substituting a server of our own would make
     * us the host of a page whose whole promise is that the reader hosts it.
     */
    suspend fun servers(
        pubkey: String,
        hosts: List<String>,
    ): List<String> = press.blossomServers(pubkey, hosts)

    /**
     * Publish the manifest to the reader's own write relays and report each one.
     *
     * `publishAndCollectResults` waits for the OK frames rather than firing and
     * forgetting, because "published" with no confirmation is the failure mode
     * where a reader's paper resolves for us, out of our cache, and for nobody
     * else. A relay that rejects it says so in the OK message and the reader
     * should see that sentence.
     */
    suspend fun publish(
        manifest: Event,
        writeRelays: List<String>,
    ): List<Result> {
        if (writeRelays.isEmpty()) return emptyList()
        return relays.publish(manifest, writeRelays).map { (relay, ok, message) -> Result(relay, ok, message) }
    }

    data class Result(
        val relay: String,
        val ok: Boolean,
        val message: String,
    )

    /**
     * The reader's site, and whether we actually got to look.
     *
     * THE WHOLE ARCHIVE IS THIS EVENT. A `kind 35128` carries every path the
     * reader has published — `/index.html`, `/2026-08-18`, and every day before
     * it — and it REPLACES on publish. So the difference between "you have no
     * site" and "we could not read your site" is the difference between a first
     * publish and deleting somebody's back catalogue, and it cannot be a
     * guess: [Missing] is a relay saying there is nothing, and [Unreadable] is
     * a relay saying nothing.
     */
    sealed interface Site {
        /** They have one, with every path it names. */
        data class Found(
            val site: NamedSiteEvent,
        ) : Site

        /** Relays answered, and there is no site yet. A first publish. */
        data object Missing : Site

        /** Nobody answered. We do not know, so nothing may be replaced. */
        data object Unreadable : Site
    }

    suspend fun existing(
        pubkey: String,
        hosts: List<String>,
    ): Site {
        // ASKED WITH A CANARY, because an unreachable relay is indistinguishable
        // from an empty one.
        //
        // The first version of this read the manifest alone and called silence
        // `Unreadable`. It does not work, and a test caught it: quartz answers a
        // host it cannot reach with an empty list rather than an error, so a
        // dead relay and a reader who has never published produce byte-identical
        // results. Timeouts, refused connections and bad DNS all arrive as
        // "nothing here".
        //
        // So the read also asks for the small replaceable events every Nostr
        // account has — profile, relay list, server list. One of them coming
        // back proves the host is answering, and an absent manifest beside a
        // present profile is a real absence. Nothing at all, from any of their
        // relays, means we are talking to nobody.
        val mine = hosts.take(3).distinct()
        val filter =
            Filter(
                kinds = listOf(NamedSiteEvent.KIND, BlossomServersEvent.KIND, RELAY_LIST, PROFILE),
                authors = listOf(pubkey),
            )

        // THEIR hosts are the witnesses, not ours. Our search relay is always up
        // and holds a profile for anybody in the corpus, so letting it vouch
        // would make the canary sing no matter what.
        val theirs = eachOf(mine, filter)
        if (theirs.isEmpty() || theirs.all { it.isEmpty() }) return Site.Unreadable

        // Ours is still worth reading for the manifest itself — it may hold a
        // copy theirs has dropped — but only now that the question is known to
        // be answerable.
        val ours = runCatching { relays.fetch(readRelay, filter, idle = 10_000) }.getOrDefault(emptyList())
        val newest =
            (theirs.flatten() + ours)
                .filter { it.kind == NamedSiteEvent.KIND && named(it) }
                .maxByOrNull { it.createdAt }
                ?: return Site.Missing
        return Site.Found(NamedSiteEvent(newest.id, newest.pubKey, newest.createdAt, newest.tags, newest.content, newest.sig))
    }

    /** Our site, and not another nsite the reader happens to keep. */
    private fun named(event: Event) = event.tags.any { it.size > 1 && it[0] == "d" && it[1] == Templates.SITE }

    /**
     * One list per host, so a caller can tell which of them said anything.
     *
     * Asked at once: these are independent hosts and three of them in series is
     * three idle windows, thirty seconds to read one small event.
     */
    private suspend fun eachOf(
        hosts: List<String>,
        filter: Filter,
    ): List<List<Event>> =
        coroutineScope {
            hosts
                .map { host -> async { runCatching { relays.fetch(host, filter, idle = 10_000) }.getOrDefault(emptyList()) } }
                .awaitAll()
        }

    private companion object {
        const val RELAY_LIST = 10002
        const val PROFILE = 0
    }
}
