package com.nosfabrica.observer.runner

import com.nosfabrica.observer.frontpage.INCLUDE_SPAM
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.relay.client.INostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.DONE_REASON_EOSE
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.fetchAllWithHooks
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.publishAndCollectResults
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip5aStaticWebsites.observer.ObserverSchedule
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTaskEvent
import com.vitorpamplona.quartz.utils.TimeUtils
import java.util.concurrent.ConcurrentHashMap

/** What a read across some relays returned, and which of them answered it in full. */
class RelayRead(
    val events: List<Event>,
    /** The relays that reached EOSE: on those, a missing event is missing, not unknown. */
    val served: Set<NormalizedRelayUrl>,
) {
    /** At least one relay reached EOSE: an empty [events] then means "none", not "unknown". */
    val anyServed: Boolean get() = served.isNotEmpty()

    /** Whether any of [relays] answered: a read judged by the relays that hold the data, not by any that happen to be asked too. */
    fun servedAny(relays: Set<NormalizedRelayUrl>): Boolean = served.any { it in relays }
}

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
        if (relays.isEmpty()) return RelayRead(emptyList(), emptySet())
        // Checked again by RunnerNostr; dropping a forgery here keeps it out of the collected list too.
        val result =
            client.fetchAllWithHooks(
                relays.associateWith { listOf(filter) },
                idleTimeoutMs = idleMs,
            ) { _, e -> RunnerNostr.acceptable(e) }
        val served = result.doneReasons.filterValues { it == DONE_REASON_EOSE }.keys
        return RelayRead(result.events.map { it.second }.distinctBy { it.id }, served)
    }

    override suspend fun publish(
        event: Event,
        relays: Set<NormalizedRelayUrl>,
    ): Int = if (relays.isEmpty()) 0 else client.publishAndCollectResults(event, relays).count { it.value.accepted }
}

/** An owner's newest Observer schedule, as read, and whether enough of their relays answered to trust that it is the newest. */
class ScheduleRead(
    val event: ScheduledTaskEvent,
    /** More than half of the owner's write relays (the index relays, when they list none) reached EOSE. */
    val quorum: Boolean,
)

/**
 * The reads the worker makes, on top of [RelayAccess]. A person's lists come from their own
 * NIP-65 write relays, found through the [index] relays (the search relay mirrors replaceable
 * kinds, but answers only a `search` that says `include:spam`).
 *
 * Nothing a relay sends is taken as read until its id and signature verify and it isn't dated
 * more than [MAX_FUTURE_SECONDS] ahead ([acceptable]): anyone can hand a relay an event with the
 * owner's or the Assistant's pubkey on it.
 */
class RunnerNostr(
    val access: RelayAccess,
    private val index: Set<NormalizedRelayUrl>,
    private val searchRelay: NormalizedRelayUrl?,
    private val now: () -> Long = { TimeUtils.now() },
    /** How long a person's NIP-65 list is reused before it is read again. */
    private val relayListTtlSeconds: Long = RELAY_LIST_TTL_SECONDS,
) {
    private class CachedRelays(
        val relays: Set<NormalizedRelayUrl>,
        val at: Long,
    )

    private val relayLists = ConcurrentHashMap<HexKey, CachedRelays>()

    /** When each owner with no schedule on the index relays last had their own relays asked. */
    private val offIndexCheckedAt = ConcurrentHashMap<HexKey, Long>()

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
        val at = now()
        val raw =
            if (searchRelay == null || searchRelay !in relays) {
                access.fetch(relays, filter)
            } else {
                val others = access.fetch(relays - searchRelay, filter)
                val search = access.fetch(setOf(searchRelay), forRelay(searchRelay, filter))
                RelayRead(others.events + search.events, others.served + search.served)
            }
        return RelayRead(raw.events.distinctBy { it.id }.filter { acceptable(it, at) }, raw.served)
    }

    /** [pubkey]'s NIP-65 write relays, as `wss` URLs only; empty when they published none. Cached ([relayListTtlSeconds]). */
    suspend fun writeRelays(pubkey: HexKey): Set<NormalizedRelayUrl> = writeRelaysOf(listOf(pubkey))[pubkey].orEmpty()

    /**
     * Each of [pubkeys]' NIP-65 write relays, read from the index relays in batches of
     * [AUTHORS_PER_REQ] authors, and reused for [relayListTtlSeconds]. A read that no index relay
     * answered isn't cached: the next call asks again.
     */
    suspend fun writeRelaysOf(pubkeys: Collection<HexKey>): Map<HexKey, Set<NormalizedRelayUrl>> {
        val at = now()
        val out = HashMap<HexKey, Set<NormalizedRelayUrl>>()
        val stale = ArrayList<HexKey>()
        for (key in pubkeys.distinct()) {
            val cached = relayLists[key]
            if (cached != null && at - cached.at < relayListTtlSeconds) out[key] = cached.relays else stale += key
        }
        for (chunk in stale.sorted().chunked(AUTHORS_PER_REQ)) {
            val read = fetch(index, Filter(kinds = listOf(AdvertisedRelayListEvent.KIND), authors = chunk))
            val newest =
                read.events
                    .filterIsInstance<AdvertisedRelayListEvent>()
                    .groupBy { it.pubKey }
                    .mapValues { (_, lists) ->
                        lists.maxWith(compareBy<Event> { it.createdAt }.thenByDescending { it.id }) as AdvertisedRelayListEvent
                    }
            for (key in chunk) {
                val relays = newest[key]?.let(::vetted).orEmpty()
                out[key] = relays
                if (read.anyServed) relayLists[key] = CachedRelays(relays, at)
            }
        }
        return out
    }

    /** `writeRelays()` doesn't vet schemes; and a relay that names an internal host is refused here. */
    private fun vetted(list: AdvertisedRelayListEvent): Set<NormalizedRelayUrl> =
        list
            .writeRelays()
            .orEmpty()
            .filter { UrlGuard.refusal(it, setOf("wss")) == null }
            .mapNotNull { RelayUrlNormalizer.normalizeOrNull(it) }
            .toSet()

    /** The newest event matching [filter] by [author] on [relays] plus the index relays. */
    suspend fun newest(
        author: HexKey,
        relays: Set<NormalizedRelayUrl>,
        filter: Filter,
    ): Event? = newestOf(fetch(relays + index, filter).events.filter { it.pubKey == author })

    /**
     * Every owner's newest Observer schedule (kind 34817, `d=observer`), and whether it was read
     * with a quorum. Asked by author, never by `#p`: an owner who took printing back published a
     * newer version without the `p`, and a `#p` read would never see it, so a relay still holding
     * the older version would keep the worker printing. The caller checks that the newest names
     * exactly this owner's Assistant.
     *
     * The index relays are asked first, for every owner; only the owners with a schedule there
     * (and, hourly, those without: [OFF_INDEX_RECHECK_SECONDS]) have their own write relays asked
     * too, in batches of [AUTHORS_PER_REQ] per relay.
     */
    suspend fun schedules(owners: Collection<HexKey>): List<ScheduleRead> {
        if (owners.isEmpty()) return emptyList()
        val found = HashMap<HexKey, MutableList<Event>>()
        val servedBy = HashMap<HexKey, MutableSet<NormalizedRelayUrl>>()

        suspend fun ask(
            relay: NormalizedRelayUrl,
            authors: List<HexKey>,
        ) {
            for (chunk in authors.sorted().chunked(AUTHORS_PER_REQ)) {
                val read = fetch(setOf(relay), scheduleFilter(chunk))
                read.events.forEach { found.getOrPut(it.pubKey) { ArrayList() } += it }
                if (relay in read.served) chunk.forEach { servedBy.getOrPut(it) { HashSet() } += relay }
            }
        }

        val all = owners.distinct()
        index.forEach { ask(it, all) }
        val at = now()
        // An owner the index shows no schedule for is still looked for on their own relays, but
        // only every [OFF_INDEX_RECHECK_SECONDS]: a schedule the index missed is found late, never lost.
        val scheduled =
            all.filter { owner ->
                found[owner].orEmpty().any(ObserverSchedule::isObserverSchedule) ||
                    (offIndexCheckedAt[owner]?.let { at - it >= OFF_INDEX_RECHECK_SECONDS } ?: true).also {
                        if (it) {
                            offIndexCheckedAt[owner] =
                                at
                        }
                    }
            }
        offIndexCheckedAt.keys.retainAll(all.toSet())
        val ownRelays = writeRelaysOf(scheduled)
        val byRelay = HashMap<NormalizedRelayUrl, MutableList<HexKey>>()
        for (owner in scheduled) {
            ownRelays[owner].orEmpty().filter { it !in index }.forEach {
                byRelay.getOrPut(
                    it,
                ) { ArrayList() } += owner
            }
        }
        for ((relay, some) in byRelay) ask(relay, some)

        return scheduled.mapNotNull { owner ->
            val newest =
                newestOf(
                    found[owner].orEmpty().filter {
                        it.pubKey == owner && ObserverSchedule.isObserverSchedule(it)
                    },
                ) as? ScheduledTaskEvent
            newest?.let { ScheduleRead(it, hasQuorum(ownRelays[owner].orEmpty(), servedBy[owner].orEmpty())) }
        }
    }

    /** [owner]'s newest Observer schedule read again, from their write relays and the index, with a quorum: the read right before spending. */
    suspend fun schedule(owner: HexKey): ScheduleRead? {
        val relays = writeRelays(owner)
        val read = fetch(relays + index, scheduleFilter(listOf(owner)))
        val newest = newestOf(read.events.filter { it.pubKey == owner && ObserverSchedule.isObserverSchedule(it) }) as? ScheduledTaskEvent
        return newest?.let { ScheduleRead(it, hasQuorum(relays, read.served)) }
    }

    /** More than half of [relays] (the index relays when there are none) are among [served]. */
    private fun hasQuorum(
        relays: Set<NormalizedRelayUrl>,
        served: Set<NormalizedRelayUrl>,
    ): Boolean {
        val asked = relays.ifEmpty { index }
        return asked.isNotEmpty() && asked.count { it in served } * 2 > asked.size
    }

    private fun scheduleFilter(authors: List<HexKey>) =
        Filter(kinds = listOf(ScheduledTaskEvent.KIND), authors = authors, tags = mapOf("d" to listOf(ObserverSchedule.D_TAG)))

    companion object {
        /** One 64-hex author is 67 bytes of JSON: 500 is far under the search relay's 262,144-byte REQ frame. */
        const val AUTHORS_PER_REQ = 500

        /** An hour: a person's relays rarely move, and a stale list only costs a missed read. */
        const val RELAY_LIST_TTL_SECONDS = 3_600L

        /** How often an owner the index shows no schedule for has their own relays asked anyway. */
        const val OFF_INDEX_RECHECK_SECONDS = 3_600L

        /** How far ahead of the worker's clock an event may be dated and still be read. */
        const val MAX_FUTURE_SECONDS = 600L

        /**
         * Whether [event] may be read at all: its id and signature verify and its `created_at` is
         * at most [MAX_FUTURE_SECONDS] ahead. A forged site dated next year would otherwise win
         * every newest-of read, and `sign` would refuse every publish after it.
         */
        fun acceptable(
            event: Event,
            at: Long = TimeUtils.now(),
        ): Boolean = event.createdAt <= at + MAX_FUTURE_SECONDS && event.verify()

        fun relaysOf(urls: List<String>): Set<NormalizedRelayUrl> =
            urls.mapNotNull { RelayUrlNormalizer.normalizeOrNull(it.trim()) }.toSet()
    }
}
