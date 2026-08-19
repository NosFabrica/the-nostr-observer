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
     * Every edition this reader has published, newest first.
     *
     * THIS IS THE ARCHIVE, and it is entirely theirs. Each day is its own
     * `kind 35128` under a `d` of `observer-<date>`, so the list is just their
     * sites, filtered to the ones we named — and no publish has ever replaced
     * any of them.
     *
     * There is nothing to fail closed about any more. When one site held every
     * day at once, this read was a PRECONDITION: publishing rewrote the event
     * that carried the archive, so a read that came back empty for the wrong
     * reason deleted it. That needed a canary to tell an unreachable relay from
     * an empty one, and a refusal when it could not. Now a failed read costs a
     * reader a listing they can refresh, and nothing else.
     *
     * Relays cannot prefix-match a `d` tag, so this asks for their sites and
     * sorts ours out here. A reader has a few hundred at most.
     *
     * IT READS EXACTLY WHERE THE MANIFEST WAS SENT, and nowhere else. Two
     * things used to be wrong about that. Our own search relay was in the list,
     * so an edition could appear in a reader's archive because WE have it while
     * their relays do not — which is the "resolves for us and for nobody else"
     * failure this whole design exists to prevent, wearing the costume of a
     * working feature. And the reader's own list was cut to three while a
     * publish goes to all of them, so an edition that landed on their fourth
     * relay was invisible here.
     *
     * The cost is that a reader whose relays are slow or down sees a short
     * archive rather than a padded one. That is the right way round: a missing
     * row is a relay problem they can see and fix, and a row that only we can
     * resolve is a lie we told them about where their paper is.
     */
    suspend fun editions(
        pubkey: String,
        hosts: List<String>,
    ): List<Edition> {
        val events =
            anyOf(
                hosts,
                Filter(kinds = listOf(NamedSiteEvent.KIND), authors = listOf(pubkey)),
            )
        return events
            .mapNotNull { event ->
                val site = NamedSiteEvent(event.id, event.pubKey, event.createdAt, event.tags, event.content, event.sig)
                val day = Templates.dayOf(site.identifier() ?: return@mapNotNull null) ?: return@mapNotNull null
                val hash = site.paths().firstOrNull { it.path == "/index.html" }?.hash ?: return@mapNotNull null
                Edition(day, hash, site.servers(), site.description(), event.createdAt)
            }
            // Newest wins per day: a day republished is one edition, not two.
            .groupBy { it.day }
            .mapNotNull { (_, versions) -> versions.maxByOrNull { it.publishedAt } }
            .sortedByDescending { it.day }
    }

    data class Edition(
        val day: String,
        val hash: String,
        val servers: List<String>,
        /** The day's lead headline, put there at publish so a list can be read. */
        val headline: String?,
        val publishedAt: Long,
    )

    /**
     * The reader's relays, all of them, asked at once.
     *
     * At once because these are independent hosts and one slow relay is not a
     * reason to wait before asking the next: in series, five hosts is five idle
     * windows to read a handful of small events. A host that fails contributes
     * nothing rather than failing the read, which is what makes reading all of
     * them affordable.
     */
    private suspend fun anyOf(
        hosts: List<String>,
        filter: Filter,
    ): List<Event> =
        coroutineScope {
            hosts
                .distinct()
                .map { host -> async { runCatching { relays.fetch(host, filter, idle = 10_000) }.getOrDefault(emptyList()) } }
                .awaitAll()
                .flatten()
        }
}
