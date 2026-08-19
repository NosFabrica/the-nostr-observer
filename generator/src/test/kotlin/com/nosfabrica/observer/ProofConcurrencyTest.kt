package com.nosfabrica.observer

import com.nosfabrica.observer.safe.Proof
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Two readers printing at the same moment.
 *
 * `Press` holds one [Proof] and the server holds one `Press`, so every reader's
 * edition renders through the same browser. Playwright is not concurrent, and
 * before this was serialised, four threads calling `check` gave one success and
 * three exceptions:
 *
 *     Object doesn't exist: tracing@99bbed9e…
 *     Cannot find object to call __adopt__: browser-context@6a876b2a…
 *
 * That exception surfaced inside `Editions.run`, where the catch-all turns it
 * into a FAILED edition. So the second reader to press print lost a paper the
 * model had already been paid for, because of something another reader did.
 *
 * Skipped rather than failed when no browser can start, for the same reason
 * `Proof` itself degrades: a machine without Chromium should lose the check and
 * keep everything else.
 */
class ProofConcurrencyTest {
    @Test
    fun `several editions can render at once`() {
        val html =
            "<!doctype html><html><head><style>body{background:#fff;color:#111;font-size:18px}</style></head><body><main>" +
                (1..40).joinToString("") { "<p>Something worth reading, number $it, at a readable length.</p>" } +
                "</main></body></html>"

        Proof().use { proof ->
            if (!proof.check(html).ran) return

            val racers = 4
            val ready = CountDownLatch(racers)
            val go = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(racers)
            val results =
                (1..racers).map {
                    pool.submit<Result<Proof.Report>> {
                        ready.countDown()
                        go.await()
                        runCatching { proof.check(html) }
                    }
                }
            ready.await()
            go.countDown()

            results.map { it.get() }.forEachIndexed { i, result ->
                // `check` catches, so a throw here would mean the catch itself
                // failed -- but assert on it rather than on `ran`, because the
                // two say different things and only one of them is the bug.
                assertTrue(result.isSuccess, "render $i threw: ${result.exceptionOrNull()}")
                val report = result.getOrThrow()
                assertTrue(report.ran, "render $i did not run: ${report.why}")
                assertTrue(report.ok, "render $i found problems in a page that renders alone: ${report.summary()}")
            }
            pool.shutdown()
        }
    }
}
