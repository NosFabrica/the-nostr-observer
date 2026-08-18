package com.nosfabrica.observer

import com.nosfabrica.observer.nostr.Corpus
import com.nosfabrica.observer.nostr.Desk
import com.vitorpamplona.quartz.nip01Core.core.Event
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The number in the top-left corner.
 *
 * It identifies a print run rather than a file. The page's OWN hash cannot go
 * here — printing it would change the page and therefore the hash — so this
 * fingerprints the material the edition was made from, and the properties that
 * matter are that identical material gives an identical code and that anything
 * different gives a different one.
 */
class CodeTest {
    private fun corpus(
        events: List<Event>,
        observer: String = Fixtures.OBSERVER,
        until: Long = 1_787_018_640,
    ) = Corpus(
        observer = observer,
        since = until - 86_400,
        until = until,
        ranked = mapOf(Desk.NOTES to events),
        control = emptyList(),
        dayNotes = null,
        profiles = emptyMap(),
    )

    private val events = listOf(Fixtures.plain, Fixtures.withArt, Fixtures.video)

    @Test
    fun `it is six hex digits`() {
        assertTrue(corpus(events).code().matches(Regex("^[0-9A-F]{6}$")), corpus(events).code())
    }

    @Test
    fun `the same material gives the same code`() {
        assertEquals(corpus(events).code(), corpus(events).code())
    }

    @Test
    fun `the order the desks came back in does not change it`() {
        // The desks are pulled in parallel and the order they finish in is a
        // race. A code that moved between two runs over identical material
        // would be worse than no code at all.
        assertEquals(corpus(events).code(), corpus(events.reversed()).code())
    }

    @Test
    fun `one more event is a different edition`() {
        assertNotEquals(corpus(events).code(), corpus(events + Fixtures.injection).code())
    }

    @Test
    fun `two readers on the same morning do not share a code`() {
        // Even on identical material: the paper is read FOR somebody, and two
        // people's editions are two units.
        assertNotEquals(corpus(events).code(), corpus(events, observer = Fixtures.ALICE).code())
    }

    @Test
    fun `an hour later is a different edition`() {
        assertNotEquals(corpus(events).code(), corpus(events, until = 1_787_018_640 + 3_600).code())
    }
}
