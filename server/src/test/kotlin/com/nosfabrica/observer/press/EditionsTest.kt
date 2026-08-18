package com.nosfabrica.observer.press

import com.nosfabrica.observer.Press
import com.nosfabrica.observer.nostr.Relays
import com.nosfabrica.observer.press.store.Continuities
import com.nosfabrica.observer.press.store.Db
import com.nosfabrica.observer.press.store.Drafts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * One edition at a time, per reader.
 *
 * Generation costs real money and reads somebody else's relay hard, so two
 * clicks on a slow button must not become two runs. The guard used to be a
 * get-then-put, which is a race that only shows up under exactly the condition
 * that produces it: two requests arriving at once.
 *
 * The runs launched here immediately fail against an unreachable relay, which
 * is fine — what is being tested is how many were started, not what they did.
 */
class EditionsTest {
    @Test
    fun `two clicks at once are one edition`(
        @TempDir dir: Path,
    ) {
        val db = Db(dir.resolve("e.db").toString())
        val drafts = Drafts(db)
        val relays = Relays()
        val editions =
            Editions(
                Press(relays, "wss://unreachable.invalid"),
                drafts,
                Continuities(db),
                CoroutineScope(SupervisorJob()),
            )

        val reader = "a".repeat(64)
        val racers = 8
        val ready = CountDownLatch(racers)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(racers)
        val ids =
            (1..racers).map {
                pool.submit<String> {
                    ready.countDown()
                    go.await()
                    editions.start(reader, false)
                }
            }
        ready.await()
        go.countDown()

        val started = ids.map { it.get() }.toSet()
        pool.shutdownNow()
        relays.close()

        assertEquals(1, started.size, "eight simultaneous clicks started ${started.size} editions")
    }
}
