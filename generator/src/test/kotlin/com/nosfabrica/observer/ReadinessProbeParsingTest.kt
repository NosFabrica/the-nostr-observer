package com.nosfabrica.observer

import com.nosfabrica.observer.nostr.ReadinessProbe
import com.nosfabrica.observer.nostr.Relays
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The two places a lens is read and written.
 *
 * Both are parsing problems dressed as network problems, and both have a failure
 * mode where the wrong answer looks exactly like success from the outside — a
 * relay list that reports no write relays, or a 10040 that names a service which
 * cannot rank. Those are what these hold.
 */
class ReadinessProbeParsingTest {
    private val probe = ReadinessProbe(Relays(), "wss://example.invalid")
    private val service = "7d7ffd720b907fe597a7f454afe02f2dc1eca440baa029e9117b1c3209839377"

    @Test
    fun `an unmarked relay is a write relay`() {
        // NIP-65: no marker means BOTH. Reading unmarked entries as read-only
        // would report "no write relays" for most real lists in existence, which
        // is the same permanent-failure message as having published none.
        val event =
            Fixtures.event(
                "r1",
                Fixtures.ALICE,
                "",
                kind = 10002,
                tags =
                    listOf(
                        listOf("r", "wss://both.example.com"),
                        listOf("r", "wss://writes.example.com", "write"),
                        listOf("r", "wss://reads.example.com", "read"),
                    ),
            )
        // quartz's AdvertisedRelayListEvent.writeRelays() owns this rule now.
        assertEquals(listOf("wss://both.example.com", "wss://writes.example.com"), probe.writeRelays(event))
    }

    @Test
    fun `no relay list at all is empty rather than an exception`() {
        assertEquals(emptyList<String>(), probe.writeRelays(null))
    }

    @Test
    fun `non-websocket entries are not relays`() {
        val event =
            Fixtures.event(
                "r2",
                Fixtures.ALICE,
                "",
                kind = 10002,
                tags = listOf(listOf("r", "https://example.com"), listOf("r", "wss://ok.example.com")),
            )
        assertEquals(listOf("wss://ok.example.com"), probe.writeRelays(event))
    }

    @Test
    fun `the ranked probe asks the same question the edition will`() {
        // This one carried no `since` at first, on the reasoning that a probe is
        // a liveness check rather than a read. Wrong twice: an unbounded NIP-50
        // is the MOST expensive query this store answers and it timed out, so
        // both sides came back zero, so the probe passed by testing nothing.
        val probe = probe.rankedProbe(Fixtures.ALICE, 1_786_900_000)
        assertEquals(1_786_900_000L, probe.since?.toLong())
        assertEquals("observer:${Fixtures.ALICE} sort:rank", probe.search)

        // The comparison is only worth anything if the two sides differ by the
        // lens and nothing else. `include:spam` is the relay's auth-gate token,
        // not a filter: without it a bare `sort:rank` is CLOSED outright, and
        // with it the query is still the anonymous ranking (measured
        // 2026-08-30, see Relays.INCLUDE_SPAM).
        val anon = this.probe.rankedProbe(null, 1_786_900_000)
        assertEquals("include:spam sort:rank", anon.search)
        assertEquals(probe.copy(search = anon.search).toJson(), anon.toJson())
    }

    @Test
    fun `the auth-gate token goes to the search relay and to no other host`() {
        // Both directions matter. Tokenless to the search relay is a refused
        // read that looks like an empty one; the token on a reader's own relay
        // is a `search` field a plain NIP-01 store may reject outright. And
        // the match is by relay, not by spelling — the audit found the
        // trailing-slash form going tokenless.
        val filter = Filter(kinds = listOf(1), authors = listOf(Fixtures.ALICE))
        assertEquals(Relays.INCLUDE_SPAM, probe.dressed("wss://example.invalid", filter).search)
        assertEquals(Relays.INCLUDE_SPAM, probe.dressed("wss://example.invalid/", filter).search)
        assertNull(probe.dressed("wss://their-relay.example.com", filter).search)
    }

    @Test
    fun `a rank entry needs both a service and a relay hint`() {
        // The rejections below are quartz's ServiceProviderTag.parse refusing to
        // build a tag that is missing a field, not a local takeIf somebody could
        // later tidy away. That is the point of using it.
        fun tenForty(vararg tags: List<String>) = Fixtures.event("s", Fixtures.ALICE, "", kind = 10040, tags = tags.toList())

        assertEquals(
            service to "wss://scores.example.com/",
            probe.rankProvider(tenForty(listOf("30382:rank", service, "wss://scores.example.com"))),
        )

        // Each of these resolves to nothing in the store's provider map while
        // looking, from the outside, exactly like a configured lens.
        assertNull(probe.rankProvider(tenForty(listOf("30382:rank", service))), "hintless")
        assertNull(probe.rankProvider(tenForty(listOf("30382:followers", service, "wss://x.example.com"))), "followers only")
        assertNull(probe.rankProvider(null), "no 10040 at all")
    }
}
