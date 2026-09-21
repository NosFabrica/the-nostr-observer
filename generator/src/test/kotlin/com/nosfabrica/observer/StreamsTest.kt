package com.nosfabrica.observer

import com.nosfabrica.observer.nostr.Desk
import com.nosfabrica.observer.nostr.Streams
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StreamsTest {
    private val stream = Fixtures.liveStream()
    private val corpus = Fixtures.corpus(listOf(stream), Desk.LIVE)

    @Test
    fun `writer url is event id hex`() {
        assertEquals(
            "https://zap.stream/stream/${Fixtures.STREAM_ID}",
            Streams.writerUrl(Fixtures.STREAM_ID),
        )
    }

    @Test
    fun `canonical url round-trips through naddr`() {
        val url = Streams.canonicalUrl(stream)
        assertEquals(
            Fixtures.STREAM_ID,
            Streams.streamLinkTarget(url, listOf(stream)),
        )
    }

    @Test
    fun `writer form resolves only for a stream we read`() {
        val writer = Streams.writerUrl(Fixtures.STREAM_ID)
        assertEquals(Fixtures.STREAM_ID, Streams.streamLinkTarget(writer, listOf(stream)))
        assertNull(Streams.streamLinkTarget(writer, emptyList()))
    }

    @Test
    fun `a zap stream url copied from a post body is not verified`() {
        val invented =
            Streams.canonicalUrl(
                Fixtures.event(
                    "f".repeat(64),
                    "dd44".repeat(16),
                    "",
                    kind = 30311,
                    tags = listOf(listOf("d", "fake-stream")),
                ),
            )
        assertNull(Streams.streamLinkTarget(invented, listOf(stream)))
    }

    @Test
    fun `naddr encoding uses nip-19 field order`() {
        val naddr = NAddress.create(30311, Fixtures.ALICE, Fixtures.STREAM_D, emptyList())
        val parsed = NAddress.parse(naddr)!!
        assertEquals(Fixtures.STREAM_D, parsed.dTag)
        assertEquals(Fixtures.ALICE, parsed.author)
        assertEquals(30311, parsed.kind)
    }

    @Test
    fun `live streams need a d tag`() {
        val bare =
            Fixtures.event(
                "aa55".repeat(16),
                Fixtures.ALICE,
                "",
                kind = 30311,
                tags = listOf(listOf("status", "live")),
            )
        assertTrue(Streams.live(Fixtures.corpus(listOf(bare), Desk.LIVE)).isEmpty())
    }
}
