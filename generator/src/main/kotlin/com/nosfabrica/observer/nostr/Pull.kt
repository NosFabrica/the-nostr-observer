package com.nosfabrica.observer.nostr

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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

/** Everything one edition is written from. */
data class Corpus(
    val observer: String,
    val since: Long,
    val until: Long,
    /** Ranked through the observer's lens — the paper. */
    val ranked: Map<Desk, List<NostrEvent>>,
    /** The same query with the observer token removed — the Instrument panel. */
    val control: List<NostrEvent>,
    val profiles: Map<String, Profile>,
) {
    val notes: List<NostrEvent> get() = ranked[Desk.NOTES].orEmpty()

    fun all(): List<NostrEvent> = ranked.values.flatten()

    fun byline(pubkey: String): String = profiles[pubkey]?.byline() ?: pubkey.take(8)
}

class Pull(
    private val relay: RelayClient,
) {
    /**
     * A bare `observer:<pk> sort:rank` with no search term is a valid NIP-50
     * query and returns a ranked recency feed. That is the whole product, and it
     * is worth stating because it looks like a mistake: every other client sends
     * a term.
     */
    private fun search(observer: String?): String = if (observer == null) "sort:rank" else "observer:$observer sort:rank"

    private fun filter(
        kind: Int,
        since: Long,
        limit: Int,
        observer: String?,
    ): JsonObject =
        buildJsonObject {
            put("kinds", buildJsonArray { add(kind) })
            put("since", since)
            put("search", search(observer))
            put("limit", limit)
        }

    suspend fun corpus(
        observer: String,
        since: Long,
        until: Long,
    ): Corpus {
        val desks = Desk.entries
        // One socket, every desk at once, plus the control run. The control is
        // reader-INDEPENDENT — the same query minus one token — but it is a relay
        // read rather than a model call, so running it per edition costs nothing
        // worth optimising and keeps the panel honest for this exact window.
        val filters =
            desks.map { filter(it.kind, since, it.limit, observer) } +
                listOf(filter(Desk.NOTES.kind, since, Desk.NOTES.limit, null))

        val results = relay.reqAll(filters)
        val ranked = desks.zip(results).toMap()
        val control = results.last()

        // Every author we are about to print, plus everyone the control run
        // names — the Instrument panel prints the spammer's own text, and a hex
        // string there would hide what makes the comparison land.
        val keys = (ranked.values.flatten() + control).map { it.pubkey }.distinct()
        return Corpus(observer, since, until, ranked, control, profiles(keys))
    }

    /** kind 0 for everyone we will name. Newest wins; batched because 244 authors is normal. */
    suspend fun profiles(pubkeys: List<String>): Map<String, Profile> {
        if (pubkeys.isEmpty()) return emptyMap()
        val filters =
            pubkeys.chunked(100).map { batch ->
                buildJsonObject {
                    put("kinds", buildJsonArray { add(0) })
                    put("authors", buildJsonArray { batch.forEach { add(it) } })
                }
            }
        val best = mutableMapOf<String, Profile>()
        relay.reqAll(filters).flatten().forEach { event ->
            val p = Profile.from(event) ?: return@forEach
            val seen = best[p.pubkey]
            if (seen == null || seen.createdAt < p.createdAt) best[p.pubkey] = p
        }
        return best
    }
}
