package com.nosfabrica.observer.runner

import com.nosfabrica.assistant.core.MemoryPendingStore
import com.nosfabrica.assistant.core.MemoryRunStore
import com.nosfabrica.assistant.core.PendingStore
import com.nosfabrica.assistant.core.RunStore
import com.nosfabrica.assistant.core.WakeRequest
import com.nosfabrica.assistant.core.capturingLogs
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip5aStaticWebsites.observer.ObserverSite
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTask
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The wakes: one path per owner, joined, bounded, answered from the `runs` row; the retry loop; the claim race. */
class WakeServiceTest : RunnerWorld() {
    private val pending = MemoryPendingStore()

    private fun TestScope.service(
        printer: PaperPrinter = this@WakeServiceTest.printer,
        parallelism: Int = ObserverRunner.PARALLELISM,
        accepting: () -> Boolean = { true },
        runs: RunStore = this@WakeServiceTest.runs,
        pending: PendingStore = this@WakeServiceTest.pending,
        scope: CoroutineScope = backgroundScope,
    ) = WakeService(runnerWith(printer = printer, runs = runs, pending = pending), runs, pending, scope, parallelism, accepting)

    private fun wake(
        who: HexKey = owner.pubKey,
        occurrence: Long = six,
        task: String = "observer",
    ) = WakeRequest(task, who, occurrence)

    /** A printer whose print waits for [release], having said it started on [started]. */
    private class Gate {
        val started = CompletableDeferred<HexKey>()
        val release = CompletableDeferred<Unit>()

        fun around(inner: PaperPrinter) =
            PaperPrinter { key, model, who, name, options ->
                started.complete(who)
                release.await()
                inner.print(key, model, who, name, options)
            }
    }

    @Test
    fun aWakeRunsTheOwnersPathInTheBackground() =
        runTest {
            world()
            schedule()
            val service = service()
            assertEquals(WakeAnswer.Accepted(joined = false), service.wake(wake()))
            service.idle()
            assertEquals(1, printer.calls.size)
            assertTrue(ObserverSite.isRunnerSiteFor(relays.published.single().first, assistant.pubKey, owner.pubKey))
            assertEquals(0, service.busy)
        }

    @Test
    fun aSecondWakeForAnOwnerAlreadyRunningJoinsThatRun() =
        runTest {
            world()
            schedule()
            val gate = Gate()
            val service = service(printer = gate.around(printer))
            assertEquals(WakeAnswer.Accepted(joined = false), service.wake(wake()))
            gate.started.await()
            // The runner woke again (its retry, or a second replica of it): no second print.
            assertEquals(WakeAnswer.Accepted(joined = true), service.wake(wake()))
            assertEquals(1, service.busy)
            gate.release.complete(Unit)
            service.idle()
            assertEquals(1, printer.calls.size)
            assertEquals(1, relays.published.size)
        }

    @Test
    fun anOccurrenceWithARowIsAnsweredFromItWithoutReadingARelay() =
        runTest {
            world()
            schedule()
            val service = service()
            service.wake(wake())
            service.idle()
            val asked = relays.asked.size
            assertEquals(WakeAnswer.Settled("PRINTED"), service.wake(wake()))
            // Another occurrence's row that is still `started` (another replica's run, or one that died) is settled too.
            runs.claim(ObserverRunner.TASK, owner.pubKey, six - 86_400)
            assertEquals(WakeAnswer.Settled("ATTEMPTED"), service.wake(wake(occurrence = six - 86_400)))
            assertEquals(asked, relays.asked.size, "a settled wake read a relay")
            assertEquals(1, printer.calls.size)
        }

    @Test
    fun aWakePastEverySlotIsBusyAndTheNextIsTaken() =
        runTest {
            world()
            schedule()
            secondOwner()
            val gate = Gate()
            val service = service(printer = gate.around(printer), parallelism = 1)
            assertEquals(WakeAnswer.Accepted(joined = false), service.wake(wake()))
            gate.started.await()
            assertEquals(WakeAnswer.Busy(WakeService.RETRY_AFTER_SECONDS), service.wake(wake(stranger.pubKey)))
            gate.release.complete(Unit)
            service.idle()
            assertEquals(WakeAnswer.Accepted(joined = false), service.wake(wake(stranger.pubKey)))
            service.idle()
            assertEquals(setOf(owner.pubKey, stranger.pubKey), printer.calls.map { it.third }.toSet())
        }

    @Test
    fun oneSlowReaderDoesNotHoldUpTheOthers() =
        runTest {
            world()
            schedule()
            secondOwner()
            // The owner's print waits until the stranger's is done: run one after the other, it never would.
            val strangerDone = CompletableDeferred<Unit>()
            val gated =
                PaperPrinter { key, model, who, name, options ->
                    if (who == owner.pubKey) strangerDone.await() else strangerDone.complete(Unit)
                    printer.print(key, model, who, name, options)
                }
            val service = service(printer = gated)
            service.wake(wake())
            service.wake(wake(stranger.pubKey))
            service.idle()
            assertEquals(setOf(owner.pubKey, stranger.pubKey), printer.calls.map { it.third }.toSet())
        }

    @Test
    fun aStoppingServiceTakesNoWakeAndStartsNoPrint() =
        runTest {
            world()
            schedule()
            var stopping = false
            val service = service(accepting = { !stopping })
            stopping = true
            assertEquals(WakeAnswer.Busy(WakeService.RETRY_AFTER_SECONDS), service.wake(wake()))
            service.republishPending()
            service.idle()
            assertTrue(printer.calls.isEmpty())
            assertTrue(api.decrypted.isEmpty())
        }

    @Test
    fun stoppingWaitsForThePrintUnderWayOnlyForTheGrace() =
        runTest {
            world()
            schedule()
            val gate = Gate()
            val service = service(printer = gate.around(printer))
            service.wake(wake())
            gate.started.await()
            // The print outlives the grace: the drain gives up, saying so.
            assertFalse(service.drain(1_000))
            gate.release.complete(Unit)
            service.idle()
            assertEquals(1, printer.calls.size)
        }

    @Test
    fun aWakeForAnotherTaskIsMalformed() =
        runTest {
            val service = service()
            assertEquals(WakeAnswer.Malformed("this service runs observer"), service.wake(wake(task = "podcast")))
            assertEquals(0, service.busy)
        }

    @Test
    fun twoReplicasWokenTogetherPrintOnce() =
        runTest {
            world()
            schedule()
            // Both reach the claim before either inserts: only the inserter prints.
            val arrived = CompletableDeferred<Unit>()
            var waiting = 0
            val racing =
                object : RunStore by runs {
                    override suspend fun claim(
                        task: String,
                        owner: HexKey,
                        occurrence: Long,
                        scheduleId: String?,
                    ): Boolean {
                        if (++waiting == 2) arrived.complete(Unit)
                        arrived.await()
                        return runs.claim(task, owner, occurrence, scheduleId)
                    }
                }
            val outcomes =
                listOf(runnerWith(runs = racing), runnerWith(runs = racing))
                    .map { replica -> async { replica.run(owner.pubKey) } }
                    .awaitAll()
            assertEquals(setOf(RunOutcome.PRINTED, RunOutcome.ATTEMPTED), outcomes.toSet())
            assertEquals(1, printer.calls.size)
            assertEquals(1, relays.published.size)
        }

    @Test
    fun theRetryLoopPublishesAWaitingPaperWithoutPrintingAgain() =
        runTest {
            world()
            schedule()
            val service = service()
            blossom.down = true
            service.wake(wake())
            service.idle()
            assertEquals(listOf(six), pending.list(ObserverRunner.TASK, owner.pubKey).map { it.occurrence })
            assertTrue(relays.published.isEmpty())
            // The row says how it went: settled, so the runner stops waking.
            assertEquals(WakeAnswer.Settled("PUBLISH_PENDING"), service.wake(wake()))
            blossom.down = false
            clock += 600
            service.republishPending()
            service.idle()
            assertEquals(1, printer.calls.size)
            assertTrue(ObserverSite.isRunnerSiteFor(relays.published.single().first, assistant.pubKey, owner.pubKey))
            assertTrue(pending.list(ObserverRunner.TASK, owner.pubKey).isEmpty())
            assertEquals(emptyList(), pending.owners(ObserverRunner.TASK))
        }

    @Test
    fun theRetryLoopPublishesNothingOnceTheOwnerTookPrintingBack() =
        runTest {
            world()
            schedule()
            val service = service()
            blossom.down = true
            service.wake(wake())
            service.idle()
            blossom.down = false
            // The owner's newest version names no runner.
            schedule(ScheduledTask(task().publicTags.filterNot { it[0] == "p" }.toTypedArray(), task().privateTags), createdAt = now)
            clock += 600
            val asked = blossom.requests.size
            service.republishPending()
            service.idle()
            assertTrue(relays.published.isEmpty())
            assertEquals(asked, blossom.requests.size, "a paper went to Blossom")
            // Kept for its 12 hours, in case they name the Assistant again; dropped after.
            assertEquals(1, pending.list(ObserverRunner.TASK, owner.pubKey).size)
            clock += PendingPaper.RETRY_SECONDS
            service.republishPending()
            assertTrue(pending.list(ObserverRunner.TASK, owner.pubKey).isEmpty())
        }

    @Test
    fun theRetryLoopDropsThePapersOfAnOwnerWithNoAssistant() =
        runTest {
            world()
            schedule()
            val gone = MemoryPendingStore()
            val html = "<!doctype html><html><body><h1>Yesterday</h1></body></html>"
            val nobody = newSigner().pubKey
            gone.put(
                PendingPaper(
                    nobody,
                    six,
                    "2026-11-12",
                    "",
                    six,
                    "m",
                    1,
                    1,
                    "Yesterday",
                    sha256Hex(html.encodeToByteArray()),
                    html,
                    "x",
                    now + 3_600,
                ).toPending(),
            )
            service(pending = gone).run {
                republishPending()
                idle()
            }
            assertTrue(gone.list(ObserverRunner.TASK, nobody).isEmpty())
            assertTrue(relays.published.isEmpty())
        }

    @Test
    fun noKeyPlaintextOrCiphertextReachesTheLogs() =
        runTest {
            world()
            val event = schedule()
            val keyList = relays.held[assistantRelay]!!.first { it.kind == 12000 }
            val service = service()
            val (_, logs) =
                capturingLogs {
                    service.wake(wake())
                    yield()
                    service.idle()
                    service.wake(wake())
                }
            assertTrue("owner=${owner.pubKey}" in logs)
            assertTrue("PRINTED" in logs)
            val nsec = assistant.keyPair.privKey!!.joinToString("") { "%02x".format(it) }
            for (secret in listOf(openRouter.key, secondOpenRouter.key, anthropic.key, nsec, event.content, keyList.content)) {
                assertFalse(secret in logs, "a key, a plaintext or a ciphertext reached the log")
            }
            assertFalse("anthropic/claude-sonnet-4.5" in logs, "the schedule's plaintext reached the log")
            assertFalse("Europe/London" in logs, "the schedule's plaintext reached the log")
        }
}
