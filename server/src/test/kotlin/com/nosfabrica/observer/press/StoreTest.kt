package com.nosfabrica.observer.press

import com.nosfabrica.observer.press.auth.Sessions
import com.nosfabrica.observer.press.publish.Pendings
import com.nosfabrica.observer.press.publish.Templates
import com.nosfabrica.observer.press.store.Continuities
import com.nosfabrica.observer.press.store.Db
import com.nosfabrica.observer.press.store.Drafts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DraftTest {
    private val alice = "a".repeat(64)
    private val mallory = "b".repeat(64)

    private fun db(dir: Path) = Db(dir.resolve("t.db").toString())

    @Test
    fun `a draft belongs to the reader who made it`(
        @TempDir dir: Path,
    ) {
        val drafts = Drafts(db(dir))
        val id = drafts.open(alice)

        assertNotNull(drafts.of(id, alice))
        // Unguessable ids keep strangers out; this keeps out a signed-in reader
        // who simply tries somebody else's id, which is a different attack and
        // needs its own answer.
        assertNull(drafts.of(id, mallory), "another reader must not read it by id")
    }

    @Test
    fun `an expired draft is gone rather than merely old`(
        @TempDir dir: Path,
    ) {
        // A TTL nobody enforces is a retention policy of "forever". Zero seconds
        // is the same code path a six-hour draft takes, just already past.
        val drafts = Drafts(db(dir), ttlSeconds = -1)
        val id = drafts.open(alice)
        assertNull(drafts.of(id, alice))
    }

    @Test
    fun `a run reaches ready with its page and hash`(
        @TempDir dir: Path,
    ) {
        val drafts = Drafts(db(dir))
        val id = drafts.open(alice)
        assertEquals(Drafts.State.RUNNING, drafts.of(id, alice)?.state)

        drafts.ready(id, "<main>hello</main>", "c".repeat(64), "{}", "[]")
        val done = drafts.of(id, alice)!!
        assertEquals(Drafts.State.READY, done.state)
        assertEquals("<main>hello</main>", done.html)
        assertEquals("c".repeat(64), done.sha256)
    }

    @Test
    fun `migrations are idempotent across opens`(
        @TempDir dir: Path,
    ) {
        // The second open must not try to create the tables again. This is the
        // whole point of numbering them, and it only ever fails on restart --
        // which is to say, in production and not in a fresh test.
        val path = dir.resolve("twice.db").toString()
        val id = Drafts(Db(path)).open(alice)
        assertNotNull(Drafts(Db(path)).of(id, alice))
    }
}

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

class PendingsTest {
    private fun template() = Templates.uploadAuth("a".repeat(64), 10, 1, 2)

    @Test
    fun `a prepared template survives long enough to be signed`() {
        val pendings = Pendings()
        pendings["draft"] = Pendings.Pending(template(), template(), emptyList(), emptyList(), "a".repeat(64), "2026-08-18")
        assertNotNull(pendings["draft"])
    }

    @Test
    fun `an abandoned prepare does not live forever`() {
        // Every Prepare the reader then walked away from used to leave an entry
        // nothing removed. The upload authorization inside is dead after ten
        // minutes anyway, so the entry was a leak holding something unusable.
        val pendings = Pendings(ttlSeconds = -1)
        pendings["draft"] = Pendings.Pending(template(), template(), emptyList(), emptyList(), "a".repeat(64), "2026-08-18")
        assertNull(pendings["draft"])
        pendings.sweep()
        assertEquals(0, pendings.size())
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
