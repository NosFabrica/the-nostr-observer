package com.nosfabrica.observer.press

import com.nosfabrica.observer.Press
import com.nosfabrica.observer.nostr.Relays
import com.nosfabrica.observer.press.publish.Announce
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Silence is not an empty archive.
 *
 * A `kind 35128` carries every path the reader has ever published and it
 * REPLACES on publish, so the manifest we build has to be the whole list. That
 * list used to come from a table of ours; it now comes from their own site
 * event, which is the durable copy and the only one that survives this
 * deployment.
 *
 * Which turns a read into a precondition. If no relay answers and we read that
 * as "you have published nothing", the next publish replaces a reader's whole
 * back catalogue with today's paper — so the two cases have to be told apart,
 * and this is the test that they are.
 */
class ArchiveTest {
    @Test
    fun `no answer is not the same as no site`() =
        runTest {
            Relays().use { relays ->
                val announce =
                    Announce(
                        relays,
                        "wss://unreachable.invalid",
                        Press(relays, "wss://unreachable.invalid"),
                    )
                // Nothing here is reachable, so nothing here knows anything. The
                // wrong answer is `Missing`, and it is the plausible one: an
                // unreachable relay returns no events, and no events looks exactly
                // like a reader who has never published.
                assertEquals(
                    Announce.Site.Unreadable,
                    announce.existing("9".repeat(64), listOf("wss://also-unreachable.invalid")),
                )
            }
        }
}
