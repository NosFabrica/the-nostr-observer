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

    /** Does this reader already have a site under our `d` tag, and what is in it? */
    suspend fun existing(
        pubkey: String,
        hosts: List<String>,
    ): NamedSiteEvent? {
        val events =
            anyOf(
                hosts,
                Filter(
                    kinds = listOf(NamedSiteEvent.KIND),
                    authors = listOf(pubkey),
                    tags = mapOf("d" to listOf(Templates.SITE)),
                ),
            )
        val newest = events.maxByOrNull { it.createdAt } ?: return null
        return NamedSiteEvent(newest.id, newest.pubKey, newest.createdAt, newest.tags, newest.content, newest.sig)
    }

    // The reader's own relays and ours, asked together: a 10063 lives where they
    // put it and the search relay mirrors only the kinds it was asked to, so we
    // need every answer anyway. Asked in series, four hosts meant four idle
    // windows -- forty seconds to read one small event.
    private suspend fun anyOf(
        hosts: List<String>,
        filter: Filter,
    ): List<Event> =
        coroutineScope {
            (hosts.take(3) + readRelay)
                .distinct()
                .map { host -> async { runCatching { relays.fetch(host, filter, idle = 10_000) }.getOrDefault(emptyList()) } }
                .awaitAll()
                .flatten()
        }
}
