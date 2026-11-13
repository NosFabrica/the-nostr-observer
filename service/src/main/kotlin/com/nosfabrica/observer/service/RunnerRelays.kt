package com.nosfabrica.observer.service

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
import com.vitorpamplona.quartz.utils.Log
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** What a read across some relays returned, and which of them answered it in full. */
class RelayRead(
    val events: List<Event>,
    /** The relays that reached EOSE: on those, a missing event is missing, not unknown. */
    val served: Set<NormalizedRelayUrl>,
    /** The relays that answered with a CLOSED (not an auth wall): the REQ itself, maybe its size, was refused. */
    val refused: Set<NormalizedRelayUrl> = emptySet(),
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

/**
 * [RelayAccess] through Quartz's client. Every relay is asked on its own subscription, in
 * parallel, and each one is bounded twice: by the wall clock ([deadlineMs]) and by how many events
 * it may send ([maxEvents]). Quartz's own window is an idle one, reset by every event, so a relay
 * streaming junk would otherwise hold the read, and the poll, forever. A relay cut off by either
 * bound didn't serve the read: what it sent that verifies is kept, but it never counts toward a
 * quorum. One relay on its own subscription also means a misbehaving one costs only its own
 * answer, never its neighbours'.
 *
 * What bounds a single WebSocket message is not here: OkHttp has no inbound message limit, and
 * buffers a whole message before handing it on. The relay client's sockets are bounded instead
 * ([ByteBudgetSocketFactory]).
 */
class QuartzRelayAccess(
    private val client: INostrClient,
    private val idleMs: Long = 10_000,
    private val deadlineMs: Long = FETCH_DEADLINE_MS,
    private val maxEvents: Int = MAX_EVENTS_PER_RELAY,
) : RelayAccess {
    override suspend fun fetch(
        relays: Set<NormalizedRelayUrl>,
        filter: Filter,
    ): RelayRead {
        if (relays.isEmpty()) return RelayRead(emptyList(), emptySet())
        val reads = coroutineScope { relays.map { relay -> async { fetchOne(relay, filter) } }.awaitAll() }
        return RelayRead(
            reads.flatMap { it.events }.distinctBy { it.id },
            reads.flatMapTo(HashSet()) { it.served },
            reads.flatMapTo(HashSet()) { it.refused },
        )
    }

    /** Thrown from the event hook to end a relay's read: the hook's exception unsubscribes it. */
    private class TooManyEvents : Exception()

    private suspend fun fetchOne(
        relay: NormalizedRelayUrl,
        filter: Filter,
    ): RelayRead {
        // Collected here, not from the result: a read cut off by the deadline returns nothing.
        val kept = ArrayList<Event>()
        var sent = 0
        val result =
            try {
                withTimeoutOrNull(deadlineMs) {
                    client.fetchAllWithHooks(mapOf(relay to listOf(filter)), idleTimeoutMs = idleMs) { _, e ->
                        if (++sent > maxEvents) throw TooManyEvents()
                        // Checked again by RunnerNostr; dropping a forgery here keeps it out of memory.
                        if (RunnerNostr.acceptable(e)) synchronized(kept) { kept += e }
                        false
                    }
                }
            } catch (e: TooManyEvents) {
                null
            }
        if (result == null) Log.d(TAG) { "relay=${relay.url} cut off: deadline or more than $maxEvents events" }
        val reason = result?.doneReasons?.get(relay)
        return RelayRead(
            synchronized(kept) { kept.toList() },
            if (reason == DONE_REASON_EOSE) setOf(relay) else emptySet(),
            if (reason?.startsWith("closed:") == true) setOf(relay) else emptySet(),
        )
    }

    override suspend fun publish(
        event: Event,
        relays: Set<NormalizedRelayUrl>,
    ): Int = if (relays.isEmpty()) 0 else client.publishAndCollectResults(event, relays).count { it.value.accepted }

    companion object {
        private const val TAG = "QuartzRelayAccess"

        /** The longest one relay may take to answer one read, whatever it sends meanwhile. */
        const val FETCH_DEADLINE_MS = 20_000L

        /**
         * The most events one relay may send for one read. The largest read the worker makes is a
         * chunk of [RunnerNostr.AUTHORS_PER_REQ] schedules, one each: four times that is a relay
         * holding old versions too, and anything past it is a relay sending junk.
         */
        const val MAX_EVENTS_PER_RELAY = 4 * RunnerNostr.AUTHORS_PER_REQ
    }
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
    /**
     * The longest one read may take, whatever [access] does: a backstop over its own per-relay
     * bound ([QuartzRelayAccess.FETCH_DEADLINE_MS]). Past it, nothing was served.
     */
    private val deadlineMs: Long = READ_DEADLINE_MS,
    /** How many relays a batch read asks at once ([writeRelaysOf]). */
    private val relayParallelism: Int = RELAY_PARALLELISM,
    /** The authors a batch read asks one relay for in one REQ ([byAuthors]). */
    private val authorsPerReq: Int = AUTHORS_PER_REQ,
    /** The smallest batch a relay that CLOSEs one is asked again with. */
    private val minChunk: Int = MIN_CHUNK,
) {
    private class CachedRelays(
        val relays: Set<NormalizedRelayUrl>,
        val at: Long,
    )

    private val relayLists = ConcurrentHashMap<HexKey, CachedRelays>()

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
            withTimeoutOrNull(deadlineMs) {
                if (searchRelay == null || searchRelay !in relays) {
                    access.fetch(relays, filter)
                } else {
                    coroutineScope {
                        val others = async { access.fetch(relays - searchRelay, filter) }
                        val search = async { access.fetch(setOf(searchRelay), forRelay(searchRelay, filter)) }
                        val (o, s) = others.await() to search.await()
                        RelayRead(o.events + s.events, o.served + s.served, o.refused + s.refused)
                    }
                }
            } ?: RelayRead(emptyList(), emptySet()).also { Log.d(TAG) { "a read of ${relays.size} relays ran past ${deadlineMs}ms" } }
        return RelayRead(raw.events.distinctBy { it.id }.filter { acceptable(it, at) }, raw.served, raw.refused)
    }

    /** [pubkey]'s NIP-65 write relays, as `wss` URLs only; empty when they published none. Cached ([relayListTtlSeconds]). */
    suspend fun writeRelays(pubkey: HexKey): Set<NormalizedRelayUrl> = writeRelaysOf(listOf(pubkey))[pubkey].orEmpty()

    /**
     * Each of [pubkeys]' NIP-65 write relays, read from the index relays ([byAuthors]), and
     * reused for [relayListTtlSeconds]. A list no index relay answered for in full isn't cached:
     * the next call asks again. Lists past their time are dropped, so people the worker no
     * longer asks about don't stay in memory.
     */
    suspend fun writeRelaysOf(pubkeys: Collection<HexKey>): Map<HexKey, Set<NormalizedRelayUrl>> {
        val at = now()
        relayLists.entries.removeIf { at - it.value.at >= relayListTtlSeconds }
        val out = HashMap<HexKey, Set<NormalizedRelayUrl>>()
        val stale = ArrayList<HexKey>()
        for (key in pubkeys.distinct()) {
            val cached = relayLists[key]
            if (cached != null) out[key] = cached.relays else stale += key
        }
        if (stale.isEmpty()) return out
        val reads =
            onEach(index) { relay -> byAuthors(relay, stale) { Filter(kinds = listOf(AdvertisedRelayListEvent.KIND), authors = it) } }
        val newest =
            reads
                .flatMap { it.events }
                .filterIsInstance<AdvertisedRelayListEvent>()
                .groupBy { it.pubKey }
                .mapValues { (_, lists) -> newestOf(lists) as AdvertisedRelayListEvent }
        val covered = reads.flatMapTo(HashSet()) { it.covered }
        for (key in stale) {
            val relays = newest[key]?.let(::vetted).orEmpty()
            out[key] = relays
            if (key in covered || key in newest) relayLists[key] = CachedRelays(relays, at)
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
     * The newest event matching [filter] by [author], and whether more than half of [relays]
     * answered for it in full: [ScheduleRead]'s majority rule, for a list read right before
     * spending (the Assistant's kind 12000). [relays] empty is judged on the index relays.
     */
    suspend fun newestWithQuorum(
        author: HexKey,
        relays: Set<NormalizedRelayUrl>,
        filter: Filter,
    ): Pair<Event?, Boolean> {
        val read = fetch(relays + index, filter)
        return newestOf(read.events.filter { it.pubKey == author }) to hasQuorum(relays, read.served)
    }

    /** What [byAuthors] read on one relay: the events, and the authors it answered for in full. */
    private class AuthorsRead(
        val events: List<Event>,
        val covered: Set<HexKey>,
    )

    /**
     * [authors]' events of one replaceable kind on [relay], in chunks of [authorsPerReq] with
     * `limit` set to the chunk's size, and the authors the relay answered for in full.
     *
     * A relay answered for an author when it sent that author's event, or when it reached EOSE on
     * a page that wasn't full. A full page (as many events as the limit, or [FULL_PAGE_FLOOR], the
     * smallest page a relay is assumed to cut to without saying so) may have left authors out:
     * those are asked again in chunks half the size, until the page isn't full. A CLOSED (a REQ
     * too large for the relay) is asked again the same way, down to [minChunk]. A relay that
     * doesn't answer (down, slow, cut off) isn't asked again: it answered for nobody.
     */
    private suspend fun byAuthors(
        relay: NormalizedRelayUrl,
        authors: Collection<HexKey>,
        filterOf: (List<HexKey>) -> Filter,
    ): AuthorsRead {
        val events = ArrayList<Event>()
        val covered = HashSet<HexKey>()
        var left = authors.distinct().sorted()
        var size = authorsPerReq
        var asked = 0
        while (left.isNotEmpty() && asked < MAX_REQS_PER_RELAY) {
            val again = ArrayList<HexKey>()
            for (chunk in left.chunked(size)) {
                if (asked++ >= MAX_REQS_PER_RELAY) break
                val wanted = chunk.toHashSet()
                val read = fetch(setOf(relay), filterOf(chunk).copy(limit = chunk.size))
                val mine = read.events.filter { it.pubKey in wanted }
                events += mine
                when {
                    relay in read.served -> {
                        val sent = mine.mapTo(HashSet()) { it.pubKey }
                        covered += sent
                        if (read.events.size >= minOf(chunk.size, FULL_PAGE_FLOOR) && chunk.size > 1) {
                            again += chunk.filter { it !in sent }
                        } else {
                            covered += chunk
                        }
                    }

                    relay in read.refused && size > minChunk -> {
                        again += chunk
                    }
                }
            }
            if (again.isEmpty() || size <= 1) break
            left = again
            size = (size / 2).coerceAtLeast(1)
        }
        return AuthorsRead(events, covered)
    }

    /** [block] on each of [relays], [relayParallelism] at a time. */
    private suspend fun <T> onEach(
        relays: Collection<NormalizedRelayUrl>,
        block: suspend (NormalizedRelayUrl) -> T,
    ): List<T> {
        val gate = Semaphore(relayParallelism)
        return coroutineScope { relays.map { relay -> async { gate.withPermit { block(relay) } } }.awaitAll() }
    }

    /**
     * [owner]'s newest Observer schedule (kind 34817, `d=observer`), from their write relays and
     * the index, and whether more than half of their relays answered: the read a wake starts
     * with, and the one right before spending. Asked by author, never by `#p`: an owner who took
     * printing back published a newer version without the `p`, and a `#p` read would never see
     * it, so a relay still holding the older version would keep the service printing. The caller
     * checks that the newest names exactly this owner's Assistant.
     */
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
        private const val TAG = "RunnerNostr"

        /** One 64-hex author is 67 bytes of JSON: 500 is far under the search relay's 262,144-byte REQ frame. */
        const val AUTHORS_PER_REQ = 500

        /**
         * A page this long may be a relay's own cut, made without saying so (strfry cuts at 500,
         * others lower): its missing authors are asked again in smaller chunks ([byAuthors]).
         */
        const val FULL_PAGE_FLOOR = 100

        /** The smallest chunk a relay that CLOSEs a batch is asked again with. */
        const val MIN_CHUNK = 25

        /** The most REQs [byAuthors] sends one relay for one batch read. */
        const val MAX_REQS_PER_RELAY = 64

        /** How many relays a batch read asks at once. */
        const val RELAY_PARALLELISM = 16

        /** [QuartzRelayAccess.FETCH_DEADLINE_MS] and a little more: the backstop over any [RelayAccess]. */
        const val READ_DEADLINE_MS = QuartzRelayAccess.FETCH_DEADLINE_MS + 5_000

        /** An hour: a person's relays rarely move, and a stale list only costs a missed read. */
        const val RELAY_LIST_TTL_SECONDS = 3_600L

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
