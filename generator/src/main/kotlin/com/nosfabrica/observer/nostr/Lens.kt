package com.nosfabrica.observer.nostr

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip02FollowList.ContactListEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * How a reader's corpus gets chosen.
 *
 * Two shapes, and the difference is the whole product. [Trusted] hands the relay
 * an `observer:` token and lets a scoring service rank; [Provisional] hands it a
 * list of pubkeys and gets recency. The second is worse and honest about it —
 * it exists so that the ~95% of readers with no lens yet get a paper on their
 * first morning instead of an explanation.
 */
sealed interface Lens {
    val label: String

    data class Trusted(
        val observer: String,
    ) : Lens {
        override val label = "web of trust"
    }

    data class Provisional(
        val authors: List<String>,
        val direct: Int,
        val extended: Int,
        val truncated: Boolean,
    ) : Lens {
        // ASCII: this label is printed to the console as well as set in the
        // page, and the JVM's default console encoding turns an em dash into a
        // question mark, which reads like the string is broken.
        override val label = "provisional - $direct follows and $extended of their follows"
    }
}

/**
 * Builds a provisional lens out of the reader's own follow list.
 *
 * Follows, then follows-of-follows ordered by how many of the reader's own
 * follows vouch for them. That second hop is what stops the paper being a plain
 * timeline: a stranger seven of your friends all follow is more likely to be
 * worth a headline than one only you do. It is a crude stand-in for a trust
 * score and it should read as one — the edition says so on its face.
 *
 * Two costs worth naming rather than hiding. It is RECENCY, not rank, so a
 * prolific account can still dominate (the digest's per-author cap is doing
 * more work here than in the trusted path). And the author list is capped,
 * because a filter carrying five thousand pubkeys is a burden on somebody
 * else's relay whatever it returns.
 */
class Follows(
    private val relays: Relays,
    private val searchRelay: String,
    private val maxAuthors: Int = 600,
) {
    /**
     * Follow lists do not live on the search relay, and this is not a bug there.
     *
     * Measured 2026-08-17: `search-staging` holds ZERO kind-3 events, and
     * `/stats.json` confirms kind 3 is not among its mirrored kinds. Asking it
     * for a follow list returns nothing for everyone, so a provisional lens
     * built against it degrades to "just the reader" every single time — which
     * is what it did on the first live run, silently, and looked like a reader
     * with no friends.
     *
     * Kind 10002 IS mirrored, so the reader's own relays are discoverable and
     * their follow list is one hop away on relays they chose. That is the outbox
     * model working exactly as intended rather than a workaround.
     */
    suspend fun provisional(
        observer: String,
        writeRelays: List<String>,
    ): Lens.Provisional {
        // At most three: a reader with nine relays does not need nine sockets
        // opened on their behalf to answer one question.
        val hosts = writeRelays.take(3)
        val direct = followsFromAnyOf(hosts, observer)
        if (direct.isEmpty()) {
            return Lens.Provisional(listOf(observer), 0, 0, false)
        }

        // One hop out, and only from a sample: reading five hundred follow lists
        // to rank a paper nobody has paid for yet is not a reasonable thing to do
        // to a relay. The sample is the reader's own first follows, which is the
        // arbitrary-but-defensible order the relay already gave us.
        val vouches = mutableMapOf<String, Int>()
        val sample = direct.take(120)
        // Asked on the relays we already opened for the reader. People who follow
        // each other tend to share relays, so the hit rate is decent and the cost
        // is zero new connections; the ones we miss simply do not vote.
        lists(hosts, sample)
            .forEach { list ->
                list
                    .values("p")
                    .mapNotNull { it.firstOrNull() }
                    .filter { it.length == 64 && it != observer && it !in direct }
                    .distinct()
                    .forEach { vouches[it] = (vouches[it] ?: 0) + 1 }
            }

        // Two vouches minimum. One is noise — every follow list contains somebody
        // nobody else in the reader's world has heard of.
        val extended =
            vouches.entries
                .filter { it.value >= 2 }
                .sortedByDescending { it.value }
                .map { it.key }

        val authors = (listOf(observer) + direct + extended)
        val capped = authors.take(maxAuthors)
        return Lens.Provisional(
            authors = capped,
            direct = direct.size,
            extended = extended.size,
            truncated = authors.size > capped.size,
        )
    }

    /** First relay that has an answer wins; a reader's list is the same list everywhere. */
    private suspend fun followsFromAnyOf(
        hosts: List<String>,
        pubkey: String,
    ): List<String> {
        for (host in hosts + listOf(null)) {
            val events =
                runCatching {
                    relays.fetch(host ?: searchRelay, followFilter(listOf(pubkey)), idle = 12_000)
                }.getOrNull().orEmpty()
            val newest = events.maxByOrNull { it.createdAt } ?: continue
            val follows =
                newest
                    .values("p")
                    .mapNotNull { it.firstOrNull() }
                    .filter { it.length == 64 }
                    .distinct()
            if (follows.isNotEmpty()) return follows
        }
        return emptyList()
    }

    /** Every follow list we can get for these people, from the relays already in play. */
    private suspend fun lists(
        hosts: List<String>,
        pubkeys: List<String>,
    ): List<Event> {
        val filters = pubkeys.chunked(40).map { followFilter(it) }
        // All three hosts at once. In series this was three fifteen-second idle
        // windows on the slow path of a first-time reader's very first edition.
        val out =
            coroutineScope {
                hosts
                    .map { host -> async { runCatching { relays.fetch(host, filters, idle = 15_000) }.getOrDefault(emptyList()) } }
                    .awaitAll()
                    .flatten()
            }
        // Newest list per author: the same person answered by two relays is one
        // vote, not two, and the stale copy must not be the one that counts.
        return out.groupBy { it.pubKey }.values.mapNotNull { copies -> copies.maxByOrNull { it.createdAt } }
    }

    private fun followFilter(authors: List<String>) = Filter(kinds = listOf(ContactListEvent.KIND), authors = authors)
}
