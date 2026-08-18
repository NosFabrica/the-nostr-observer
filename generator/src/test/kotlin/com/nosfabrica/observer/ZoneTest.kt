package com.nosfabrica.observer

import com.nosfabrica.observer.corpus.Digest
import com.nosfabrica.observer.nostr.Corpus
import com.nosfabrica.observer.nostr.Desk
import com.nosfabrica.observer.write.Continuity
import com.nosfabrica.observer.write.Writer
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The paper is dated in the reader's day.
 *
 * A page whose only clock is UTC is dated wrong for a good part of the world
 * for a good part of every day, and the page itself cannot fix it: a published
 * edition carries no script, by design and by its own Content-Security-Policy,
 * so nothing in it can read a viewer's clock. The zone has to be baked in when
 * the edition is written, which makes this the one place it can go wrong.
 */
class ZoneTest {
    /** 02:04 UTC on Tuesday 18 August 2026 — which is still Monday in New York. */
    private val closed = 1_787_018_640L

    private fun brief(zone: ZoneId): String {
        val corpus =
            Corpus(
                observer = Fixtures.OBSERVER,
                since = closed - 86_400,
                until = closed,
                ranked = mapOf(Desk.NOTES to listOf(Fixtures.plain)),
                control = emptyList(),
                dayNotes = 100,
                profiles = emptyMap(),
            )
        return Writer().userMessage(corpus, Digest().render(corpus, emptyList()), emptyList(), Continuity(), zone)
    }

    @Test
    fun `an edition closing after midnight UTC is still yesterday's paper in New York`() {
        // The failure this catches is a front page dated tomorrow, which is the
        // one dateline error a reader cannot fail to notice.
        val utc = brief(ZoneOffset.UTC)
        assertTrue(utc.contains("Date: Tuesday, August 18, 2026"), utc.lineSequence().first { it.startsWith("Date") })

        val newYork = brief(ZoneId.of("America/New_York"))
        assertTrue(newYork.contains("Date: Monday, August 17, 2026"), newYork.lineSequence().first { it.startsWith("Date") })
    }

    @Test
    fun `the window stamp is local, and says which local`() {
        val newYork = brief(ZoneId.of("America/New_York"))
        // 02:04 UTC is 22:04 the previous evening on the US east coast. Printing
        // the hour without the zone would be a different lie from printing UTC.
        assertTrue(newYork.contains("ending 22:04 EDT"), newYork.lineSequence().first { it.startsWith("Window") })
        assertFalse(newYork.contains("02:04"), "the UTC hour must not survive anywhere in the brief")
    }

    @Test
    fun `a zone east of the line moves the other way`() {
        val auckland = brief(ZoneId.of("Pacific/Auckland"))
        assertTrue(auckland.contains("Date: Tuesday, August 18, 2026"), "Auckland is already well into Tuesday")
        assertTrue(auckland.contains("ending 14:04 NZST"), auckland.lineSequence().first { it.startsWith("Window") })
    }

    @Test
    fun `the model is never asked to convert a timestamp itself`() {
        // It has no reliable way to know the offset in force on the day, and a
        // raw instant in the brief is an invitation to try.
        val brief = brief(ZoneId.of("Europe/Lisbon"))
        assertFalse(brief.contains("Z\n"), "no ISO instant: $brief")
        assertFalse(brief.contains(closed.toString()), "no epoch seconds either")
    }
}
