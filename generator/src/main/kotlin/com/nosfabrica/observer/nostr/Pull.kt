package com.nosfabrica.observer.nostr

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * The desks a front page is made of, and why each earns a column.
 *
 * Not "every kind the relay holds" — the kinds that turned out to carry a
 * story. A desk is one REQ, so a desk that returns nothing costs one
 * subscription and answers "was there any today" honestly.
 *
 * A desk may span SEVERAL kinds, which is only safe because each is asked on
 * its own subscription. While the desks shared one REQ, results had to be
 * recovered by kind and two desks claiming the same kind would have collided —
 * that is exactly the bug that filed the anonymous control run as news. Video
 * is the desk that needs it; see [VIDEOS].
 */
enum class Desk(
    val kinds: List<Int>,
    val label: String,
    val limit: Int,
) {
    NOTES(listOf(1), "notes", 400),
    PICTURES(listOf(20), "picture posts", 60),

    /**
     * Video: the current kinds and the deprecated ones together, because the
     * deprecated ones are where the video actually is.
     *
     * NIP-71 moved video to `kind 21` (normal) and `kind 22` (short), replacing
     * `34235` and `34236`. Measured through the prototype observer on
     * 2026-08-18, one 24-hour window at a trust floor of 20:
     *
     *     kind 21 -> 0 events        kind 34235 -> 6 from 5 authors
     *     kind 22 -> 0 events        kind 34236 -> 37 from 13 authors
     *
     * Asking only for the current kinds would have printed no video at all.
     * Both are asked: the new ones cost nothing and will fill as clients
     * migrate, the old ones carry today's. Re-measure before dropping either —
     * the point of this note is that the answer was not what the spec says.
     */
    VIDEOS(listOf(21, 34235), "videos", 40),
    SHORTS(listOf(22, 34236), "short videos", 40),
    FILES(listOf(1063), "file metadata", 50),
    HIGHLIGHTS(listOf(9802), "highlights", 50),
    ARTICLES(listOf(30023), "long-form", 100),
    CLASSIFIEDS(listOf(30402), "classifieds", 30),
    WIKI(listOf(30818), "wiki entries", 30),
    CALENDAR(listOf(31923), "calendar events", 100),
    APPS(listOf(32267), "app releases", 30),
}

/** A byline, resolved from a kind 0 through quartz's own metadata reader. */
data class Byline(
    val pubkey: String,
    val createdAt: Long,
    val name: String?,
    val nip05: String?,
) {
    fun display(): String = name?.takeIf { it.isNotBlank() } ?: pubkey.take(8)

    companion object {
        fun from(event: Event): Byline? {
            val meta = (
                event as? MetadataEvent
                    ?: MetadataEvent(event.id, event.pubKey, event.createdAt, event.tags, event.content, event.sig)
            )
            val user = runCatching { meta.contactMetaData() }.getOrNull()
            return Byline(
                pubkey = event.pubKey,
                createdAt = event.createdAt,
                name = user?.bestName(),
                nip05 = user?.nip05,
            )
        }
    }
}

/** Everything one edition is written from. */
data class Corpus(
    val observer: String,
    val since: Long,
    val until: Long,
    /** Chosen by the lens — the paper. */
    val ranked: Map<Desk, List<Event>>,
    /** The same window with no lens at all — the Instrument panel. */
    val control: List<Event>,
    val profiles: Map<String, Byline>,
) {
    val notes: List<Event> get() = ranked[Desk.NOTES].orEmpty()

    fun all(): List<Event> = ranked.values.flatten()

    fun byline(pubkey: String): String = profiles[pubkey]?.display() ?: pubkey.take(8)
}

class Pull(
    private val relays: Relays,
    private val searchRelay: String,
    private val trustFloor: Int = DEFAULT_TRUST_FLOOR,
) {
    /**
     * A bare `observer:<pk> sort:rank` with no search term is a valid NIP-50
     * query and returns a ranked recency feed. That is the whole product, and
     * it is worth stating because it looks like a mistake: every other client
     * sends a term.
     *
     * `filter:rank:gte` is the trust floor, and it is not redundant with
     * `limit`. See [DEFAULT_TRUST_FLOOR]. It is NOT applied to the control run:
     * that query is the anonymous read, and filtering it would destroy the only
     * comparison this project makes.
     */
    private fun filter(
        kinds: List<Int>,
        since: Long,
        until: Long,
        limit: Int,
        observer: String?,
    ): Filter =
        Filter(
            kinds = kinds,
            since = since,
            // BOTH ends. `until` was carried all the way into Corpus and never
            // put into a filter, so the window had a start and no finish: a
            // backdated run asked for "the 24 hours ending last Tuesday" and got
            // everything from last Monday to now instead. Invisible in normal
            // use, because the server always passes the present.
            until = until,
            limit = limit,
            // Null is the control run, and the difference between these two
            // strings is the entire product. `sort:rank` without a resolvable
            // observer does not fail: it silently becomes the anonymous
            // ranking, which on a measured window was 209 of 400 posts from one
            // spam account. That is why nothing gets here without a lens the
            // readiness chain has already confirmed.
            search =
                if (observer == null) {
                    "sort:rank"
                } else {
                    "observer:$observer sort:rank filter:rank:gte:$trustFloor"
                },
        )

    suspend fun corpus(
        observer: String,
        since: Long,
        until: Long,
    ): Corpus {
        val desks = Desk.entries

        // ONE REQ PER DESK, all at once, plus the control run.
        //
        // The obvious shape is one REQ carrying all nine filters, and that is
        // what this did. It costs nothing in wall-clock -- these are ten
        // subscriptions on one socket against a relay advertising a limit of
        // fifty -- and it buys back the thing the quartz migration lost.
        //
        // quartz's `fetchAll` returns the filters MERGED, so a batched call has
        // to recover each desk by kind. That works until two filters share a
        // kind, and two of them do: the control run is kind 1 exactly like the
        // notes desk. Merged, its anonymous results landed in the ranked notes
        // and the Instrument panel's overlap went to ~100%, which is the one
        // number this whole product exists to report. Asking separately means
        // each answer arrives already attributed and no future desk can collide
        // with another by sharing a kind.
        val (ranked, control) =
            coroutineScope {
                val asked =
                    desks.map { desk ->
                        desk to
                            async { relays.fetch(searchRelay, filter(desk.kinds, since, until, desk.limit, observer), idle = 25_000) }
                    }
                val controlAsked = async { controlRun(since, until) }
                asked.associate { (desk, job) -> desk to job.await().take(desk.limit) } to controlAsked.await()
            }

        // Every author we are about to print, plus everyone the control run
        // names -- the Instrument panel prints the spammer's own text, and a hex
        // string there would hide what makes the comparison land.
        val keys = (ranked.values.flatten() + control).map { it.pubKey }.distinct()
        return Corpus(observer, since, until, ranked, control, profiles(keys))
    }

    /**
     * Run separately so its results cannot be confused with the ranked ones.
     *
     * Both queries are kind 1 over the same window, so a single fetch returns
     * them interleaved with no way to tell which side an event came from — and
     * the Instrument panel's whole claim is about the difference between them.
     */
    private suspend fun controlRun(
        since: Long,
        until: Long,
    ): List<Event> = relays.fetch(searchRelay, filter(Desk.NOTES.kinds, since, until, Desk.NOTES.limit, null), idle = 25_000)

    companion object {
        /**
         * The lowest trust score this reader's paper will print.
         *
         * `limit` alone is not a quality gate, and measuring that was the
         * surprise. The obvious model — the relay ranks the window and `limit`
         * takes the top N, so a floor below the Nth score does nothing — is
         * WRONG. Measured against search-staging on 2026-08-18 for the
         * prototype observer, over a 24-hour window of 35,084 candidate notes:
         *
         *     no floor   35,084      gte:20   11,838
         *     gte:5      22,899      gte:30    9,607
         *     gte:10     16,265      gte:50    6,834
         *
         * and at `limit = 400`, adding `gte:20` REPLACED 49 of the 400 notes.
         * So roughly one in eight of what the paper printed scored under 20 on
         * the reader's own web of trust, and the floor swaps those out for
         * better material.
         *
         * It matters most where it is hardest to see. A reader with a rich lens
         * has twelve thousand notes above the floor and gives up nothing; a
         * reader with a thin lens, or anybody on a quiet day, is the case where
         * a bare `limit` scrapes downward to fill its quota with material
         * nobody vouched for. The floor turns the cap from a target back into a
         * ceiling.
         *
         * `filter:rank:` is this relay's own NIP-50 extension, like `observer:`
         * — the generator is already specific to it, so this adds no coupling
         * that was not there.
         */
        const val DEFAULT_TRUST_FLOOR = 20
    }

    /** kind 0 for everyone we will name. Newest wins; batched because 244 authors is normal. */
    suspend fun profiles(pubkeys: List<String>): Map<String, Byline> {
        if (pubkeys.isEmpty()) return emptyMap()
        val filters = pubkeys.chunked(100).map { ReadinessProbe.profileFilter(it) }
        val best = mutableMapOf<String, Byline>()
        relays.fetch(searchRelay, filters, idle = 20_000).forEach { event ->
            val p = Byline.from(event) ?: return@forEach
            val seen = best[p.pubkey]
            if (seen == null || seen.createdAt < p.createdAt) best[p.pubkey] = p
        }
        return best
    }
}
