package com.nosfabrica.observer

import com.nosfabrica.observer.nostr.Calendar
import com.nosfabrica.observer.nostr.Desk
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CalendarTest {
    private val listing = Fixtures.calendarEntry()
    private val corpus = Fixtures.corpus(listOf(listing), Desk.CALENDAR)

    @Test
    fun `writer url is event id hex`() {
        assertEquals(
            "https://njump.me/${Fixtures.CALENDAR_ID}",
            Calendar.writerUrl(Fixtures.CALENDAR_ID),
        )
    }

    @Test
    fun `canonical url round-trips through naddr`() {
        val url = Calendar.canonicalUrl(listing)
        assertEquals(
            Fixtures.CALENDAR_ID,
            Calendar.calendarLinkTarget(url, listOf(listing)),
        )
    }

    @Test
    fun `writer form resolves only for a listing we read`() {
        val writer = Calendar.writerUrl(Fixtures.CALENDAR_ID)
        assertEquals(Fixtures.CALENDAR_ID, Calendar.calendarLinkTarget(writer, listOf(listing)))
        assertNull(Calendar.calendarLinkTarget(writer, emptyList()))
    }

    @Test
    fun `an njump url copied from a post body is not verified`() {
        val invented =
            Calendar.canonicalUrl(
                Fixtures.event(
                    "f".repeat(64),
                    "ff66".repeat(16),
                    "",
                    kind = 31923,
                    tags = listOf(listOf("d", "fake-meetup")),
                ),
            )
        assertNull(Calendar.calendarLinkTarget(invented, listOf(listing)))
    }

    @Test
    fun `naddr encoding uses nip-19 field order`() {
        val naddr = NAddress.create(31923, Fixtures.ALICE, Fixtures.CALENDAR_D, emptyList())
        val parsed = NAddress.parse(naddr)!!
        assertEquals(Fixtures.CALENDAR_D, parsed.dTag)
        assertEquals(Fixtures.ALICE, parsed.author)
        assertEquals(31923, parsed.kind)
    }

    @Test
    fun `listed calendars need a d tag`() {
        val bare =
            Fixtures.event(
                "aa55".repeat(16),
                Fixtures.ALICE,
                "",
                kind = 31923,
                tags = listOf(listOf("title", "No address")),
            )
        assertTrue(Calendar.listed(Fixtures.corpus(listOf(bare), Desk.CALENDAR)).isEmpty())
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
        val url = Calendar.canonicalUrl(allDay)
        assertTrue(url.contains("naddr1"))
        assertEquals(allDay.id.lowercase(), Calendar.calendarLinkTarget(url, listOf(allDay)))
    }
}
