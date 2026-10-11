package com.nosfabrica.observer.press

import com.nosfabrica.observer.Press
import com.nosfabrica.observer.nostr.Relays
import com.nosfabrica.observer.press.publish.Announce
import com.nosfabrica.observer.press.store.Continuities
import com.nosfabrica.observer.press.store.Db
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

/**
 * A finished page that waits for a reader who is not there.
 *
 * Batching made this ordinary: an edition can come back long after the reader
 * closed the tab. Two things then have to hold -- the page is still here when
 * they return, and the authorization it hands their signer has not lapsed.
 */
class WaitingPageTest {
    private val reader = "b".repeat(64)

    @Test
    fun `a page waiting for a signature outlives the ordinary sweep`() {
        // Everything not waiting on a signer is past its time the moment it settles.
        val runs = Runs(ttlSeconds = -1, signingTtlSeconds = 3_600)
        val (run, _) = runs.open(reader)

        run.state = Runs.State.SIGNING
        assertEquals(0, runs.sweep(), "a page waiting for its reader was swept")
        assertSame(run, runs.of(reader))

        run.state = Runs.State.FAILED
        assertEquals(1, runs.sweep())
    }

    @Test
    fun `an upload authorization about to lapse is renewed, and a fresh one is left alone`(
        @TempDir dir: Path,
    ) {
        val relays = Relays()
        val press = Press(relays, "wss://unreachable.invalid")
        val editions =
            Editions(
                press,
                Runs(),
                Announce(relays, press),
                Continuities(Db(dir.resolve("w.db").toString())),
                CoroutineScope(SupervisorJob()),
            )

        val run = Runs.Run("id", reader)
        run.html = "<p>page</p>".toByteArray()
        run.sha = "c".repeat(64)
        run.state = Runs.State.SIGNING

        // Lapsed: the batch came back an hour ago and nobody was there.
        run.uploadExpires = Instant.now().epochSecond - 3_000
        editions.freshen(run)
        assertNotNull(run.upload)
        val renewed = run.upload
        assertTrue(run.uploadExpires > Instant.now().epochSecond + 500, "renewed for the full ten minutes")

        // Fresh: a template already on its way to the signer stays put.
        editions.freshen(run)
        assertSame(renewed, run.upload)
        relays.close()
    }
}
