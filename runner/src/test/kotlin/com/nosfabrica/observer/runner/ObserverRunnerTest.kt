package com.nosfabrica.observer.runner

import com.nosfabrica.observer.frontpage.WriterException
import com.nosfabrica.observer.frontpage.WriterFailure
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip5aStaticWebsites.observer.ObserverEdition
import com.vitorpamplona.quartz.nip5aStaticWebsites.observer.ObserverSite
import com.vitorpamplona.quartz.nipFAAgentKeys.tags.AgentKeyTag
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTask
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTaskClaims.OwnRun
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTaskEvent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ObserverRunnerTest {
    private val owner = newSigner()
    private val assistant = newSigner()
    private val stranger = newSigner()
    private val strangersAssistant = newSigner()

    private val index = relay("wss://index.example")
    private val ownerRelay = relay("wss://owner.example")
    private val assistantRelay = relay("wss://assistant.example")

    // 06:30 UTC on 12 November 2026: London's 06:00 run is due, and today is 2026-11-12.
    private val now = Instant.parse("2026-11-12T06:30:00Z").epochSecond
    private val day = "2026-11-12"

    private val openRouter = AgentKeyTag("sk-or-v1-first-key-0001", "https://openrouter.ai/api/v1")
    private val secondOpenRouter = AgentKeyTag("sk-or-v1-second-key-a3f9", "https://openrouter.ai/api/v1")
    private val anthropic = AgentKeyTag("sk-ant-api03-third-key-77zz", "https://api.anthropic.com/v1")

    private val relays = FakeRelays()
    private val blossom = FakeBlossom()
    private val printer = FakePrinter()
    private val records = FileRunRecords(null)
    private val api = FakeInternalApi(mapOf(owner.pubKey to assistant, stranger.pubKey to strangersAssistant))
    private var listedModels = listed("openai/gpt-4o-mini", "anthropic/claude-sonnet-4.5", "text-embedding-3")

    private val runner =
        ObserverRunner(
            api = api,
            nostr = RunnerNostr(relays, setOf(index), null),
            blossom = BlossomHttp(blossom.client),
            printer = printer,
            records = records,
            models = { listedModels },
            now = { now },
        )

    private suspend fun world(keys: List<AgentKeyTag> = listOf(openRouter, secondOpenRouter, anthropic)) {
        relays.put(
            index,
            owner.signed(10002, arrayOf(arrayOf("r", ownerRelay.url, "write")), createdAt = 1),
            assistant.signed(10002, arrayOf(arrayOf("r", assistantRelay.url)), createdAt = 1),
            stranger.signed(10002, arrayOf(arrayOf("r", ownerRelay.url, "write")), createdAt = 1),
        )
        relays.put(
            ownerRelay,
            owner.signed(
                10063,
                arrayOf(arrayOf("server", "https://blossom.owner.example"), arrayOf("server", "https://media.owner.example")),
                createdAt = 1,
            ),
            owner.signed(10040, arrayOf(arrayOf("30382:rank", "b".repeat(64), "wss://nip85.example")), createdAt = 1),
            owner.signed(0, emptyArray(), """{"name":"Vitor"}""", createdAt = 1),
        )
        // The Assistant's own Blossom list must never be used: uploads go to the owner's servers.
        relays.put(
            assistantRelay,
            assistant.signed(10063, arrayOf(arrayOf("server", "https://assistant-own.example")), createdAt = 1),
            agentKeyList(assistant, keys, createdAt = 1),
        )
    }

    private fun task(
        model: String? = "anthropic/claude-sonnet-4.5",
        key: AgentKeyTag? = secondOpenRouter,
    ): ScheduledTask {
        var t =
            ScheduledTask
                .create("observer", "observer", "0 6 * * *", "Europe/London", setAt = 0)
                .withRunner(assistant.pubKey)
        if (model != null) t = t.withParam("model", listOf(model))
        if (key != null) t = t.withParam("key", listOf(key.url, key.key.takeLast(4)))
        return t
    }

    private suspend fun schedule(
        task: ScheduledTask = task(),
        by: NostrSignerInternal = owner,
    ): Event {
        val template = ScheduledTaskEvent.update(task, by, "observer", createdAt = now - 3_600)
        val event = typed(by.sign(template))
        relays.put(ownerRelay, event)
        return event
    }

    @Test
    fun printsWithTheParamsAndPublishesTheAssistantsSiteForTheOwner() =
        runTest {
            world()
            schedule()
            assertEquals(mapOf(owner.pubKey to RunOutcome.PRINTED), runner.poll())

            // `key` = URL + last 4 picks the second OpenRouter key, `model` the model.
            assertEquals(listOf(Triple(secondOpenRouter, "anthropic/claude-sonnet-4.5", owner.pubKey)), printer.calls)

            val (site, to) = relays.published.single()
            assertEquals(setOf(assistantRelay), to)
            assertEquals(35128, site.kind)
            assertEquals(assistant.pubKey, site.pubKey)
            assertTrue(site.verify())
            assertTrue(ObserverSite.isRunnerSiteFor(site, assistant.pubKey, owner.pubKey))
            // What went out is what the server signed (its client tag in), never what was sent.
            assertTrue(site.tags.any { it.size > 1 && it[0] == "client" && it[1] == "Brainstorm" })
            assertTrue(site.tags.any { it.size > 2 && it[0] == "path" && it[1] == "/$day.html" })
            assertEquals("Vitor's Observer", site.tags.first { it[0] == "title" }[1])
            assertEquals(listOf(35128), api.signedKinds.filter { it == 35128 })
        }

    @Test
    fun anInterruptedRunIsNeverPrintedAgain() =
        runTest {
            world()
            schedule()
            // A run that started and never ended (the worker died mid-print): it may have reached
            // the model, so the next poll doesn't risk paying for the same morning twice.
            records.started(owner.pubKey, Instant.parse("2026-11-12T06:00:00Z").epochSecond)
            assertEquals(OwnRun.ENDED, records.ownRun(owner.pubKey, Instant.parse("2026-11-12T06:00:00Z").epochSecond))
            assertEquals(RunOutcome.ATTEMPTED, runner.poll()[owner.pubKey])
            assertTrue(printer.calls.isEmpty())
            assertTrue(relays.published.isEmpty())
        }

    @Test
    fun uploadsGoOnlyToTheOwnersBlossomServers() =
        runTest {
            world()
            schedule()
            runner.poll()
            assertEquals(setOf("blossom.owner.example", "media.owner.example"), blossom.hostsWritten())
            assertFalse(blossom.requests.any { "assistant-own.example" in it })
            // One 24242 per file (the page, the archive, editions.json), each signed through the server.
            assertEquals(3, api.signedKinds.count { it == 24242 })
        }

    @Test
    fun noBlossomServerOfTheOwnersMeansNoPrint() =
        runTest {
            world()
            relays.held[ownerRelay]!!.removeAll { it.kind == 10063 }
            schedule()
            assertEquals(RunOutcome.NO_SERVERS, runner.poll()[owner.pubKey])
            assertTrue(printer.calls.isEmpty())
            assertTrue(blossom.requests.isEmpty())
        }

    @Test
    fun aScheduleByAnyoneButTheOwnerIsRejected() =
        runTest {
            world()
            // A stranger (who has an Assistant of their own) names the owner's Assistant.
            schedule(task(), by = stranger)
            assertEquals(RunOutcome.REJECTED, runner.poll()[stranger.pubKey])
            assertTrue(api.decrypted.isEmpty())
            assertTrue(printer.calls.isEmpty())
            assertTrue(relays.published.isEmpty())
        }

    @Test
    fun aScheduleBySomeoneWithNoAssistantIsRejected() =
        runTest {
            world()
            val nobody = newSigner()
            relays.put(index, nobody.signed(10002, arrayOf(arrayOf("r", ownerRelay.url, "write")), createdAt = 1))
            schedule(task(), by = nobody)
            // The schedules are read by the owners we serve; this one isn't, and even if a relay
            // returned it, it would be rejected.
            val outcome = runner.poll()[nobody.pubKey]
            assertTrue(outcome == null || outcome == RunOutcome.REJECTED)
            assertTrue(printer.calls.isEmpty())
        }

    @Test
    fun twoPTagsMeansNothingRuns() =
        runTest {
            world()
            val both = ScheduledTask(task().publicTags + arrayOf(arrayOf("p", strangersAssistant.pubKey)), task().privateTags)
            schedule(both)
            assertEquals(RunOutcome.RUNNER_CONFLICT, runner.poll()[owner.pubKey])
            assertTrue(api.decrypted.isEmpty())
            assertTrue(printer.calls.isEmpty())
        }

    @Test
    fun alreadyPrintedOnTheOwnersSiteMeansNoPrint() =
        runTest {
            world()
            schedule()
            val edition = ObserverEdition(day = day, path = "/$day.html", sha256 = "c".repeat(64))
            val template =
                ObserverSite.build(
                    listOf(edition),
                    "d".repeat(64),
                    "e".repeat(64),
                    "Vitor",
                    listOf("https://blossom.owner.example"),
                    now - 600,
                )
            relays.put(ownerRelay, typed(owner.sign(template)))
            assertEquals(RunOutcome.ALREADY_PRINTED, runner.poll()[owner.pubKey])
            assertTrue(printer.calls.isEmpty())
        }

    @Test
    fun alreadyPrintedOnTheAssistantsSiteMeansNoPrint() =
        runTest {
            world()
            schedule()
            val edition = ObserverEdition(day = day, path = "/$day.html", sha256 = "c".repeat(64), printedAt = now - 900)
            val template =
                ObserverSite.build(
                    listOf(edition),
                    "d".repeat(64),
                    "e".repeat(64),
                    "Vitor",
                    listOf("https://blossom.owner.example"),
                    now - 600,
                    forReader = owner.pubKey,
                )
            relays.put(assistantRelay, typed(assistant.sign(template)))
            assertEquals(RunOutcome.ALREADY_PRINTED, runner.poll()[owner.pubKey])
            assertTrue(printer.calls.isEmpty())
        }

    @Test
    fun aSiteOfAnotherKeyTaggingTheOwnerDoesNotCount() =
        runTest {
            world()
            schedule()
            val edition = ObserverEdition(day = day, path = "/$day.html", sha256 = "c".repeat(64), printedAt = now - 900)
            val template =
                ObserverSite.build(
                    listOf(edition),
                    "d".repeat(64),
                    "e".repeat(64),
                    "Vitor",
                    emptyList(),
                    now - 600,
                    forReader = owner.pubKey,
                )
            relays.put(assistantRelay, typed(stranger.sign(template)))
            assertEquals(RunOutcome.PRINTED, runner.poll()[owner.pubKey])
        }

    @Test
    fun yesterdaysEditionDoesNotCoverThisMorning() =
        runTest {
            world()
            schedule()
            val edition = ObserverEdition(day = "2026-11-11", path = "/2026-11-11.html", sha256 = "c".repeat(64), printedAt = now - 86_400)
            val template =
                ObserverSite.build(
                    listOf(edition),
                    "d".repeat(64),
                    "e".repeat(64),
                    "Vitor",
                    emptyList(),
                    now - 80_000,
                    forReader = owner.pubKey,
                )
            relays.put(assistantRelay, typed(assistant.sign(template)))
            assertEquals(RunOutcome.PRINTED, runner.poll()[owner.pubKey])
        }

    @Test
    fun keyParamWithAnUnknownSuffixTakesTheFirstKeyAtThatUrl() =
        runTest {
            world()
            schedule(task(key = AgentKeyTag("sk-ant-zzzz-nope", "https://api.anthropic.com/v1/")))
            runner.poll()
            assertEquals(anthropic, printer.calls.single().first)
        }

    @Test
    fun withoutParamsTheDefaultKeyAndTheBestListedModelPrint() =
        runTest {
            world()
            schedule(task(model = null, key = null))
            runner.poll()
            val (key, model, _) = printer.calls.single()
            assertEquals(openRouter, key)
            assertEquals("anthropic/claude-sonnet-4.5", model)
        }

    @Test
    fun anUnknownParamIsNotRun() =
        runTest {
            world()
            schedule(task().withParam("size", listOf("broadsheet")))
            assertEquals(RunOutcome.UNKNOWN_PARAMS, runner.poll()[owner.pubKey])
            assertTrue(printer.calls.isEmpty())
        }

    @Test
    fun aPausedScheduleIsNotRun() =
        runTest {
            world()
            schedule(task().withEnabled(false, now))
            assertEquals(RunOutcome.NOT_RUNNABLE, runner.poll()[owner.pubKey])
            assertTrue(printer.calls.isEmpty())
        }

    @Test
    fun anInternalKeyUrlIsRefusedAndNoOtherKeyIsTried() =
        runTest {
            for (url in listOf(
                "http://localhost:11434/v1",
                "https://10.0.0.5/v1",
                "https://ai.internal/v1",
                "https://[::1]/v1",
                "https://169.254.169.254/latest",
            )) {
                val local = AgentKeyTag("", url)
                relays.held.clear()
                printer.calls.clear()
                world(listOf(local, openRouter))
                schedule(task(key = local))
                assertEquals(RunOutcome.REFUSED_URL, runner.poll()[owner.pubKey], url)
                assertTrue(printer.calls.isEmpty(), url)
            }
        }

    @Test
    fun aRunThatReachedTheModelIsNotRunAgainButOneThatSpentNothingIs() =
        runTest {
            world()
            schedule()
            printer.result = { _, _ -> FakePrinter.notReady() }
            assertEquals(RunOutcome.NOT_READY, runner.poll()[owner.pubKey])
            printer.result = FakePrinter.failing(WriterException(WriterFailure.RATE_LIMITED, 429))
            assertEquals(RunOutcome.WRITER_FAILED, runner.poll()[owner.pubKey])
            assertEquals(RunOutcome.ATTEMPTED, runner.poll()[owner.pubKey])
            assertEquals(2, printer.calls.size)
        }

    @Test
    fun anOwnerTheServerNoLongerHasIsAnOutcome() =
        runTest {
            world()
            schedule()
            val gone =
                ObserverRunner(
                    api =
                        object : InternalApi by api {
                            override suspend fun decrypt(
                                owner: HexKey,
                                event: Event,
                            ): String = throw NoAssistantException(owner)
                        },
                    nostr = RunnerNostr(relays, setOf(index), null),
                    blossom = BlossomHttp(blossom.client),
                    printer = printer,
                    records = records,
                    models = { listedModels },
                    now = { now },
                )
            assertEquals(RunOutcome.NO_ASSISTANT, gone.poll()[owner.pubKey])
            assertTrue(printer.calls.isEmpty())
        }

    @Test
    fun neitherTheKeyNorAPlaintextNorACiphertextIsLogged() =
        runTest {
            world()
            val event = schedule()
            val keyList = relays.held[assistantRelay]!!.first { it.kind == 12000 }
            val (_, printedLogs) = capturingLogs { runner.poll() }

            // Fresh state, and a provider that fails while echoing the key back.
            records.forget(owner.pubKey, Instant.parse("2026-11-12T06:00:00Z").epochSecond)
            relays.published.clear()
            relays.held[assistantRelay]!!.removeAll { it.kind == 35128 }
            printer.result = FakePrinter.failing(WriterException(WriterFailure.REJECTED, 401, "invalid key ${secondOpenRouter.key}"))
            val (_, failedLogs) = capturingLogs { runner.poll() }

            for (logs in listOf(printedLogs, failedLogs)) {
                assertTrue(logs.contains(owner.pubKey), "the owner and the outcome are logged")
                for (secret in listOf(
                    openRouter.key,
                    secondOpenRouter.key,
                    anthropic.key,
                    secondOpenRouter.key.takeLast(4).let { "\"$it\"" },
                )) {
                    assertFalse(logs.contains(secret), "a key reached the log")
                }
                assertFalse(logs.contains("anthropic/claude-sonnet-4.5"), "the schedule's plaintext reached the log")
                assertFalse(logs.contains("Europe/London"), "the schedule's plaintext reached the log")
                assertFalse(logs.contains(event.content), "a ciphertext reached the log")
                assertFalse(logs.contains(keyList.content), "a ciphertext reached the log")
            }
            assertTrue(failedLogs.contains("WRITER_FAILED"))
        }
}
