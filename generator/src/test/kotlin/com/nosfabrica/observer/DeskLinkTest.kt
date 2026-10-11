package com.nosfabrica.observer

import com.nosfabrica.observer.nostr.DeskLink
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class DeskLinkTest {
    /** One desk's link, the event we read on it, and what its writer form must look like. */
    data class Case(
        val link: DeskLink,
        val read: Event,
        val d: String,
        val writerUrl: String,
    ) {
        override fun toString() = link.desk.name
    }

    companion object {
        @JvmStatic
        fun cases() =
            listOf(
                Case(
                    DeskLink.LIVE,
                    Fixtures.liveStream(),
                    Fixtures.STREAM_D,
                    "https://zap.stream/stream/${Fixtures.STREAM_ID}",
                ),
                Case(
                    DeskLink.CLASSIFIEDS,
                    Fixtures.classified(),
                    Fixtures.LISTING_D,
                    "https://shopstr.store/listing/${Fixtures.LISTING_ID}",
                ),
                Case(
                    DeskLink.CALENDAR,
                    Fixtures.calendarEntry(),
                    Fixtures.CALENDAR_D,
                    "https://njump.me/${Fixtures.CALENDAR_ID}",
                ),
            )
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `writer url is event id hex`(case: Case) {
        assertEquals(case.writerUrl, case.link.writerUrl(case.read.id))
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `canonical url round-trips through naddr`(case: Case) {
        val url = case.link.canonicalUrl(case.read)
        assertTrue(url.contains("naddr1"))
        assertEquals(case.read.id, case.link.target(url, listOf(case.read)))
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `writer form resolves only for an event we read`(case: Case) {
        val writer = case.link.writerUrl(case.read.id)
        assertEquals(case.read.id, case.link.target(writer, listOf(case.read)))
        assertNull(case.link.target(writer, emptyList()))
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `a url copied from a post body is not verified`(case: Case) {
        val invented =
            case.link.canonicalUrl(
                Fixtures.event(
                    "f".repeat(64),
                    "ff66".repeat(16),
                    "",
                    kind = case.read.kind,
                    tags = listOf(listOf("d", "somebody-elses")),
                ),
            )
        assertNull(case.link.target(invented, listOf(case.read)))
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `naddr encoding uses nip-19 field order`(case: Case) {
        val parsed = NAddress.parse(NAddress.create(case.read.kind, Fixtures.ALICE, case.d, emptyList()))!!
        assertEquals(case.d, parsed.dTag)
        assertEquals(Fixtures.ALICE, parsed.author)
        assertEquals(case.read.kind, parsed.kind)
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `only events with a d tag are listed`(case: Case) {
        val bare =
            Fixtures.event(
                "aa55".repeat(16),
                Fixtures.ALICE,
                "",
                kind = case.read.kind,
                tags =
                    case.read.tags
                        .map { it.toList() }
                        .filter { it.first() != "d" },
            )
        assertTrue(case.link.listed(Fixtures.corpus(listOf(bare), case.link.desk)).isEmpty())
        assertEquals(listOf(case.read), case.link.listed(Fixtures.corpus(listOf(case.read), case.link.desk)))
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `another desk's link does not verify this desk's event`(case: Case) {
        for (other in cases().map { it.link }.filter { it !== case.link }) {
            assertNull(other.target(case.link.canonicalUrl(case.read), listOf(case.read)))
        }
    }

    @Test
    fun `all-day kind is listed with its own kind in the naddr`() {
        val allDay =
            Fixtures.event(
                "bb66".repeat(16),
                Fixtures.ALICE,
                "",
                kind = 31922,
                tags = listOf(listOf("d", "nostrasia"), listOf("title", "Nostrasia"), listOf("start", "2026-11-01")),
            )
        val url = DeskLink.CALENDAR.canonicalUrl(allDay)
        assertEquals(31922, NAddress.parse(url.substringAfterLast('/'))!!.kind)
        assertEquals(allDay.id.lowercase(), DeskLink.CALENDAR.target(url, listOf(allDay)))
    }
}
