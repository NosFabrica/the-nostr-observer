package com.nosfabrica.observer.runner

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip5aStaticWebsites.observer.ObserverSite
import com.vitorpamplona.quartz.nipFAAgentKeys.tags.AgentKeyTag
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTask
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTaskClaims.OwnRun
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTaskEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How long anything can hold the worker, what survives a halt, and which reads are trusted. */
class RunnerBoundsTest : RunnerWorld() {
    /** A second owner (the stranger) with their own Assistant, lists and schedule, beside the owner's. */
    private suspend fun secondOwner() {
        relays.put(index, strangersAssistant.signed(10002, arrayOf(arrayOf("r", assistantRelay.url)), createdAt = 1))
        relays.put(
            ownerRelay,
            stranger.signed(10063, arrayOf(arrayOf("server", "https://blossom.owner.example")), createdAt = 1),
            stranger.signed(10040, arrayOf(arrayOf("30382:rank", "b".repeat(64), "wss://nip85.example")), createdAt = 1),
        )
        relays.put(assistantRelay, agentKeyList(strangersAssistant, listOf(openRouter), createdAt = 1))
        val strangers =
            ScheduledTask
                .create("observer", "observer", "0 6 * * *", "Europe/London", setAt = 0)
                .withRunner(strangersAssistant.pubKey, 0)
                .withParam("model", listOf("m"))
        relays.put(ownerRelay, typed(stranger.sign(ScheduledTaskEvent.update(strangers, stranger, "observer", createdAt = now - 3_600))))
    }

    @Test
    fun aRelayThatNeverAnswersDoesNotHoldThePoll() =
        runTest {
            world()
            schedule()
            // The owner's relay takes every REQ and never ends one: no EOSE, no CLOSED, no error.
            val hanging =
                object : RelayAccess by relays {
                    override suspend fun fetch(
                        relays: Set<NormalizedRelayUrl>,
                        filter: Filter,
                    ): RelayRead = if (ownerRelay in relays) awaitCancellation() else this@RunnerBoundsTest.relays.fetch(relays, filter)
                }
            val outcomes = runnerWith(access = hanging).poll()
            // It answered for nobody: no quorum on the owner's relays, nothing spent.
            assertEquals(RunOutcome.NO_QUORUM, outcomes[owner.pubKey])
            assertTrue(printer.calls.isEmpty())
        }

    @Test
    fun aTokenRefusedForOneOwnerLetsTheOthersRunningPrintFinishAndKeepTheirPaper() =
        runTest {
            world()
            schedule()
            secondOwner()
            val printing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            // The owner's print is under way (it may have paid) when the server refuses the token for the stranger.
            val slow =
                PaperPrinter { key, model, who, name ->
                    printing.complete(Unit)
                    release.await()
                    printer.print(key, model, who, name)
                }
            val refusing =
                object : InternalApi by api {
                    override suspend fun decrypt(
                        owner: HexKey,
                        event: Event,
                        schedule: Event?,
                    ): String {
                        if (owner == stranger.pubKey) {
                            printing.await()
                            release.complete(Unit)
                            throw RunnerTokenRejectedException()
                        }
                        return api.decrypt(owner, event, schedule)
                    }
                }
            val dir = Files.createTempDirectory("pending").toFile()
            blossom.down = true
            val worker = runnerWith(printer = slow, api = refusing, pending = PendingPapers(dir))
            assertFailsWith<RunnerTokenRejectedException> { worker.poll() }
            // The print finished, is ended (never paid for twice), and its paper is kept on disk.
            assertEquals(1, printer.calls.size)
            assertEquals(OwnRun.ENDED, records.ownRun(owner.pubKey, six))
            assertEquals(listOf(six), PendingPapers(dir).of(owner.pubKey).map { it.occurrence })
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
            val dir = Files.createTempDirectory("pending").toFile()
            assertNull(withTimeoutOrNull(60_000) { runnerWith(access = stuck, pending = PendingPapers(dir)).poll() })
            assertEquals(1, printer.calls.size)
            // A restart finds it, and publishes it without printing again.
            val restarted = PendingPapers(dir)
            assertEquals(listOf(six), restarted.of(owner.pubKey).map { it.occurrence })
            runnerWith(pending = restarted).poll()
            assertEquals(1, printer.calls.size)
            assertTrue(relays.published.any { ObserverSite.isRunnerSiteFor(it.first, assistant.pubKey, owner.pubKey) })
            assertTrue(restarted.of(owner.pubKey).isEmpty())
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
            val pending = PendingPapers(null)
            pending.keep(yesterday)
            val worker = runnerWith(pending = pending)
            blossom.down = true
            assertEquals(RunOutcome.PUBLISH_PENDING, worker.poll()[owner.pubKey])
            // Today's paper is kept beside it, not in its place.
            assertEquals(listOf(six - 86_400, six), pending.of(owner.pubKey).map { it.occurrence })
            // Back up: both go out, in one site.
            blossom.down = false
            clock += 600
            worker.poll()
            assertTrue(pending.of(owner.pubKey).isEmpty())
            val site = relays.published.last().first
            val paths = site.tags.filter { it.size > 1 && it[0] == "path" }.map { it[1] }
            assertTrue("/2026-11-11.html" in paths && "/2026-11-12.html" in paths, paths.toString())
            assertEquals(1, printer.calls.size)
        }

    @Test
    fun aRunWaitingForAPermitWhenTheWorkerStopsStartsNothing() =
        runTest {
            world()
            schedule()
            secondOwner()
            var stopping = false
            // One run at a time; SIGTERM arrives during the first print.
            val stopper =
                PaperPrinter { key, model, who, name ->
                    stopping = true
                    printer.print(key, model, who, name)
                }
            val outcomes = runnerWith(printer = stopper, accepting = { !stopping }, parallelism = 1).poll()
            assertEquals(1, printer.calls.size)
            val waited = if (printer.calls.single().third == owner.pubKey) stranger.pubKey else owner.pubKey
            assertEquals(RunOutcome.STOPPING, outcomes[waited])
            // Not even its schedule was decrypted.
            assertTrue(api.decrypted.none { it.first == waited })
        }

    @Test
    fun aModelsListingThatNeverAnswersEndsTheRun() =
        runTest {
            world()
            schedule(task(model = null, key = null))
            val outcome = runnerWith(models = { awaitCancellation() }).poll()[owner.pubKey]
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
            assertEquals(RunOutcome.NO_QUORUM, runner.poll()[owner.pubKey])
            assertTrue(printer.calls.isEmpty())
            relays.down = emptySet()
            assertEquals(RunOutcome.PRINTED, runner.poll()[owner.pubKey])
        }

    @Test
    fun aSettledKeyOutcomeIsLookedAtAgainWhenTheAssistantsKeysChange() =
        runTest {
            world()
            val elsewhere = AgentKeyTag("sk-other-9999", "https://api.example.com/v1")
            schedule(task(key = elsewhere))
            assertEquals(RunOutcome.KEY_NOT_GIVEN, runner.poll()[owner.pubKey])
            // The owner gives the Assistant that key: a new kind 12000.
            relays.put(assistantRelay, agentKeyList(assistant, listOf(openRouter, elsewhere), createdAt = 2))
            assertEquals(RunOutcome.PRINTED, runner.poll()[owner.pubKey])
            assertEquals(elsewhere, printer.calls.single().first)
        }

    @Test
    fun aRelayThatCutsAPageShortIsNotTakenAsHavingAnsweredForEveryone() =
        runTest {
            world()
            schedule()
            // Two more owners whose schedules are on the same relay, written later than the owner's.
            val others = listOf(newSigner(), newSigner())
            val assistants = others.map { newSigner() }
            others.zip(assistants).forEachIndexed { i, (who, theirs) ->
                relays.put(index, who.signed(10002, arrayOf(arrayOf("r", ownerRelay.url, "write")), createdAt = 1))
                val t =
                    ScheduledTask
                        .create("observer", "observer", "0 6 * * *", "Europe/London", setAt = 0)
                        .withRunner(theirs.pubKey, 0)
                relays.put(ownerRelay, typed(who.sign(ScheduledTaskEvent.update(t, who, "observer", createdAt = now - 60 + i))))
            }
            // The owner took printing back on their relay; the index still has the version naming the Assistant.
            schedule(
                ScheduledTask(task().publicTags.filterNot { it[0] == "p" }.toTypedArray(), task().privateTags),
                onIndex = false,
                createdAt = now - 1_800,
            )
            // The owners' relay sends two events for a REQ with no limit, and says nothing about it.
            relays.defaultLimit = { if (it == ownerRelay) 2 else null }
            val nostr = RunnerNostr(relays, setOf(index), null, now = { clock })
            val reads = nostr.schedules(listOf(owner.pubKey) + others.map { it.pubKey })
            val mine = reads.single { it.event.pubKey == owner.pubKey }
            // The newest is the one without the Assistant, read in full.
            assertTrue(ScheduledTask(mine.event.tags).runners.isEmpty())
            assertTrue(mine.quorum)
        }

    @Test
    fun aRelayThatClosesABigBatchIsAskedAgainInSmallerOnes() =
        runTest {
            world()
            schedule()
            relays.refuses = { relay, filter -> relay == ownerRelay && filter.authors.orEmpty().size > 1 }
            val nostr = RunnerNostr(relays, setOf(index), null, now = { clock }, authorsPerReq = 4, minChunk = 1)
            val read = nostr.schedules(listOf(owner.pubKey, stranger.pubKey)).single { it.event.pubKey == owner.pubKey }
            assertTrue(read.quorum)
            assertTrue(relays.asked.any { (to, f) -> to == setOf(ownerRelay) && f.authors == listOf(owner.pubKey) && f.limit == 1 })
        }
}
