package com.nosfabrica.observer

import com.nosfabrica.observer.nostr.Relays
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The REQ that was too big to answer.
 *
 * An edition once asked nine desks for the same 600 authors, which is about
 * 353 KB of filter in one frame. `search-staging` advertises a 262144 byte cap
 * and enforces it by dropping the frame in silence — no NOTICE, no CLOSED — so
 * the whole edition came back empty while every one of those queries answered
 * fine on its own. Nothing about the symptom pointed at size.
 *
 * That caller was the provisional lens and it has been removed, so these tests
 * are now the ONLY thing exercising the split. They are kept for that reason:
 * the relay's limit did not go away with the caller, and the next filter big
 * enough to hit it would fail exactly as silently.
 */
class RelaysTest {
    private fun authors(n: Int) = (0 until n).map { "%064x".format(it) }

    @Test
    fun `small filters travel together`() {
        val filters = List(9) { Filter(kinds = listOf(it), since = 1, limit = 50) }
        assertEquals(1, Relays.batches(filters).size)
    }

    @Test
    fun `a large author filter does not fit in one frame`() {
        // The exact shape that failed: nine desks, 600 authors each.
        val filters = List(9) { Filter(kinds = listOf(it), since = 1, limit = 50, authors = authors(600)) }
        val batches = Relays.batches(filters)
        assertTrue(batches.size > 1, "353 KB of filter cannot be one REQ")
        batches.forEach { batch ->
            assertTrue(batch.sumOf { it.toJson().length } <= Relays.MAX_REQ_BYTES, "a batch is over budget")
        }
        // Split, not dropped: every desk still gets asked.
        assertEquals(filters.size, batches.sumOf { it.size })
        assertEquals(filters, batches.flatten())
    }

    @Test
    fun `one filter over the budget is an error rather than a silent nothing`() {
        // 4000 authors is ~264 KB on its own. No split can help; the caller has
        // to chunk. Throwing says so instead of returning an empty edition.
        val filter = Filter(kinds = listOf(1), authors = authors(4_000))
        assertTrue(filter.toJson().length > Relays.MAX_REQ_BYTES)
        assertThrows(IllegalArgumentException::class.java) { Relays.batches(listOf(filter)) }
    }

    @Test
    fun `the budget leaves room for the frame around the filters`() {
        // The relay's cap is on the whole REQ, and the subscription id, brackets
        // and commas are not filter bytes.
        assertTrue(Relays.MAX_REQ_BYTES < 262_144)
    }

    @Test
    fun `sameRelay sees through the spellings a real relay list carries`() {
        // Found by audit 2026-08-30: `host == searchRelay` sent the tokenless
        // filter to a trailing-slash spelling of the search relay — a query the
        // auth gate can only refuse.
        // No case-insensitivity claim here: quartz REJECTS an uppercase scheme
        // in normalize(), and a host it rejects cannot be fetched either, so
        // there is no leg for the token to miss.
        assertTrue(Relays.sameRelay("wss://search.example.com", "wss://search.example.com/"))
        assertTrue(!Relays.sameRelay("wss://search.example.com", "wss://other.example.com"))
    }
}
