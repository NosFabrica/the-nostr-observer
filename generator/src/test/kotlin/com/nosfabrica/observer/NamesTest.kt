package com.nosfabrica.observer

import com.nosfabrica.observer.corpus.Digest
import com.nosfabrica.observer.nostr.Byline
import com.nosfabrica.observer.nostr.Desk
import com.nosfabrica.observer.nostr.Names
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * No hex in front of a person, anywhere.
 *
 * A truncated key was the fallback in four separate places, and it is the worst
 * of the available options: unreadable, not a valid identifier anybody can
 * paste anywhere, and it looks like a fault in the page. A person is their
 * name; failing that, their npub.
 */
class NamesTest {
    private val hex = Fixtures.ALICE

    @Test
    fun `a key becomes an npub`() {
        assertTrue(Names.npub(hex)!!.startsWith("npub1"), Names.npub(hex)!!)
        assertTrue(Names.short(hex).startsWith("npub1"), Names.short(hex))
    }

    @Test
    fun `a short npub is short enough for a byline and still an npub`() {
        val short = Names.short(hex)
        assertTrue(short.length < 20, "a byline is not 63 characters: $short")
        assertTrue(short.contains("…"), short)
    }

    @Test
    fun `nonsense does not become a hex string by accident`() {
        // The failure to avoid is a fallback that quietly prints the input.
        assertEquals("someone", Names.short("not a key"))
    }

    @Test
    fun `a nameless author is an npub in the byline, never a key`() {
        val nameless = Byline(hex, 1, null, null)
        assertEquals(Names.short(hex), nameless.display())
        assertFalse(nameless.display().contains(hex.take(8)))
    }

    @Test
    fun `the digest hands the writer no hex it could print`() {
        // The one place hex legitimately appears is inside an art URL, which is
        // a content address on somebody's media server. Everything the writer
        // is invited to attribute or cite comes through as a name or a bech32
        // identifier.
        // A real-shaped id, because the toy ids elsewhere in the fixtures are
        // not hex and would make this pass without proving anything.
        val id = "c0ffee".repeat(10) + "abcd"
        val post = Fixtures.event(id, Fixtures.ALICE, "a note")
        val text = Digest().render(Fixtures.corpus(listOf(post), Desk.NOTES), emptyList()).text
        assertTrue(text.contains("Alice"), text)
        assertFalse(text.contains(Fixtures.ALICE), "a pubkey reached the digest")
        assertFalse(text.contains(id), "a raw event id reached the digest: $text")
        assertTrue(text.contains("note1"), "the citation handle is bech32: $text")
    }
}
