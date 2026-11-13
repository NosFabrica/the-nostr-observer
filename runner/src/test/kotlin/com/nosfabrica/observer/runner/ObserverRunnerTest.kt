package com.nosfabrica.observer.runner

import com.nosfabrica.observer.frontpage.WriterException
import com.nosfabrica.observer.frontpage.WriterFailure
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip5aStaticWebsites.observer.ObserverEdition
import com.vitorpamplona.quartz.nip5aStaticWebsites.observer.ObserverSite
import com.vitorpamplona.quartz.nipFAAgentKeys.tags.AgentKeyTag
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTask
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTaskClaims.OwnRun
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTaskEvent
import com.vitorpamplona.quartz.utils.EventFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ObserverRunnerTest : RunnerWorld() {
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
                                schedule: Event?,
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
            // A new worker: this one remembers the morning is settled.
            val (_, failedLogs) = capturingLogs { runnerWith().poll() }

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

    @Test
    fun aPrintThatNeverEndsIsStoppedAndNotRunAgain() =
        runTest {
            world()
            schedule()
            val hanging = PaperPrinter { _, _, _, _ -> awaitCancellation() }
            assertEquals(RunOutcome.TIMED_OUT, runnerWith(printer = hanging).poll()[owner.pubKey])
            // It may have reached the model: never again on its own.
            assertEquals(OwnRun.ENDED, records.ownRun(owner.pubKey, Instant.parse("2026-11-12T06:00:00Z").epochSecond))
            assertEquals(RunOutcome.ATTEMPTED, runner.poll()[owner.pubKey])
            assertTrue(relays.published.isEmpty())
        }

    @Test
    fun oneSlowReaderDoesNotHoldUpTheOthers() =
        runTest {
            world()
            schedule()
            // A second owner with their own Assistant, schedule and lists.
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
            relays.put(
                ownerRelay,
                typed(stranger.sign(ScheduledTaskEvent.update(strangers, stranger, "observer", createdAt = now - 3_600))),
            )

            // The owner's print waits until the stranger's is done: run one after the other, it never would.
            val strangerDone = CompletableDeferred<Unit>()
            val gated =
                PaperPrinter { key, model, who, name ->
                    if (who == owner.pubKey) {
                        strangerDone.await()
                    } else {
                        strangerDone.complete(Unit)
                    }
                    printer.print(key, model, who, name)
                }
            val outcomes = runnerWith(printer = gated).poll()
            assertEquals(RunOutcome.PRINTED, outcomes[owner.pubKey])
            assertEquals(RunOutcome.PRINTED, outcomes[stranger.pubKey])
        }

    @Test
    fun aRecordThatCantBeKeptMeansNoPrint() =
        runTest {
            world()
            schedule()
            val broken =
                object : RunRecords by records {
                    override fun started(
                        owner: HexKey,
                        occurrence: Long,
                    ) = throw RunRecordsException("disk full")
                }
            assertEquals(RunOutcome.RECORDS_UNAVAILABLE, runnerWith(records = broken).poll()[owner.pubKey])
            assertTrue(printer.calls.isEmpty())
        }

    @Test
    fun aStoppingWorkerStartsNoPrint() =
        runTest {
            world()
            schedule()
            assertEquals(RunOutcome.STOPPING, runnerWith(accepting = { false }).poll()[owner.pubKey])
            assertTrue(printer.calls.isEmpty())
            assertEquals(OwnRun.NONE, records.ownRun(owner.pubKey, Instant.parse("2026-11-12T06:00:00Z").epochSecond))
        }

    @Test
    fun takingPrintingBackStopsTheWorkerEvenWhereTheOlderVersionLingers() =
        runTest {
            world()
            // The index still holds the version naming the Assistant; the owner's relay has the newer one without.
            schedule(createdAt = now - 7_200)
            schedule(
                task().let {
                    ScheduledTask(
                        it.publicTags
                            .filterNot { t ->
                                t[0] == "p"
                            }.toTypedArray(),
                        it.privateTags,
                    )
                },
                onIndex = false,
            )
            assertEquals(RunOutcome.NOT_NAMED, runner.poll()[owner.pubKey])
            assertTrue(printer.calls.isEmpty())
            assertTrue(api.decrypted.isEmpty())
        }

    @Test
    fun tooFewOfTheOwnersRelaysAnsweringMeansNoPrint() =
        runTest {
            world()
            val second = relay("wss://owner-two.example")
            relays.put(
                index,
                owner.signed(10002, arrayOf(arrayOf("r", ownerRelay.url, "write"), arrayOf("r", second.url, "write")), createdAt = 2),
            )
            schedule()
            // One of two answers: not more than half. The newer version may be on the silent one.
            relays.down = setOf(second)
            assertEquals(RunOutcome.NO_QUORUM, runner.poll()[owner.pubKey])
            assertTrue(printer.calls.isEmpty())
        }

    @Test
    fun aScheduleChangedRightBeforeSpendingIsNotPrinted() =
        runTest {
            world()
            schedule()
            var changed = false
            // The owner edits the schedule while the worker reads their profile, the last read before the print.
            val editing =
                object : RelayAccess by relays {
                    override suspend fun fetch(
                        relays: Set<NormalizedRelayUrl>,
                        filter: Filter,
                    ): RelayRead {
                        if (!changed && filter.kinds == listOf(0)) {
                            changed = true
                            schedule(task(model = "openai/gpt-4o-mini"), createdAt = now - 60)
                        }
                        return this@ObserverRunnerTest.relays.fetch(relays, filter)
                    }
                }
            val r =
                ObserverRunner(api, RunnerNostr(editing, setOf(index), null), BlossomHttp(blossom.client), printer, records, {
                    listedModels
                }, now = { now })
            assertEquals(RunOutcome.SCHEDULE_CHANGED, r.poll()[owner.pubKey])
            assertTrue(printer.calls.isEmpty())
            assertEquals(OwnRun.NONE, records.ownRun(owner.pubKey, six))
            // The next poll decides on the new version.
            assertEquals(RunOutcome.PRINTED, r.poll()[owner.pubKey])
            assertEquals("openai/gpt-4o-mini", printer.calls.single().second)
        }

    @Test
    fun aForgedOrFarFutureSiteIsNotRead() =
        runTest {
            world()
            schedule()
            val edition = ObserverEdition(day = day, path = "/$day.html", sha256 = "c".repeat(64), printedAt = now - 900)
            // The Assistant's own key, but dated a month ahead: it would win every newest-of read.
            val ahead =
                ObserverSite.build(
                    listOf(edition),
                    "d".repeat(64),
                    "e".repeat(64),
                    "Vitor",
                    listOf("https://blossom.owner.example"),
                    now + 30 * 86_400,
                    forReader = owner.pubKey,
                )
            relays.put(assistantRelay, typed(assistant.sign(ahead)))
            // And a site claiming to be the owner's, signed by someone else.
            val real =
                typed(
                    stranger.sign(
                        ObserverSite.build(
                            listOf(edition),
                            "d".repeat(64),
                            "e".repeat(64),
                            "Vitor",
                            listOf("https://blossom.owner.example"),
                            now - 600,
                        ),
                    ),
                )
            val forged = EventFactory.create<Event>(real.id, owner.pubKey, real.createdAt, real.kind, real.tags, real.content, real.sig)
            relays.put(ownerRelay, forged)
            assertEquals(RunOutcome.PRINTED, runner.poll()[owner.pubKey])
        }

    @Test
    fun theAssistantsRelaysDownIsUnreadableEvenWhenTheIndexAnswers() =
        runTest {
            world()
            schedule()
            relays.down = setOf(assistantRelay)
            assertEquals(RunOutcome.SITE_UNREADABLE, runner.poll()[owner.pubKey])
            assertTrue(printer.calls.isEmpty())
        }

    @Test
    fun aSettledMorningIsNotReadAgainEveryPoll() =
        runTest {
            world()
            schedule()
            val edition = ObserverEdition(day = day, path = "/$day.html", sha256 = "c".repeat(64), printedAt = now - 900)
            relays.put(
                ownerRelay,
                typed(
                    owner.sign(
                        ObserverSite.build(
                            listOf(edition),
                            "d".repeat(64),
                            "e".repeat(64),
                            "Vitor",
                            listOf("https://blossom.owner.example"),
                            now - 600,
                        ),
                    ),
                ),
            )
            assertEquals(RunOutcome.ALREADY_PRINTED, runner.poll()[owner.pubKey])
            val siteReads = relays.asked.count { it.second.kinds == listOf(35128) }
            val relayListReads = relays.asked.count { it.second.kinds == listOf(10002) }
            assertEquals(RunOutcome.ALREADY_PRINTED, runner.poll()[owner.pubKey])
            assertEquals(siteReads, relays.asked.count { it.second.kinds == listOf(35128) }, "the sites were read again")
            assertEquals(relayListReads, relays.asked.count { it.second.kinds == listOf(10002) }, "the relay lists were read again")
            // Decrypted once: the second poll used the plaintext it had.
            assertEquals(1, api.decrypted.count { it.second == 34817 })
        }

    @Test
    fun aPaperThatCouldNotBePublishedIsPublishedLaterAndNeverPrintedAgain() =
        runTest {
            world()
            schedule()
            blossom.down = true
            assertEquals(RunOutcome.PUBLISH_PENDING, runner.poll()[owner.pubKey])
            assertTrue(relays.published.isEmpty())
            blossom.down = false
            runner.poll()
            assertEquals(1, printer.calls.size, "printed once")
            val site = relays.published.single().first
            assertTrue(ObserverSite.isRunnerSiteFor(site, assistant.pubKey, owner.pubKey))
            // And dated by the occurrence it is for, never by when it was printed or published.
            val bytes = blossom.stored.values.first { String(it).contains("\"editions\"") }
            assertTrue(String(bytes).contains("\"printedAt\":$six"), String(bytes))
        }

    @Test
    fun aKeyParamTheAssistantLacksWithAModelFailsAndIsNotRetried() =
        runTest {
            world()
            schedule(task(key = AgentKeyTag("sk-other-9999", "https://api.example.com/v1")))
            assertEquals(RunOutcome.KEY_NOT_GIVEN, runner.poll()[owner.pubKey])
            assertTrue(printer.calls.isEmpty())
            val keyReads = api.decrypted.count { it.second == 12000 }
            assertEquals(RunOutcome.KEY_NOT_GIVEN, runner.poll()[owner.pubKey])
            assertEquals(keyReads, api.decrypted.count { it.second == 12000 })
        }
}
