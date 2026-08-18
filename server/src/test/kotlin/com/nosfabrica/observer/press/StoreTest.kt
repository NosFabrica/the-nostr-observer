package com.nosfabrica.observer.press

import com.nosfabrica.observer.press.auth.Sessions
import com.nosfabrica.observer.press.store.Continuities
import com.nosfabrica.observer.press.store.Db
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ContinuityTest {
    @Test
    fun `a first-time reader gets the house masthead`(
        @TempDir dir: Path,
    ) {
        val continuities = Continuities(Db(dir.resolve("c.db").toString()))
        assertEquals("The Nostr Observer", continuities.of("f".repeat(64)).masthead)
    }

    @Test
    fun `yesterday's masthead comes back tomorrow`(
        @TempDir dir: Path,
    ) {
        // "Softly in place" only means anything if it is written down. Without
        // this record every edition is the first edition.
        val continuities = Continuities(Db(dir.resolve("c.db").toString()))
        val reader = "d".repeat(64)
        continuities.remember(reader, "The Daily Zap", "All the sats fit to print", listOf("Wire"), listOf("A headline"))

        val back = continuities.of(reader)
        assertEquals("The Daily Zap", back.masthead)
        assertEquals(listOf("A headline"), back.recentHeadlines)
    }

    @Test
    fun `headlines do not grow without bound`(
        @TempDir dir: Path,
    ) {
        // Unbounded, this list would grow into the prompt one day at a time
        // until it crowded out the corpus it is supposed to be context for.
        val continuities = Continuities(Db(dir.resolve("c.db").toString()))
        val reader = "e".repeat(64)
        continuities.remember(reader, "m", "s", emptyList(), (1..50).map { "headline $it" })
        assertTrue(continuities.of(reader).recentHeadlines.size <= 12)
    }
}

class SessionTest {
    @Test
    fun `a token names a reader until it is closed`() {
        val sessions = Sessions()
        val token = sessions.open("a".repeat(64), Sessions.Signer.NIP07)
        assertEquals("a".repeat(64), sessions.of(token)?.pubkey)
        sessions.close(token)
        assertNull(sessions.of(token))
    }

    @Test
    fun `an expired session is not a session`() {
        val sessions = Sessions(ttlSeconds = -1)
        assertNull(sessions.of(sessions.open("a".repeat(64), Sessions.Signer.NIP07)))
    }

    @Test
    fun `nonsense is refused without a lookup`() {
        val sessions = Sessions()
        assertNull(sessions.of(null))
        assertNull(sessions.of(""))
        assertNull(sessions.of("not-a-token"))
    }

    @Test
    fun `two sign-ins are two tokens`() {
        // Sessions are per sign-in, so signing out on a laptop does not sign the
        // reader out on their phone.
        val sessions = Sessions()
        val reader = "a".repeat(64)
        assertNotEquals(sessions.open(reader, Sessions.Signer.NIP07), sessions.open(reader, Sessions.Signer.NIP46))
    }
}

class SessionSweepTest {
    @Test
    fun `expired sessions are removed even if nobody presents them`() {
        // Expiry that only happens on access is not expiry. A reader who signs
        // in once from a phone and never comes back leaves a row that nothing
        // ever looks at again, so nothing ever removes it.
        val sessions = Sessions(ttlSeconds = -1)
        sessions.open("a".repeat(64), Sessions.Signer.NIP07)
        sessions.open("b".repeat(64), Sessions.Signer.NIP46)
        assertEquals(2, sessions.size())
        assertEquals(2, sessions.sweep())
        assertEquals(0, sessions.size())
    }
}
