package com.nosfabrica.observer.press

import com.nosfabrica.observer.Press
import com.nosfabrica.observer.nostr.Relays
import com.nosfabrica.observer.press.publish.Announce
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The archive is read from the reader's relays, and from nowhere else.
 *
 * This is a one-line assertion about a one-line change, and it is here because
 * the line it protects is the difference between an archive and a claim. The
 * read used to append our own search relay to the reader's list, so an edition
 * could be listed as published because WE could resolve it while their relays
 * could not — the exact "resolves for us and for nobody else" failure the
 * design exists to prevent, wearing the costume of a working feature.
 *
 * A reader with no relays we know of therefore has no archive we can show, and
 * that is the honest answer. The tempting version — fall back to our relay so
 * the screen has something on it — is the bug.
 */
class ArchiveReadTest {
    @Test
    fun `a reader with no relays of their own has no archive, not ours`() =
        runTest {
            Relays().use { relays ->
                // An unreachable search relay, which is the point: if this read
                // consulted it at all, the call would spend its idle window
                // trying. With nowhere of the reader's own to ask, there is
                // nothing to ask, and it returns at once.
                val press = Press(relays, "wss://unreachable.invalid")
                val announce = Announce(relays, press)

                val started = System.nanoTime()
                val editions = announce.editions("a".repeat(64), emptyList())
                val tookMs = (System.nanoTime() - started) / 1_000_000

                assertTrue(editions.isEmpty(), "nothing of the reader's own was asked, so there is nothing to list")
                // Generous by an order of magnitude: quartz's idle window here
                // is ten seconds, so any relay contact at all blows this.
                assertTrue(tookMs < 1_000, "it asked somebody: took ${tookMs}ms")
            }
        }
}
