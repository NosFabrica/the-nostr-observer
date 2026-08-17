package com.nosfabrica.observer

import com.nosfabrica.observer.nostr.LensRequest
import com.nosfabrica.observer.nostr.ReadinessProbe
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
    private val probe = ReadinessProbe(relay = FakeRelay)
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
        val (writes, declared) = probe.writeRelays(event)
        assertEquals(listOf("wss://both.example.com", "wss://writes.example.com"), writes)
        assertEquals(3, declared, "the denominator counts every declared row")
    }

    @Test
    fun `no relay list at all is empty rather than an exception`() {
        assertEquals(emptyList<String>() to 0, probe.writeRelays(null))
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
        assertEquals(listOf("wss://ok.example.com"), probe.writeRelays(event).first)
    }

    @Test
    fun `a rank entry needs both a service and a relay hint`() {
        fun tenForty(vararg tags: List<String>) = Fixtures.event("s", Fixtures.ALICE, "", kind = 10040, tags = tags.toList())

        assertEquals(
            service to "wss://scores.example.com",
            probe.rankService(tenForty(listOf("30382:rank", service, "wss://scores.example.com"))),
        )

        // Each of these resolves to nothing in the store's provider map while
        // looking, from the outside, exactly like a configured lens.
        assertNull(probe.rankService(tenForty(listOf("30382:rank", service))).first, "hintless")
        assertNull(probe.rankService(tenForty(listOf("30382:followers", service, "wss://x"))).first, "followers only")
        assertNull(probe.rankService(tenForty(listOf("30382:rank", "tooshort", "wss://x"))).first, "bad key")
        assertNull(probe.rankService(null).first, "no 10040 at all")
    }
}

class LensRequestTest {
    private val service = "a".repeat(64)

    @Test
    fun `the template names every dimension publicly and with a hint`() {
        val json = LensRequest.template(service, "wss://scores.example.com", 1_786_900_000)
        assertTrue(json.contains("\"kind\":10040"))
        LensRequest.DIMENSIONS.forEach { assertTrue(json.contains(it), "$it missing") }
        // Two dimensions plus the client tag, each carrying service and hint.
        assertEquals(2, Regex(Regex.escape("wss://scores.example.com")).findAll(json).count())
        assertTrue(json.contains("the-nostr-observer"))
    }

    @Test
    fun `refuses to build something the store cannot resolve`() {
        assertThrows(IllegalArgumentException::class.java) {
            LensRequest.template("short", "wss://x.example.com", 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LensRequest.template(service, "https://x.example.com", 1)
        }
    }

    @Test
    fun `minting says a person is involved rather than faking a queue`() =
        kotlinx.coroutines.runBlocking {
            assertNull(LensRequest.Manual.mint(service), "no API exists yet, so no assignment is invented")
            assertTrue(LensRequest.Manual.EXPLANATION.contains("operator step"))
        }
}

/** The probe's parsing does not touch the network; this exists only to construct it. */
private val FakeRelay =
    com.nosfabrica.observer.nostr
        .RelayClient("ws://127.0.0.1:1")
