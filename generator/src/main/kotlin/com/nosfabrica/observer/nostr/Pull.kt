package com.nosfabrica.observer.nostr

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.security.MessageDigest
import java.util.HexFormat

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
     * Streams that are on the air RIGHT NOW, and only those.
     *
     * A `kind 30311` is replaceable and carries a `status`, so the record of a
     * finished stream sits in the window looking exactly like a running one.
     * Measured 2026-08-18: of 18 in a 24-hour window, 11 were `live` and 7 had
     * already `ended`. Listings for something that finished this morning are
     * not listings, so the ended ones are dropped here rather than left for the
     * writer to notice.
     *
     * Honest limit: "now" is generation time. The page is a static file, so a
     * stream can end between the edition being written and somebody reading it.
     * The prompt tells the writer to say when it started rather than to promise
     * it is still running.
     */
    LIVE(listOf(30311), "live now", 30) {
        override fun keeps(event: Event) = event.value("status").equals("live", ignoreCase = true)
    },
    POLLS(listOf(1068), "polls", 20),

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

    // 31922 is the all-day half of NIP-52 and 31923 the timed half. Reading
    // only one of them dropped whole-day events silently -- measured 2026-08-18
    // there were none, which is exactly how a gap like this stays invisible.
    CALENDAR(listOf(31922, 31923), "calendar events", 100),
    APPS(listOf(32267), "app releases", 30),
    GIT(listOf(30617), "code repositories", 30),
    ;

    /**
     * Whether an event still belongs on the desk after it arrives.
     *
     * Only [LIVE] needs it. A filter cannot express "and the `status` tag says
     * live", so the relay returns both and this drops the rest.
     */
    open fun keeps(event: Event): Boolean = true
}

/** A byline, resolved from a kind 0 through quartz's own metadata reader. */
data class Byline(
    val pubkey: String,
    val createdAt: Long,
    val name: String?,
    val nip05: String?,
) {
    /** Their name if they published one, and their npub if they did not. Never hex. */
    fun display(): String = name?.takeIf { it.isNotBlank() } ?: Names.short(pubkey)

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
    /**
     * How many notes the lens surfaced in this window, or null if the relay
     * would not say.
     *
     * The desks are CAPPED — the notes desk asks for 400 — so what we pull is
     * what we asked for and not what the day held. Without this the page can
     * only report its own digest, and that reads like the day: the first real
     * edition printed "555 EVENTS KEPT · 51 PRUNED" as a masthead statistic
     * when the window actually held around eleven thousand notes above the
     * trust floor.
     *
     * Null is a supported answer and must not become a guess. NIP-45 COUNT is
     * optional, and this store has spells of not answering it at all.
     */
    val dayNotes: Long?,
    val profiles: Map<String, Byline>,
) {
    val notes: List<Event> get() = ranked[Desk.NOTES].orEmpty()

    fun all(): List<Event> = ranked.values.flatten()

    fun byline(pubkey: String): String = profiles[pubkey]?.display() ?: Names.short(pubkey)

    /**
     * A short code for this edition, printed top-left of the folio.
     *
     * IT CANNOT BE THE HASH OF THE PAGE. That is a fixed point: printing the
     * page's own sha256 into the page changes the page, which changes the hash.
     * The published file does have a content hash — it is what Blossom stores
     * it under and what the upload authorization is bound to — but it exists
     * only after the page is final, and no ink on the page can name it.
     *
     * So this hashes what the edition is MADE of: who it was read for, the
     * window it covers, and the id of every event that came back. Two runs over
     * the same material print the same code, a run an hour later prints a
     * different one, and two readers on the same morning print different ones
     * because their lenses surfaced different things. That is what "this
     * specific unit" means for a paper — a print run, not a file.
     *
     * Six hex digits, which is sixteen million and is not a collision domain
     * anybody will meet: this identifies an edition to a person reading it,
     * beside a date and a name, not to a database.
     */
    fun code(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(observer.toByteArray())
        digest.update(since.toString().toByteArray())
        digest.update(until.toString().toByteArray())
        // Sorted, because the desks are pulled in parallel and the order they
        // finish in is a race. A code that changed between two runs over
        // identical material would be worse than no code at all.
        all().map { it.id }.sorted().forEach { digest.update(it.toByteArray()) }
        return HexFormat
            .of()
            .formatHex(digest.digest())
            .take(6)
            .uppercase()
    }
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
        /** Null asks for everything matching, which is what a COUNT wants and a REQ never does. */
        limit: Int?,
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
            //
            // The control says `include:spam` because the relay's auth gate
            // CLOSES a bare `sort:rank` outright now (see
            // [Relays.INCLUDE_SPAM]). It is still the anonymous ranking, not a
            // recency cut — measured 2026-08-30, `include:spam sort:rank` and
            // plain `include:spam` at the same limit share 0 events — so the
            // Instrument panel's comparison is unchanged.
            search =
                if (observer == null) {
                    "${Relays.INCLUDE_SPAM} sort:rank"
                } else {
                    "observer:$observer sort:rank filter:rank:gte:$trustFloor"
                },
        )

    /**
     * What actually belongs on a desk, once the relay has answered.
     *
     * Two rules a filter cannot express. [Desk.keeps] drops a live stream that
     * has already ended, and the reader's own posts are not the news: a paper
     * is what OTHER people did today, and reading your own words back under
     * your own masthead is the one thing in it you cannot learn anything from.
     * They rank highly through your own lens almost by construction, so without
     * this they crowd the front page.
     *
     * They stay in the CONTROL run, which is a measurement of the network
     * rather than a page, and they stay quotable when somebody else is replying
     * to them.
     */
    internal fun belongs(
        desk: Desk,
        observer: String,
        events: List<Event>,
    ): List<Event> = events.filter { desk.keeps(it) && it.pubKey != observer }

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
        val (ranked, control, dayNotes) =
            coroutineScope {
                val asked =
                    desks.map { desk ->
                        desk to
                            async {
                                belongs(
                                    desk,
                                    observer,
                                    relays.fetch(searchRelay, filter(desk.kinds, since, until, desk.limit, observer), idle = 25_000),
                                )
                            }
                    }
                val controlAsked = async { controlRun(since, until) }
                // One COUNT, for an honest denominator: the same question the
                // notes desk asks, without the cap. It is what makes "555 of
                // ~11,800" a sentence a reader could check.
                // NO LIMIT on a COUNT. Passing the desk's cap made the relay
                // count up to the cap and stop: it answered 400 for a window
                // holding around eleven thousand, which is a denominator that
                // agrees with the numerator by construction and says nothing.
                val countAsked =
                    async { relays.count(searchRelay, filter(Desk.NOTES.kinds, since, until, null, observer)) }
                Triple(
                    asked.associate { (desk, job) -> desk to job.await().take(desk.limit) },
                    controlAsked.await(),
                    countAsked.await(),
                )
            }

        // Every author we are about to print, plus everyone the control run
        // names -- the Instrument panel prints the spammer's own text, and a hex
        // string there would hide what makes the comparison land.
        //
        // And the people a HIGHLIGHT quotes. They wrote the sentence but signed
        // nothing in this window, so they appear in no event's pubKey; without
        // them the digest can only credit the excerpt to a hex prefix, which is
        // the same as not crediting it.
        val quoted = ranked[Desk.HIGHLIGHTS].orEmpty().mapNotNull { it.value("p") }.filter { it.length == 64 }

        // AND THE READER, who is in none of the above BECAUSE of the rule that
        // keeps them out of their own paper: their events are dropped from every
        // desk, so their key is in no author list, so their `kind 0` was never
        // asked for. The page names them -- "Ranked as …" -- and with no profile
        // that line came out as an npub. Caught by a real run, and it is exactly
        // the shape of bug two correct changes make together.
        val keys = ((ranked.values.flatten() + control).map { it.pubKey } + quoted + observer).distinct()
        return Corpus(observer, since, until, ranked, control, dayNotes, profiles(keys))
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

    /**
     * kind 0 for everyone we will name. Newest wins; batched because 244
     * authors is normal.
     *
     * [hosts] are asked alongside the search relay, for the one case the search
     * relay cannot serve: a reader's OWN profile, which lives wherever they put
     * it and may never have been indexed here. Everybody else in the corpus was
     * found through this relay in the first place.
     */
    suspend fun profiles(
        pubkeys: List<String>,
        hosts: List<String> = emptyList(),
    ): Map<String, Byline> {
        if (pubkeys.isEmpty()) return emptyMap()
        val chunks = pubkeys.chunked(100)
        val best = mutableMapOf<String, Byline>()
        val found =
            coroutineScope {
                (listOf(searchRelay) + hosts.take(3))
                    .distinct()
                    .map { host ->
                        // Same question, dressed per host: the search relay's
                        // auth gate CLOSES a tokenless REQ, while a reader's
                        // own relay may not implement `search` at all — so the
                        // token goes to the one host that demands it and to no
                        // other. [Relays.sameRelay], not `==`: a reader's list
                        // can name the search relay in another spelling.
                        val token = if (Relays.sameRelay(host, searchRelay)) Relays.INCLUDE_SPAM else null
                        val filters = chunks.map { ReadinessProbe.profileFilter(it, token) }
                        async { runCatching { relays.fetch(host, filters, idle = 20_000) }.getOrDefault(emptyList()) }
                    }.awaitAll()
                    .flatten()
            }
        found.forEach { event ->
            val p = Byline.from(event) ?: return@forEach
            val seen = best[p.pubkey]
            if (seen == null || seen.createdAt < p.createdAt) best[p.pubkey] = p
        }
        return best
    }
}
