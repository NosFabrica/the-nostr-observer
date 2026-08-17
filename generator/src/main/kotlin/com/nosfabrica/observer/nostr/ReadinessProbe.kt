package com.nosfabrica.observer.nostr

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Asks the relay the four questions [Readiness] decides on.
 *
 * [Readiness] is pure and testable; this is the half that talks to the network,
 * kept separate for exactly that reason. Every fact it cannot establish is left
 * null rather than guessed, because null drives `checking` and a guess drives a
 * confident wrong answer.
 *
 * Measured against search-staging on 2026-08-17, for the observer this project
 * was prototyped through: 149,171 of the provider's 149,266 cards are here
 * (99.9%), and the reader's own posts count 27,058. That is a `ready` lens, and
 * it is the shape the probe was written against.
 */
class ReadinessProbe(
    private val relay: RelayClient,
    private val open: (String) -> RelayClient = { RelayClient(it) },
) {
    /**
     * A reader's kind 10002, split the way NIP-65 means it.
     *
     * An `r` tag with no marker is BOTH read and write. Only an explicit "read"
     * marker excludes it — treating unmarked entries as read-only would report
     * "no write relays" for the majority of real relay lists, which is the same
     * permanent-failure message as having published none at all.
     */
    internal fun writeRelays(event: NostrEvent?): Pair<List<String>, Int> {
        if (event == null) return emptyList<String>() to 0
        val rows = event.tags("r")
        val writes =
            rows
                .filter { it.getOrNull(1)?.lowercase() != "read" }
                .mapNotNull { it.firstOrNull()?.trim() }
                .filter { it.startsWith("wss://") || it.startsWith("ws://") }
        return writes to rows.size
    }

    /** The `30382:rank` entry, if it is public and carries a relay hint. */
    internal fun rankService(event: NostrEvent?): Pair<String?, String?> {
        val tag = event?.tags?.firstOrNull { it.firstOrNull() == "30382:rank" } ?: return null to null
        val service = tag.getOrNull(1)?.takeIf { it.length == 64 }
        val hint = tag.getOrNull(2)?.takeIf { it.startsWith("wss://") || it.startsWith("ws://") }
        // Both halves are required. A hintless entry resolves to nothing in the
        // store's provider map, so reporting the service alone would say a lens
        // exists that cannot rank.
        return if (service != null && hint != null) service to hint else null to null
    }

    suspend fun gather(
        observer: String,
        since: Long,
    ): Readiness.Facts =
        coroutineScope {
            val listEvent =
                async {
                    relay.req(filter(kinds = listOf(10002), authors = listOf(observer), limit = 1)).firstOrNull()
                }
            val scoreEvent =
                async {
                    relay.req(filter(kinds = listOf(10040), authors = listOf(observer), limit = 1)).firstOrNull()
                }
            // Link 4 runs regardless of links 1-3: it is one cheap pair of reads
            // and it is the only thing that can see an unprojected service.
            val authed = async { relay.req(rankedProbe(observer, since), timeoutMs = 25_000).size.toLong() }
            val anon = async { relay.req(rankedProbe(null, since), timeoutMs = 25_000).size.toLong() }

            val (writes, declared) = writeRelays(listEvent.await())
            val (service, hint) = rankService(scoreEvent.await())

            val scores =
                if (service == null) {
                    null
                } else {
                    val here = relay.count(filter(kinds = listOf(30382), authors = listOf(service)))
                    // "There" is the provider's own relay. Asking a second host is
                    // the whole point of the comparison, so a failure to reach it
                    // must leave a null denominator rather than borrow ours.
                    val there =
                        runCatching {
                            open(hint!!).use { it.count(filter(kinds = listOf(30382), authors = listOf(service))) }
                        }.getOrNull()
                    Readiness.Counts(here, there)
                }

            val posts =
                if (writes.isEmpty()) {
                    null
                } else {
                    val here = relay.count(filter(kinds = listOf(1), authors = listOf(observer)))
                    val there =
                        runCatching {
                            open(writes.first()).use { it.count(filter(kinds = listOf(1), authors = listOf(observer))) }
                        }.getOrNull()
                    if (here == null && there == null) null else Readiness.Counts(here, there)
                }

            Readiness.Facts(
                writeRelays = writes,
                relayListSeen = listEvent.await() != null,
                scoreListSeen = scoreEvent.await() != null,
                rankService = service,
                rankRelay = hint,
                scores = scores,
                probeAuthed = authed.await(),
                probeAnon = anon.await(),
                posts = posts,
            )
        }

    /**
     * The same question asked twice, once through the lens and once without.
     *
     * A small limit on purpose: this is a liveness check, not a read. What
     * matters is whether the ranked side comes back empty while the anonymous
     * side does not — the signature of cards that are stored but not projected.
     *
     * `since` IS REQUIRED, and leaving it off is not a tidier filter but a
     * broken probe. Measured against search-staging on 2026-08-17: the same
     * NIP-50 search returns 12 events immediately with a 24-hour `since` and
     * times out with none at all. Both sides then come back zero, which
     * [Readiness] correctly reads as a quiet window rather than a broken lens —
     * so link 4 passes every time and tests nothing. It shipped that way for
     * about ten minutes.
     */
    private fun rankedProbe(
        observer: String?,
        since: Long,
    ) = buildJsonObject {
        put("kinds", buildJsonArray { add(1) })
        put("since", since)
        put("search", if (observer == null) "sort:rank" else "observer:$observer sort:rank")
        put("limit", 12)
    }

    private fun filter(
        kinds: List<Int>,
        authors: List<String>,
        limit: Int? = null,
    ) = buildJsonObject {
        put("kinds", buildJsonArray { kinds.forEach { add(it) } })
        put("authors", buildJsonArray { authors.forEach { add(it) } })
        limit?.let { put("limit", it) }
    }
}
