package com.nosfabrica.observer.nostr

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.count
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.fetchAll
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.publishAndCollectResults
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.Closeable
import java.time.Duration

/**
 * Relay reads, through quartz.
 *
 * This file used to be three hundred lines of hand-rolled NIP-01: a websocket,
 * a subscription registry, EVENT/EOSE/CLOSED/COUNT dispatch, and an AUTH frame
 * I had to learn about by watching a COUNT come back empty. All of it already
 * existed in quartz — `NostrClient` plus the `fetchAll` and `count`
 * accessories — and quartz is a dependency of the relay this project reads
 * from, so the two were always going to have to agree about event shapes.
 *
 * What is left is the two questions this project actually asks and the timeout
 * policy it wants, which is the only part that was ever ours.
 *
 * The timeout is an IDLE window, not a deadline: quartz drains until a relay
 * has said nothing for [idleMs], so a slow relay finishes and a silent one is
 * given up on. Reading it as a deadline is a mistake this codebase's sibling
 * has already paid for once.
 */
class Relays(
    private val idleMs: Long = 15_000,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob())
    private val okhttp = OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(10)).build()

    /**
     * Exposed because a NIP-46 signer needs to talk on the same sockets.
     *
     * A second client would mean a second connection to relays this process is
     * already connected to, and quartz's remote signer is built to be handed
     * one rather than to own one.
     */
    val client = NostrClient(BasicOkHttpWebSocket.Builder { okhttp }, scope)

    /**
     * Everything matching, from one relay. Order is the relay's.
     *
     * Sent as however many REQs it takes to stay under [MAX_REQ_BYTES], run at
     * once. See that constant for why splitting is not an optimisation.
     */
    suspend fun fetch(
        url: String,
        filters: List<Filter>,
        idle: Long = idleMs,
    ): List<Event> {
        val relay = RelayUrlNormalizer.normalize(url)
        val batches = batches(filters)
        return deadline(idle) {
            if (batches.size == 1) {
                client.fetchAll(relay, batches.first(), idle)
            } else {
                coroutineScope {
                    batches.map { async { client.fetchAll(relay, it, idle) } }.awaitAll().flatten()
                }
            }
        } ?: emptyList()
    }

    suspend fun fetch(
        url: String,
        filter: Filter,
        idle: Long = idleMs,
    ): List<Event> = fetch(url, listOf(filter), idle)

    /**
     * Send one event to several relays and wait for each to answer.
     *
     * Fire-and-forget is the wrong shape for the only thing this project
     * publishes. A manifest that reached none of a reader's relays, but that we
     * hold in our own database, resolves perfectly for us and for nobody else:
     * the reader would see their paper and no one else would, which is the
     * failure this whole design exists to avoid. So the OK frame is waited for
     * and the relay's own sentence is carried back.
     */
    suspend fun publish(
        event: Event,
        urls: List<String>,
        idle: Long = idleMs,
    ): List<Triple<String, Boolean, String>> {
        val targets = urls.map { RelayUrlNormalizer.normalize(it) }.toSet()
        val results = client.publishAndCollectResults(event, targets, idle)
        return targets.map { relay ->
            val result = results[relay]
            Triple(
                relay.url,
                result?.accepted ?: false,
                result?.message?.ifBlank { if (result.accepted) "accepted" else "rejected without a reason" }
                    ?: "no answer",
            )
        }
    }

    /**
     * How many match, or null when the relay will not say.
     *
     * NIP-45 is optional. Null is a supported answer that callers must draw
     * nothing from rather than estimate — a percentage computed from a guess
     * puts a number on screen no relay ever stated.
     */
    suspend fun count(
        url: String,
        filter: Filter,
        idle: Long = idleMs,
    ): Long? =
        deadline(idle) {
            runCatching { client.count(RelayUrlNormalizer.normalize(url), filter, idle)?.count?.toLong() }.getOrNull()
        }

    companion object {
        /**
         * How many bytes of filter one REQ may carry.
         *
         * `search-staging` advertises `max_message_length: 262144` in its NIP-11
         * document, and it enforces it the way relays generally do: the oversized
         * frame is dropped, with no NOTICE and no CLOSED. The subscription then
         * sits open saying nothing until the idle timer expires and quartz
         * reports what it heard, which is an empty list.
         *
         * That is why this is a correctness fix and not a politeness one. It
         * was found the hard way: an edition built from a 600-pubkey author
         * list across nine desks came to about 353 KB and returned zero events,
         * while every one of those queries answered normally on its own
         * (measured 2026-08-17: six desks at 235 KB answered, nine at 353 KB
         * returned nothing).
         *
         * NOTE, 2026-08-18: that caller is gone. The provisional lens it
         * belonged to was removed, and nothing on a live path now builds a
         * frame anywhere near the cap — the largest is a profile fetch at
         * roughly 27 KB. This is kept as a GUARD rather than deleted with it,
         * because the limit is real, it is the relay's and not ours, and
         * exceeding it fails silently. It is no longer exercised by use, so do
         * not assume it is proven by anything except its own test.
         *
         * The budget is under the advertised cap because the cap is on the whole
         * frame: subscription id, brackets and commas are ours to leave room for.
         * It is a constant rather than a NIP-11 read because a relay that answers
         * NIP-11 with anything unexpected must not be able to talk us INTO a
         * larger frame.
         */
        const val MAX_REQ_BYTES = 240_000

        /**
         * The token that opens the search relay to an UNRANKED query.
         *
         * Measured 2026-08-30: `search-staging` now CLOSES every REQ and COUNT
         * whose `search` field carries neither an `observer:<hex>` token nor
         * `include:spam` — "auth-required: this relay answers through a web of
         * trust and has no house observer to lend you." So every plain lookup
         * — a kind 0, a 10002, a 10063 — must say `include:spam` to be
         * answered at all. The ranked desks already name their observer and
         * need nothing.
         *
         * Only on the search relay. The sibling stores (scores.brainstorm.world,
         * nip85.nosfabrica.com, measured the same day) answer tokenless
         * queries, and a reader's own relays may refuse a `search` field they
         * do not implement — so callers attach this to the search-relay leg of
         * a fan-out and never to the others.
         */
        const val INCLUDE_SPAM = "include:spam"

        /**
         * Whether two differently written URLs name the same relay.
         *
         * The auth-gate token must reach EVERY leg of a fan-out that lands on
         * the search relay, and raw string equality misses the variants a real
         * relay list carries — a trailing slash is the common one (quartz's
         * own normalizer adds it: `wss://x` and `wss://x/` are one relay).
         * Found by audit 2026-08-30: a reader whose 10002 writes the search
         * relay with a slash got that leg sent tokenless, a query that can
         * only be refused.
         */
        fun sameRelay(
            a: String,
            b: String,
        ): Boolean = runCatching { RelayUrlNormalizer.normalize(a) == RelayUrlNormalizer.normalize(b) }.getOrDefault(a == b)

        /**
         * Greedy split, in order. One filter per REQ at worst.
         *
         * A single filter over the budget throws rather than being sent to be
         * silently dropped. No caller in this codebase can currently reach that
         * size, so it firing means somebody has built something new — and the
         * loud version of that is a stack trace at the boundary instead of a
         * blank page an hour later.
         */
        internal fun batches(
            filters: List<Filter>,
            budget: Int = MAX_REQ_BYTES,
        ): List<List<Filter>> {
            val out = mutableListOf<MutableList<Filter>>()
            var used = 0
            filters.forEach { filter ->
                val size = filter.toJson().length
                require(size <= budget) {
                    "One filter is $size bytes, over the $budget-byte REQ budget; chunk its authors or ids."
                }
                if (out.isEmpty() || used + size > budget) {
                    out += mutableListOf(filter)
                    used = size
                } else {
                    out.last() += filter
                    used += size
                }
            }
            return out
        }
    }

    /**
     * A wall clock over the idle clock, as a belt-and-braces bound.
     *
     * Honest about what this is: a GUARD, not a fix for a diagnosed bug. On
     * 2026-08-18 `--check` against `search-staging` began intermittently
     * blocking until killed, in runs where the relay had apparently stopped
     * answering COUNTs. It reproduced on the previous commit as well, so it is
     * not something this code introduced, and it has NOT been reproduced
     * locally — a socket that connects and then says nothing is already handled
     * by quartz's idle clock, so the real shape is something else.
     *
     * What is defensible without knowing the shape: no relay read from a
     * request handler should be able to block forever. `/api/readiness` is
     * fetched on every page load, and an unbounded wait there is a held thread
     * and a page that looks broken. Null and empty are already supported
     * answers everywhere this is used — null is what drives `checking` rather
     * than a guess — so giving up degrades honestly instead of inventing a
     * number.
     *
     * If the underlying hang is ever pinned down, this stays anyway; it is the
     * bound, not the diagnosis.
     */
    private suspend fun <T> deadline(
        idle: Long,
        block: suspend CoroutineScope.() -> T,
    ): T? = withTimeoutOrNull(idle * 2 + 5_000, block)

    override fun close() {
        scope.cancel()
        okhttp.dispatcher.executorService.shutdown()
        okhttp.connectionPool.evictAll()
    }
}
