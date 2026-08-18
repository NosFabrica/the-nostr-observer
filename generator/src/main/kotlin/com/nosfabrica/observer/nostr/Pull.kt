package com.nosfabrica.observer.nostr

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * The nine kinds a front page is made of, and why each earns a column.
 *
 * The list is the one the prototype edition was built from. It is not "every
 * kind the relay holds" — it is the kinds that turned out to carry a story.
 */
enum class Desk(
    val kind: Int,
    val label: String,
    val limit: Int,
) {
    NOTES(1, "notes", 400),
    PICTURES(20, "picture posts", 60),
    FILES(1063, "file metadata", 50),
    HIGHLIGHTS(9802, "highlights", 50),
    ARTICLES(30023, "long-form", 100),
    CLASSIFIEDS(30402, "classifieds", 30),
    WIKI(30818, "wiki entries", 30),
    CALENDAR(31923, "calendar events", 100),
    APPS(32267, "app releases", 30),
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
    val lens: Lens,
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
) {
    /**
     * A bare `observer:<pk> sort:rank` with no search term is a valid NIP-50
     * query and returns a ranked recency feed. That is the whole product, and
     * it is worth stating because it looks like a mistake: every other client
     * sends a term.
     */
    private fun filter(
        kind: Int,
        since: Long,
        limit: Int,
        lens: Lens?,
    ): Filter =
        when (lens) {
            is Lens.Trusted -> {
                Filter(kinds = listOf(kind), since = since, limit = limit, search = "observer:${lens.observer} sort:rank")
            }

            // No `search` at all on the provisional path. `sort:rank` without a
            // resolvable observer is the failure this project exists to avoid:
            // it degrades silently to the anonymous ranking, which on a measured
            // window was 209 of 400 posts from one spam account. An authors
            // filter asks a different question and gets an answer.
            is Lens.Provisional -> {
                Filter(kinds = listOf(kind), since = since, limit = limit, authors = lens.authors)
            }

            null -> {
                Filter(kinds = listOf(kind), since = since, limit = limit, search = "sort:rank")
            }
        }

    suspend fun corpus(
        lens: Lens,
        observer: String,
        since: Long,
        until: Long,
    ): Corpus {
        val desks = Desk.entries

        // Every desk in one call. quartz returns them merged rather than one
        // list per filter, so the desks are recovered by kind -- which is why
        // the control run is NOT in this batch: it is kind 1 too, and merged in
        // here its anonymous results would land in the ranked notes. On a
        // measured window that would have been 209 spam posts filed as news.
        // The control run goes out at the same time, on its own subscription:
        // it is a separate query for a separate reason, not a slower one. Run in
        // series it added a whole idle window to every edition.
        val (all, control) =
            coroutineScope {
                val desksAsked = async { relays.fetch(searchRelay, desks.map { filter(it.kind, since, it.limit, lens) }, idle = 25_000) }
                val controlAsked = async { controlRun(since) }
                desksAsked.await() to controlAsked.await()
            }
        val byKind = all.groupBy { it.kind }
        val ranked = desks.associateWith { desk -> byKind[desk.kind].orEmpty().take(desk.limit) }

        // Every author we are about to print, plus everyone the control run
        // names -- the Instrument panel prints the spammer's own text, and a hex
        // string there would hide what makes the comparison land.
        val keys = (all + control).map { it.pubKey }.distinct()
        return Corpus(lens, observer, since, until, ranked, control, profiles(keys))
    }

    /**
     * Run separately so its results cannot be confused with the ranked ones.
     *
     * Both queries are kind 1 over the same window, so a single fetch returns
     * them interleaved with no way to tell which side an event came from — and
     * the Instrument panel's whole claim is about the difference between them.
     */
    private suspend fun controlRun(since: Long): List<Event> =
        relays.fetch(searchRelay, filter(Desk.NOTES.kind, since, Desk.NOTES.limit, null), idle = 25_000)

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
