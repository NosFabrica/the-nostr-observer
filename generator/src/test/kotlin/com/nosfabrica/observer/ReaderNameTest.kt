package com.nosfabrica.observer

import com.nosfabrica.observer.nostr.Byline
import com.nosfabrica.observer.nostr.Corpus
import com.nosfabrica.observer.nostr.Desk
import com.nosfabrica.observer.nostr.Names
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The page names the reader, so the reader's name has to be fetched.
 *
 * THE BUG, found by a real run and not by any test here: keeping the reader out
 * of their own paper drops their events from every desk, so their key appears
 * in no author list, so their `kind 0` was never asked for — and the line that
 * says who the paper was ranked for came out as an npub. Two correct changes,
 * one wrong result, and only a live edition showed it.
 */
class ReaderNameTest {
    @Test
    fun `the reader is named even though they are not in the paper`() {
        // Exactly the shape the bug had: nothing of theirs in any desk, and a
        // profile for them all the same.
        val corpus =
            Corpus(
                observer = Fixtures.OBSERVER,
                since = 0,
                until = 1,
                ranked = mapOf(Desk.NOTES to listOf(Fixtures.plain)),
                control = emptyList(),
                dayNotes = null,
                profiles = mapOf(Fixtures.OBSERVER to Byline(Fixtures.OBSERVER, 1, "Vitor Pamplona", null)),
            )
        assertEquals("Vitor Pamplona", corpus.byline(corpus.observer))
    }

    @Test
    fun `and falls back to their npub rather than a key`() {
        val corpus =
            Corpus(
                observer = Fixtures.OBSERVER,
                since = 0,
                until = 1,
                ranked = mapOf(Desk.NOTES to listOf(Fixtures.plain)),
                control = emptyList(),
                dayNotes = null,
                profiles = emptyMap(),
            )
        assertEquals(Names.short(Fixtures.OBSERVER), corpus.byline(corpus.observer))
    }
}
