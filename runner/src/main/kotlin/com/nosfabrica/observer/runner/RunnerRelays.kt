package com.nosfabrica.observer.runner

import com.nosfabrica.observer.frontpage.INCLUDE_SPAM
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.client.INostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.fetchAllWithHooks
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.publishAndCollectResults
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip5aStaticWebsites.observer.ObserverSchedule
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTaskEvent

/** What a read across some relays returned, and whether any of them answered at all. */
class RelayRead(
    val events: List<Event>,
    /** At least one relay reached EOSE (or CLOSED): an empty [events] then means "none", not "unknown". */
    val anyServed: Boolean,
)

/** The worker's whole use of relays: read with a filter, publish one event. Tests fake this. */
interface RelayAccess {
    suspend fun fetch(
        relays: Set<NormalizedRelayUrl>,
        filter: Filter,
    ): RelayRead

    /** How many of [relays] accepted [event]. */
    suspend fun publish(
        event: Event,
        relays: Set<NormalizedRelayUrl>,
    ): Int
}

/** [RelayAccess] through Quartz's client. */
class QuartzRelayAccess(
    private val client: INostrClient,
    private val idleMs: Long = 10_000,
) : RelayAccess {
    override suspend fun fetch(
        relays: Set<NormalizedRelayUrl>,
        filter: Filter,
    ): RelayRead {
        if (relays.isEmpty()) return RelayRead(emptyList(), false)
        val result = client.fetchAllWithHooks(relays.associateWith { listOf(filter) }, idleTimeoutMs = idleMs) { _, _ -> true }
        return RelayRead(result.events.map { it.second }.distinctBy { it.id }, result.anyRelayServed)
    }

    override suspend fun publish(
        event: Event,
        relays: Set<NormalizedRelayUrl>,
    ): Int = if (relays.isEmpty()) 0 else client.publishAndCollectResults(event, relays).count { it.value.accepted }
}

/**
 * The reads the worker makes, on top of [RelayAccess]. A person's lists come from their own
 * NIP-65 write relays, found through the [index] relays (the search relay mirrors replaceable
 * kinds, but answers only a `search` that says `include:spam`).
 */
class RunnerNostr(
    val access: RelayAccess,
    private val index: Set<NormalizedRelayUrl>,
    private val searchRelay: NormalizedRelayUrl?,
) {
    /** A filter as one relay must be asked it: the search relay closes a REQ without a token. */
    private fun forRelay(
        relay: NormalizedRelayUrl,
        filter: Filter,
    ) = if (relay == searchRelay) filter.copy(search = INCLUDE_SPAM) else filter

    /** [filter] on [relays] and the index relays, the search relay asked with its token. */
    suspend fun read(
        relays: Set<NormalizedRelayUrl>,
        filter: Filter,
    ): RelayRead = fetch(relays + index, filter)

    private suspend fun fetch(
        relays: Set<NormalizedRelayUrl>,
        filter: Filter,
    ): RelayRead {
        if (searchRelay == null || searchRelay !in relays) return access.fetch(relays, filter)
        val others = access.fetch(relays - searchRelay, filter)
        val search = access.fetch(setOf(searchRelay), forRelay(searchRelay, filter))
        return RelayRead((others.events + search.events).distinctBy { it.id }, others.anyServed || search.anyServed)
    }

    /** [pubkey]'s NIP-65 write relays, as `wss` URLs only; empty when they published none. */
    suspend fun writeRelays(pubkey: HexKey): Set<NormalizedRelayUrl> {
        val list =
            fetch(index, Filter(kinds = listOf(AdvertisedRelayListEvent.KIND), authors = listOf(pubkey), limit = 4))
                .events
                .filterIsInstance<AdvertisedRelayListEvent>()
                .filter { it.pubKey == pubkey }
                .maxByOrNull { it.createdAt } ?: return emptySet()
        // `writeRelays()` doesn't vet schemes; and a relay that names an internal host is refused here.
        return list
            .writeRelays()
            .orEmpty()
            .filter { UrlGuard.refusal(it, setOf("wss")) == null }
            .mapNotNull { RelayUrlNormalizer.normalizeOrNull(it) }
            .toSet()
    }

    /** The newest event matching [filter] by [author] on [relays] plus the index relays. */
    suspend fun newest(
        author: HexKey,
        relays: Set<NormalizedRelayUrl>,
        filter: Filter,
    ): Event? =
        fetch(relays + index, filter)
            .events
            .filter { it.pubKey == author }
            .maxWithOrNull(compareBy<Event> { it.createdAt }.thenByDescending { it.id })

    /**
     * The Observer schedules (kind 34817, `d=observer`) that [pairs]' owners published naming
     * their Assistants, newest per author, read from each owner's write relays and the index
     * relays. Nothing here is trusted yet: the worker checks each one ([ObserverRunner]).
     */
    suspend fun schedules(pairs: List<AssistantPair>): List<ScheduledTaskEvent> {
        if (pairs.isEmpty()) return emptyList()
        // One REQ per relay per chunk of pairs: each names its owners as authors and only their
        // own Assistants in `#p`, so the frame stays small however many Assistants there are.
        val byRelay = HashMap<NormalizedRelayUrl, MutableSet<AssistantPair>>()
        index.forEach { relay -> byRelay.getOrPut(relay) { HashSet() }.addAll(pairs) }
        for (pair in pairs) writeRelays(pair.owner).forEach { byRelay.getOrPut(it) { HashSet() }.add(pair) }
        val found = ArrayList<Event>()
        for ((relay, some) in byRelay) {
            for (chunk in some.sortedBy { it.owner }.chunked(PAIRS_PER_REQ)) {
                val filter =
                    Filter(
                        kinds = listOf(ScheduledTaskEvent.KIND),
                        authors = chunk.map { it.owner }.distinct(),
                        tags = mapOf("d" to listOf(ObserverSchedule.D_TAG), "p" to chunk.map { it.assistant }.distinct()),
                    )
                found += fetch(setOf(relay), filter).events
            }
        }
        return found
            .filterIsInstance<ScheduledTaskEvent>()
            .filter { ObserverSchedule.isObserverSchedule(it) }
            .groupBy { it.pubKey }
            .map { (_, events) -> events.maxWith(compareBy<Event> { it.createdAt }.thenByDescending { it.id }) }
    }

    companion object {
        /** Two 64-hex keys a pair: far under the search relay's 262,144-byte REQ frame. */
        const val PAIRS_PER_REQ = 500

        fun relaysOf(urls: List<String>): Set<NormalizedRelayUrl> =
            urls.mapNotNull { RelayUrlNormalizer.normalizeOrNull(it.trim()) }.toSet()
    }
}
