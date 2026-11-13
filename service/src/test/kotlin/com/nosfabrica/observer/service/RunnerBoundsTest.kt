package com.nosfabrica.observer.service

import com.nosfabrica.observer.service.state.MemoryPendingStore
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip5aStaticWebsites.observer.ObserverSite
import com.vitorpamplona.quartz.nipFAAgentKeys.tags.AgentKeyTag
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTask
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTaskEvent
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How long anything can hold the worker, what survives a halt, and which reads are trusted. */
class RunnerBoundsTest : RunnerWorld() {
    @Test
    fun aRelayThatNeverAnswersDoesNotHoldTheWake() =
        runTest {
            world()
            schedule()
            // The owner's relay takes every REQ and never ends one: no EOSE, no CLOSED, no error.
            // Every relay is asked on its own subscription, so it costs its own deadline, and the
            // others (the index) still answer.
            val hanging =
                object : RelayAccess by relays {
                    override suspend fun fetch(
                        relays: Set<NormalizedRelayUrl>,
                        filter: Filter,
                    ): RelayRead {
                        if (ownerRelay !in relays) return this@RunnerBoundsTest.relays.fetch(relays, filter)
                        delay(QuartzRelayAccess.FETCH_DEADLINE_MS)
                        return this@RunnerBoundsTest.relays.fetch(relays - ownerRelay, filter)
                    }
                }
            // It answered for nobody: no quorum on the owner's relays, nothing spent.
            assertEquals(RunOutcome.NO_QUORUM, runnerWith(access = hanging).run(owner.pubKey))
            assertTrue(printer.calls.isEmpty())
        }

    @Test
    fun aPaperIsKeptBeforeItsPublishSoAHaltMidPublishLosesNothing() =
        runTest {
            world()
            schedule()
            // The publish to the Assistant's relays never returns: the worker is stopped there.
            val stuck =
                object : RelayAccess by relays {
                    override suspend fun publish(
                        event: Event,
                        relays: Set<NormalizedRelayUrl>,
                    ): Int = awaitCancellation()
                }
            val pending = MemoryPendingStore()
            assertNull(withTimeoutOrNull(60_000) { runnerWith(access = stuck, pending = pending).run(owner.pubKey) })
            assertEquals(1, printer.calls.size)
            // A restart finds it in the store, and publishes it without printing again.
            assertEquals(listOf(six), pending.list(ObserverRunner.TASK, owner.pubKey).map { it.occurrence })
            runnerWith(pending = pending).run(owner.pubKey)
            assertEquals(1, printer.calls.size)
            assertTrue(relays.published.any { ObserverSite.isRunnerSiteFor(it.first, assistant.pubKey, owner.pubKey) })
            assertTrue(pending.list(ObserverRunner.TASK, owner.pubKey).isEmpty())
        }

    @Test
    fun anOlderUnpublishedPaperIsNotDroppedWhenANewerOneIsPrinted() =
        runTest {
            world()
            schedule()
            // Yesterday's paper, paid for and never published, still within its retry time.
            val html = "<!doctype html><html><body><h1>Yesterday</h1></body></html>"
            val yesterday =
                PendingPaper(
                    owner = owner.pubKey,
                    occurrence = six - 86_400,
                    day = "2026-11-11",
                    code = "",
                    printedAt = six - 86_400,
                    model = "m",
                    quotes = 1,
                    posts = 1,
                    headline = "Yesterday",
                    sha256 = sha256Hex(html.encodeToByteArray()),
                    html = html,
                    readerName = "Vitor",
                    until = now + 3_600,
                )
            val pending = MemoryPendingStore()
            pending.put(yesterday.toPending())
            val worker = runnerWith(pending = pending)
            blossom.down = true
            assertEquals(RunOutcome.PUBLISH_PENDING, worker.run(owner.pubKey))
            // Today's paper is kept beside it, not in its place.
            assertEquals(listOf(six - 86_400, six), pending.list(ObserverRunner.TASK, owner.pubKey).map { it.occurrence })
            // Back up: both go out, in one site.
            blossom.down = false
            clock += 600
            worker.run(owner.pubKey)
            assertTrue(pending.list(ObserverRunner.TASK, owner.pubKey).isEmpty())
            val site = relays.published.last().first
            val paths = site.tags.filter { it.size > 1 && it[0] == "path" }.map { it[1] }
            assertTrue("/2026-11-11.html" in paths && "/2026-11-12.html" in paths, paths.toString())
            assertEquals(1, printer.calls.size)
        }

    @Test
    fun aModelsListingThatNeverAnswersEndsTheRun() =
        runTest {
            world()
            schedule(task(model = null, key = null))
            val outcome = runnerWith(models = { awaitCancellation() }).run(owner.pubKey)
            assertEquals(RunOutcome.NO_MODEL, outcome)
            assertTrue(printer.calls.isEmpty())
        }

    @Test
    fun theAssistantsKeysAreReadWithAQuorum() =
        runTest {
            world()
            val second = relay("wss://assistant-two.example")
            relays.put(index, assistant.signed(10002, arrayOf(arrayOf("r", assistantRelay.url), arrayOf("r", second.url)), createdAt = 2))
            schedule()
            // One of the Assistant's two relays answers: a newer list (a key taken back) may be on the other.
            relays.down = setOf(second)
            assertEquals(RunOutcome.NO_QUORUM, runner.run(owner.pubKey))
            assertTrue(printer.calls.isEmpty())
            relays.down = emptySet()
            assertEquals(RunOutcome.PRINTED, runner.run(owner.pubKey))
        }

    @Test
    fun aSettledKeyOutcomeIsLookedAtAgainWhenTheAssistantsKeysChange() =
        runTest {
            world()
            val elsewhere = AgentKeyTag("sk-other-9999", "https://api.example.com/v1")
            schedule(task(key = elsewhere))
            assertEquals(RunOutcome.KEY_NOT_GIVEN, runner.run(owner.pubKey))
            // The owner gives the Assistant that key: a new kind 12000.
            relays.put(assistantRelay, agentKeyList(assistant, listOf(openRouter, elsewhere), createdAt = 2))
            assertEquals(RunOutcome.PRINTED, runner.run(owner.pubKey))
            assertEquals(elsewhere, printer.calls.single().first)
        }
}
