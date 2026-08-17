package com.nosfabrica.observer

import com.nosfabrica.observer.corpus.ArtDesk
import com.nosfabrica.observer.corpus.Digest
import com.nosfabrica.observer.nostr.Bech32
import com.nosfabrica.observer.nostr.Readiness
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArtDeskTest {
    @Test
    fun `declared mime beats the url extension in both directions`() {
        assertFalse(ArtDesk.isImage("https://x/thumb.jpg", "video/mp4"), "a .jpg that declares video is video")
        assertTrue(ArtDesk.isImage("https://x/no-extension", "image/webp"), "no extension, declared image")
    }

    @Test
    fun `falls back to the extension only when nothing is declared`() {
        assertTrue(ArtDesk.isImage("https://x/a.png", null))
        assertFalse(ArtDesk.isImage("https://x/a.mov", null))
        assertFalse(ArtDesk.isImage("https://x/whatever", null), "unknown stays out rather than guessing")
    }

    @Test
    fun `parses the decimal dimensions clients actually send`() {
        assertEquals(1080 to 1920, ArtDesk.parseDim("1080.0x1920.0"))
        assertEquals(640 to 480, ArtDesk.parseDim("640x480"))
        assertEquals(null to null, ArtDesk.parseDim("wide"))
        assertEquals(null to null, ArtDesk.parseDim(null))
    }

    @Test
    fun `shortlist takes images and leaves video behind`() {
        val list = ArtDesk.shortlist(Fixtures.corpus())
        assertEquals(1, list.size, "one still among two videos: ${list.map { it.url }}")
        assertEquals(Fixtures.ART_URL, list.single().url)
        assertTrue(list.single().portrait)
        assertEquals("art-1", list.single().id)
    }

    @Test
    fun `caption drops the url the client pasted into the body`() {
        val art = ArtDesk.shortlist(Fixtures.corpus()).single()
        assertFalse(art.caption.contains("http"), "caption was '${art.caption}'")
        assertTrue(art.caption.startsWith("Zimbabwe Black"))
    }
}

class DigestTest {
    @Test
    fun `caps one author before they own a desk`() {
        val flood = (1..40).map { Fixtures.event("f$it", Fixtures.MALLORY, "filing number $it") }
        val rendered = Digest().render(Fixtures.corpus(flood), emptyList())
        assertEquals(20, rendered.kept, "notes cap is per author")
        assertEquals(20, rendered.dropped)
    }

    @Test
    fun `collapses the same post filed to several hosts`() {
        val text = "I always thought lions were the kings of the jungle and tigers were just oversized cats."
        val dupes = (1..4).map { Fixtures.event("d$it", Fixtures.ALICE, "$text https://host$it.example/x.jpg") }
        val rendered = Digest().render(Fixtures.corpus(dupes), emptyList())
        assertEquals(1, rendered.kept, "four uploads of one confession is one post")
    }

    @Test
    fun `renders bylines art ids and the corpus text`() {
        val rendered = Digest().render(Fixtures.corpus(), Fixtures.art())
        assertTrue(rendered.text.contains("Alice"))
        assertTrue(rendered.text.contains("ART: art-1"))
        assertTrue(rendered.text.contains("wow is it expensive"))
        assertTrue(rendered.approxTokens > 0)
    }
}

class Bech32Test {
    @Test
    fun `decodes real npubs to the pubkeys they belong to`() {
        assertEquals(
            "fb89e58f838b7d716a88300ea1f2539fff78766aa1121ec10968b6b10a498f28",
            Bech32.toHexPubkey("npub1lwy7trur3d7hz65gxq82rujnnllhsan25yfpasgfdzmtzzjf3u5q0v4zv0"),
        )
        assertEquals(
            "30e8cbf1427c137fa60674a639431c19a9d6f4c07fd2959df83158e674fccbaa",
            Bech32.toHexPubkey("npub1xr5vhu2z0sfhlfsxwjnrjscurx5adaxq0lfft80cx9vwva8uew4qk293g6"),
        )
    }

    @Test
    fun `passes hex straight through`() {
        assertEquals(Fixtures.OBSERVER, Bech32.toHexPubkey(Fixtures.OBSERVER.uppercase()))
    }

    @Test
    fun `rejects a typo rather than returning the wrong reader`() {
        assertThrows(IllegalArgumentException::class.java) {
            Bech32.toHexPubkey("npub1lwy7trur3d7hz65gxq82rujnnllhsan25yfpasgfdzmtzzjf3u5q0v4zv1")
        }
    }

    @Test
    fun `rejects an nsec so a secret is never used as a filter`() {
        assertThrows(
            IllegalArgumentException::class.java,
        ) { Bech32.toHexPubkey("nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5") }
    }
}

class ReadinessTest {
    private val service = "7d7ffd720b90".padEnd(64, '0')

    @Test
    fun `nothing asked yet is checking, not broken`() {
        assertEquals("checking", Readiness.assess(Readiness.Facts()).state)
    }

    @Test
    fun `no relay list is the permanent failure and everything below it waits`() {
        val v = Readiness.assess(Readiness.Facts(writeRelays = emptyList(), relayListSeen = false))
        assertEquals("no-relay-list", v.state)
        assertEquals(Readiness.Tone.BLOCKED, v.tone)
        // The whole point of the port: one broken link, not four.
        assertEquals(1, v.chain.count { it.status == Readiness.Status.BROKEN })
        assertEquals(3, v.chain.count { it.status == Readiness.Status.WAITING })
    }

    @Test
    fun `a list we cannot use says something different from having no list`() {
        val v = Readiness.assess(Readiness.Facts(writeRelays = emptyList(), relayListSeen = true))
        assertEquals("no-usable-relays", v.state)
    }

    @Test
    fun `a followers-only 10040 is a broken link, not a missing one`() {
        val v =
            Readiness.assess(
                Readiness.Facts(writeRelays = listOf("wss://a"), scoreListSeen = true, rankService = null),
            )
        assertEquals("no-rank-service", v.state)
    }

    @Test
    fun `zero cards here is blocked whatever the upstream says`() {
        val v =
            Readiness.assess(
                Readiness.Facts(
                    writeRelays = listOf("wss://a"),
                    scoreListSeen = true,
                    rankService = service,
                    scores = Readiness.Counts(0, 240_000),
                ),
            )
        assertEquals("no-scores-yet", v.state)
        assertEquals(0.0, v.percent)
        assertFalse(v.ranks)
    }

    @Test
    fun `cards present but unprojected is its own state`() {
        val v =
            Readiness.assess(
                Readiness.Facts(
                    writeRelays = listOf("wss://a"),
                    scoreListSeen = true,
                    rankService = service,
                    scores = Readiness.Counts(240_000, 240_000),
                    probeAnon = 12,
                    probeAuthed = 0,
                ),
            )
        assertEquals("projection-pending", v.state, "this is what the count above cannot see")
    }

    @Test
    fun `an empty corpus is not read as a broken lens`() {
        val v =
            Readiness.assess(
                Readiness.Facts(
                    writeRelays = listOf("wss://a"),
                    scoreListSeen = true,
                    rankService = service,
                    scores = Readiness.Counts(240_000, 240_000),
                    probeAnon = 0,
                    probeAuthed = 0,
                ),
            )
        assertEquals("ready", v.state, "both sockets empty means a quiet window, not a failure")
    }

    @Test
    fun `the last few per cent of an import count as done`() {
        fun at(here: Long) =
            Readiness
                .assess(
                    Readiness.Facts(
                        writeRelays = listOf("wss://a"),
                        scoreListSeen = true,
                        rankService = service,
                        scores = Readiness.Counts(here, 1000),
                        probeAnon = 5,
                        probeAuthed = 5,
                    ),
                ).state
        assertEquals("importing", at(700))
        // No panel ever prints 90% or more.
        assertEquals("ready", at(900))
        assertEquals("ready", at(1000))
    }

    @Test
    fun `own posts lagging is an aside and still ranks`() {
        val v =
            Readiness.assess(
                Readiness.Facts(
                    writeRelays = listOf("wss://a"),
                    scoreListSeen = true,
                    rankService = service,
                    scores = Readiness.Counts(1000, 1000),
                    probeAnon = 5,
                    probeAuthed = 5,
                    posts = Readiness.Counts(3, 10),
                ),
            )
        assertEquals("posts-behind", v.state)
        assertTrue(v.ranks, "ranking is complete without your own posts")
    }

    @Test
    fun `fraction refuses to guess without a denominator`() {
        assertNull(Readiness.fraction(5, null))
        assertNull(Readiness.fraction(5, 0))
        assertEquals(1.0, Readiness.fraction(120, 100), "we can hold more than an upstream serves")
    }
}
