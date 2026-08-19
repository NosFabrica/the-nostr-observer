package com.nosfabrica.observer.nostr

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import com.vitorpamplona.quartz.nip85TrustedAssertions.list.TrustProviderListEvent
import com.vitorpamplona.quartz.nip85TrustedAssertions.list.serviceProviders
import com.vitorpamplona.quartz.nip85TrustedAssertions.list.tags.ProviderTypes
import com.vitorpamplona.quartz.nip85TrustedAssertions.users.ContactCardEvent
import com.vitorpamplona.quartz.nipB7Blossom.BlossomServersEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Asks the relay the four questions [Readiness] decides on.
 *
 * [Readiness] is pure and testable; this is the half that talks to the network,
 * kept separate for exactly that reason. Every fact it cannot establish is left
 * null rather than guessed, because null drives `checking` and a guess drives a
 * confident wrong answer.
 *
 * THE TAG PARSING IS QUARTZ'S, not ours. An earlier version of this file read
 * the `r` tags of a kind 10002 and the `30382:rank` tag of a kind 10040 by
 * hand, which was both redundant and riskier than it looked: quartz's
 * `ServiceProviderTag.parse` requires all three fields, so a hintless entry is
 * rejected there rather than by a `takeIf` somebody could later "simplify"
 * away — and this project reads a relay that resolves those same tags through
 * the same library. Two parsers for one wire format is one parser too many.
 *
 * Measured against search-staging on 2026-08-17 for the prototype observer:
 * 149,171 of the provider's 149,266 cards are here (99.9%), own posts 27,058.
 */
class ReadinessProbe(
    private val relays: Relays,
    private val searchRelay: String,
) {
    suspend fun gather(
        observer: String,
        since: Long,
    ): Readiness.Facts =
        coroutineScope {
            val listEvent = async { one(AdvertisedRelayListEvent.KIND, observer) }
            val scoreEvent = async { one(TrustProviderListEvent.KIND, observer) }
            // Link 4 runs regardless of links 1-3: one cheap pair of reads, and
            // the only thing that can see a service whose cards are stored but
            // not yet projected.
            val authed = async { relays.fetch(searchRelay, rankedProbe(observer, since)).size.toLong() }
            val anon = async { relays.fetch(searchRelay, rankedProbe(null, since)).size.toLong() }

            val relayList = listEvent.await()
            val writes = writeRelays(relayList)
            val provider = rankProvider(scoreEvent.await())

            // COUNTs, ONE AT A TIME, and this is not an oversight.
            //
            // Running the four concurrently is the obvious optimisation and it
            // was tried: `--check` went from answering in about three seconds to
            // hanging until it was killed, repeatably, against this relay. The
            // likely reason is already written down in AGENTS.md -- the store
            // sends an AUTH challenge before it answers a COUNT even though
            // `auth_required` is false -- and four handshakes racing on one
            // socket is not something this project gets to fix from the outside.
            // The fetches above genuinely do run in parallel; these do not.
            val scores =
                provider?.let { (service, hint) ->
                    val cards = Filter(kinds = listOf(ContactCardEvent.KIND), authors = listOf(service))
                    // "There" is the provider's own relay. Asking a second host
                    // is the whole point of the comparison, so failing to reach
                    // it must leave a null denominator rather than borrow ours.
                    Readiness.Counts(relays.count(searchRelay, cards), relays.count(hint, cards))
                }

            val posts =
                writes.firstOrNull()?.let { theirRelay ->
                    val mine = Filter(kinds = listOf(1), authors = listOf(observer))
                    Readiness.Counts(relays.count(searchRelay, mine), relays.count(theirRelay, mine))
                }

            Readiness.Facts(
                writeRelays = writes,
                relayListSeen = relayList != null,
                scoreListSeen = scoreEvent.await() != null,
                rankService = provider?.first,
                rankRelay = provider?.second,
                scores = scores,
                probeAuthed = authed.await(),
                probeAnon = anon.await(),
                posts = posts,
            )
        }

    /**
     * The reader's Blossom servers, from their own kind 10063.
     *
     * Lives here rather than in the publish path because BOTH need it: the
     * storage readiness chain asks at pre-flight so a reader learns they have
     * nowhere to publish BEFORE an edition is written, and the publish path
     * asks again because that is where the manifest is going. One reader, one
     * parser, no chance of the two disagreeing about what counts as a server.
     *
     * https only. A Blossom PUT is an HTTPS call and the sanitizer allows no
     * other scheme; a plain-http entry is a server we cannot use.
     */
    suspend fun blossomServers(
        observer: String,
        hosts: List<String>,
    ): List<String> {
        val filter = Filter(kinds = listOf(BlossomServersEvent.KIND), authors = listOf(observer))
        // All of their relays, and ours as well -- which is deliberately NOT
        // what `Announce.editions` does. That read decides what a reader is
        // told they have published, so an answer only our relay can give is a
        // lie about where their paper is. This one only decides where to try
        // uploading, and finding their server list on one more host cannot
        // mislead anyone: a wrong list fails at the upload, loudly.
        val events =
            coroutineScope {
                (hosts + searchRelay)
                    .distinct()
                    .map { host -> async { runCatching { relays.fetch(host, filter, idle = 10_000) }.getOrDefault(emptyList()) } }
                    .awaitAll()
                    .flatten()
            }
        val newest = events.maxByOrNull { it.createdAt } ?: return emptyList()
        return BlossomServersEvent(newest.id, newest.pubKey, newest.createdAt, newest.tags, newest.content, newest.sig)
            .servers()
            .map { it.trimEnd('/') }
            .filter { it.startsWith("https://", ignoreCase = true) }
            .distinct()
    }

    /**
     * Just the reader's write relays, for callers that need only those.
     *
     * Publishing needs to know where a manifest goes, and it used to find out by
     * running the whole readiness chain: two NIP-50 searches and four COUNTs
     * against a shared relay, to read one kind 10002. One fetch answers it.
     */
    suspend fun writeRelaysOf(observer: String): List<String> = writeRelays(one(AdvertisedRelayListEvent.KIND, observer))

    /**
     * NIP-65 write relays, via quartz.
     *
     * `writeRelays()` already knows that an `r` tag with no marker means BOTH —
     * the rule that, read wrong, reports "no write relays" for the majority of
     * real relay lists and sends the reader off to fix something that works.
     */
    internal fun writeRelays(event: Event?): List<String> =
        asRelayList(event)
            ?.writeRelays()
            // quartz returns what the tag SAID; it does not vet the scheme, and a
            // real 10002 in the wild carries `https://` entries. Measured: for
            // tags [https://example.com, wss://ok.example.com] `writeRelays()`
            // returns both. We dial these, so anything that is not a websocket is
            // dropped here — the marker semantics above are quartz's, this is
            // ours because it is about what we then do with the answer.
            ?.filter { it.startsWith("wss://") || it.startsWith("ws://") }
            ?: emptyList()

    private fun asRelayList(event: Event?): AdvertisedRelayListEvent? =
        when (event) {
            null -> null
            is AdvertisedRelayListEvent -> event
            else -> AdvertisedRelayListEvent(event.id, event.pubKey, event.createdAt, event.tags, event.content, event.sig)
        }

    /**
     * The `30382:rank` service and the relay it publishes to.
     *
     * A 10040 naming only `30382:followers` can ORDER a list but cannot RANK
     * one, and an entry without a relay hint resolves to nothing in the store's
     * provider map. Both are rejected here by quartz's own tag parser rather
     * than by a local rule that could drift away from the relay's.
     */
    internal fun rankProvider(event: Event?): Pair<String, String>? =
        event
            ?.tags
            ?.serviceProviders()
            ?.firstOrNull { it.service == ProviderTypes.rank }
            ?.let { it.pubkey to it.relayUrl.url }

    private suspend fun one(
        kind: Int,
        author: String,
    ): Event? =
        relays
            .fetch(searchRelay, Filter(kinds = listOf(kind), authors = listOf(author), limit = 1))
            .maxByOrNull { it.createdAt }

    /**
     * The same question asked twice, once through the lens and once without.
     *
     * `since` IS REQUIRED, and leaving it off is not a tidier filter but a
     * broken probe. Measured against search-staging on 2026-08-17: this search
     * returns 12 events immediately with a 24-hour `since` and times out with
     * none at all. Both sides then come back zero, which [Readiness] correctly
     * reads as a quiet window rather than a broken lens — so link 4 passes
     * every time while testing nothing. It shipped that way once.
     */
    internal fun rankedProbe(
        observer: String?,
        since: Long,
    ) = Filter(
        kinds = listOf(1),
        since = since,
        search = if (observer == null) "sort:rank" else "observer:$observer sort:rank",
        limit = 12,
    )

    companion object {
        /** kind 0 for a batch of authors. */
        fun profileFilter(authors: List<String>) = Filter(kinds = listOf(MetadataEvent.KIND), authors = authors)
    }
}
