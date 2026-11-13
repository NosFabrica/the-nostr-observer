package com.nosfabrica.observer.runner

import com.nosfabrica.observer.frontpage.AgentModel
import com.nosfabrica.observer.frontpage.RoutstrInfo
import com.nosfabrica.observer.frontpage.SatsPricing
import com.nosfabrica.observer.frontpage.SingleHttpClient
import com.nosfabrica.observer.frontpage.WriterException
import com.nosfabrica.observer.frontpage.WriterFailure
import com.nosfabrica.observer.frontpage.WritingEffort
import com.nosfabrica.observer.frontpage.writerExceptionFor
import com.vitorpamplona.quartz.nip5aStaticWebsites.observer.ObserverParams
import com.vitorpamplona.quartz.nipFAAgentKeys.tags.AgentKeyTag
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTaskClaims.OwnRun
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A Routstr key in the worker: its node's balance, its errors, onion nodes, and a key the Assistant lacks. Every node is faked. */
class RoutstrRunnerTest : RunnerWorld() {
    private val nodeKey = AgentKeyTag("sk-routstr-node-key-7f3a", "https://node.example/v1")
    private val model = "routstr/writer"

    /** 300 sats reserved per request, and cheap tokens: a print needs two reserves, 600 sats (600,000 msats) of balance. */
    private val priced = listOf(AgentModel(model, sats = SatsPricing(0.001, 0.002, 0.0, 300.0)))

    /** A node, faked: what it says about itself and the key's balance, and who asked. */
    private class FakeNode(
        var info: RoutstrInfo? = null,
        var balanceMsats: Long? = null,
    ) : RoutstrNode {
        val calls = mutableListOf<String>()

        override suspend fun info(key: AgentKeyTag): RoutstrInfo? {
            calls += "info ${key.url}"
            return info
        }

        override suspend fun balanceMsats(key: AgentKeyTag): Long? {
            calls += "balance ${key.url}"
            return balanceMsats
        }
    }

    private val routstrInfo = RoutstrInfo("Node", null, "0.5.1", "npub1node", listOf("https://mint.example"), null, null)

    private suspend fun routstrWorld(node: FakeNode) =
        runnerWith(routstr = RoutstrFunds(node)).also {
            world(listOf(nodeKey, openRouter))
            listedModels = priced
            schedule(task(model = model, key = nodeKey))
        }

    @Test
    fun aBalanceUnderTwiceTheMaxCostPrintsNothingAndIsLookedAtHourly() =
        runTest {
            val node = FakeNode(balanceMsats = 599_999)
            val worker = routstrWorld(node)
            assertEquals(RunOutcome.INSUFFICIENT_BALANCE, worker.poll()[owner.pubKey])
            assertTrue(printer.calls.isEmpty())
            // Nothing was spent: the morning is still open.
            assertEquals(OwnRun.NONE, runs.ownRun(ObserverRunner.TASK, owner.pubKey, six))
            node.balanceMsats = 600_000
            assertEquals(RunOutcome.INSUFFICIENT_BALANCE, worker.poll()[owner.pubKey], "settled for the hour")
            clock += ObserverRunner.FIXABLE_RETRY_SECONDS
            assertEquals(RunOutcome.PRINTED, worker.poll()[owner.pubKey])
            // `sats_pricing` in the key's own /models said it is a Routstr node: /info was never needed.
            assertTrue(node.calls.none { it.startsWith("info") })
            assertTrue(node.calls.all { it.endsWith(nodeKey.url) }, "only the key's own node is asked")
        }

    @Test
    fun theReserveCoversThePlanAndThePageNotJustTwoMaxCosts() =
        runTest {
            // Dear tokens: the two passes cost more than a second 300-sat reserve.
            val dear = listOf(AgentModel(model, sats = SatsPricing(0.01, 0.05, 1.0, 300.0)))
            val required = RoutstrFunds.requiredMsats(dear.single())
            assertTrue(required > 600_000, "$required")
            val node = FakeNode(balanceMsats = 600_000)
            val worker = routstrWorld(node)
            listedModels = dear
            assertEquals(RunOutcome.INSUFFICIENT_BALANCE, worker.poll()[owner.pubKey], "twice max_cost no longer covers a print")
            assertTrue(printer.calls.isEmpty())
            node.balanceMsats = required
            clock += ObserverRunner.FIXABLE_RETRY_SECONDS
            assertEquals(RunOutcome.PRINTED, worker.poll()[owner.pubKey])
        }

    @Test
    fun aDeepScheduleNeedsTheDeepReserve() =
        runTest {
            val dear = listOf(AgentModel(model, sats = SatsPricing(0.01, 0.05, 1.0, 300.0)))
            val standard = RoutstrFunds.requiredMsats(dear.single())
            val deep = RoutstrFunds.requiredMsats(dear.single(), WritingEffort.DEEP)
            assertTrue(deep > standard)
            val worker = runnerWith(routstr = RoutstrFunds(FakeNode(balanceMsats = standard)))
            world(listOf(nodeKey, openRouter))
            listedModels = dear
            schedule(ObserverParams.withDeep(task(model = model, key = nodeKey), true))
            assertEquals(RunOutcome.INSUFFICIENT_BALANCE, worker.poll()[owner.pubKey])
            assertTrue(printer.calls.isEmpty())
        }

    @Test
    fun aNodeKnownByItsInfoWithAnEmptyBalanceIsNotPrintedWith() =
        runTest {
            val node = FakeNode(info = routstrInfo, balanceMsats = 0)
            val worker = routstrWorld(node)
            listedModels = listed(model)
            assertEquals(RunOutcome.INSUFFICIENT_BALANCE, worker.poll()[owner.pubKey])
            assertTrue(printer.calls.isEmpty())
        }

    @Test
    fun anEndpointThatIsNotARoutstrNodeHasNoBalanceAsked() =
        runTest {
            val node = FakeNode(info = null, balanceMsats = 0)
            val worker = routstrWorld(node)
            listedModels = listed(model)
            assertEquals(RunOutcome.PRINTED, worker.poll()[owner.pubKey])
            assertTrue(node.calls.none { it.startsWith("balance") })
        }

    @Test
    fun aPresetProvidersKeyIsNeverAskedWhetherItIsRoutstr() =
        runTest {
            val node = FakeNode(info = routstrInfo, balanceMsats = 0)
            world()
            schedule()
            assertEquals(RunOutcome.PRINTED, runnerWith(routstr = RoutstrFunds(node)).poll()[owner.pubKey])
            assertTrue(node.calls.isEmpty())
        }

    @Test
    fun a402DuringThePrintIsInsufficientBalanceAndEnded() =
        runTest {
            val worker = routstrWorld(FakeNode(balanceMsats = 1_000_000))
            printer.result =
                FakePrinter.failing(
                    writerExceptionFor(
                        402,
                        """{"detail":{"error":{"type":"insufficient_balance","code":"insufficient_balance","details":{"required_msats":700000,"available_msats":1000}}}}""",
                    ),
                )
            assertEquals(RunOutcome.INSUFFICIENT_BALANCE, worker.poll()[owner.pubKey])
            assertEquals(OwnRun.ENDED, runs.ownRun(ObserverRunner.TASK, owner.pubKey, six))
            clock += ObserverRunner.FIXABLE_RETRY_SECONDS
            assertEquals(RunOutcome.ATTEMPTED, worker.poll()[owner.pubKey])
            assertEquals(1, printer.calls.size)
        }

    @Test
    fun aSettlementFailureIsChargedWithNoAnswerAndNeverResent() =
        runTest {
            val worker = routstrWorld(FakeNode(balanceMsats = 1_000_000))
            printer.result =
                FakePrinter.failing(
                    writerExceptionFor(
                        503,
                        """{"detail":{"error":{"message":"Payment settlement failed","type":"payment_settlement_failed","code":"payment_settlement_failed"}}}""",
                    ),
                )
            assertEquals(RunOutcome.CHARGED_NO_ANSWER, worker.poll()[owner.pubKey])
            assertEquals(OwnRun.ENDED, runs.ownRun(ObserverRunner.TASK, owner.pubKey, six))
            clock += ObserverRunner.FIXABLE_RETRY_SECONDS
            assertEquals(RunOutcome.ATTEMPTED, worker.poll()[owner.pubKey])
            assertEquals(1, printer.calls.size)
        }

    @Test
    fun anUpstreamDownBeforeAnyAnswerSpentNothingAndIsTriedAgainHourly() =
        runTest {
            val worker = routstrWorld(FakeNode(balanceMsats = 1_000_000))
            printer.result =
                FakePrinter.failing(writerExceptionFor(424, """{"error":{"message":"upstream down","code":"UPSTREAM_UNAVAILABLE"}}"""))
            assertEquals(RunOutcome.UPSTREAM_UNAVAILABLE, worker.poll()[owner.pubKey])
            assertEquals(OwnRun.NONE, runs.ownRun(ObserverRunner.TASK, owner.pubKey, six))
            assertEquals(RunOutcome.UPSTREAM_UNAVAILABLE, worker.poll()[owner.pubKey], "settled for the hour")
            printer.result = { _, _ -> FakePrinter.printed() }
            clock += ObserverRunner.FIXABLE_RETRY_SECONDS
            assertEquals(RunOutcome.PRINTED, worker.poll()[owner.pubKey])
            assertEquals(2, printer.calls.size)
        }

    @Test
    fun anUpstreamDownAfterTheFirstAnswerIsEnded() =
        runTest {
            val worker = routstrWorld(FakeNode(balanceMsats = 1_000_000))
            printer.result = { _, _ -> throw AfterAnswerException(WriterException(WriterFailure.UPSTREAM_DOWN, 424)) }
            assertEquals(RunOutcome.WRITER_FAILED, worker.poll()[owner.pubKey])
            assertEquals(OwnRun.ENDED, runs.ownRun(ObserverRunner.TASK, owner.pubKey, six))
        }

    @Test
    fun anOnionNodeIsUnreachableAndNeverAsked() =
        runTest {
            for ((onion, expected) in listOf(
                AgentKeyTag("sk-onion-key-0000", "https://${"b".repeat(56)}.onion/v1") to RunOutcome.UNREACHABLE_ONION,
                AgentKeyTag("sk-onion-key-0000", "https://short.onion/v1") to RunOutcome.UNREACHABLE_ONION,
                // A key-less endpoint may be plain http (NIP-FA): still an onion, refused as one.
                AgentKeyTag("", "http://${"a".repeat(56)}.onion/v1") to RunOutcome.UNREACHABLE_ONION,
                // A key over plain http to anything but loopback isn't a usable NIP-FA key at all.
                AgentKeyTag("sk-onion-key-0000", "http://${"a".repeat(56)}.onion/v1") to RunOutcome.KEY_NOT_GIVEN,
            )) {
                val node = FakeNode(info = routstrInfo, balanceMsats = 1_000_000)
                relays.held.clear()
                printer.calls.clear()
                world(listOf(onion, openRouter))
                schedule(task(model = model, key = onion))
                assertEquals(expected, runnerWith(routstr = RoutstrFunds(node)).poll()[owner.pubKey], onion.url)
                assertTrue(printer.calls.isEmpty(), onion.url)
                assertTrue(node.calls.isEmpty(), onion.url)
            }
        }

    @Test
    fun aKeyParamTheAssistantLacksWithoutAModelFallsBackToNoOtherKey() =
        runTest {
            world()
            // The reader chose a node the Assistant has no key for, and no model: never another provider instead.
            schedule(task(model = null, key = nodeKey))
            assertEquals(RunOutcome.KEY_NOT_GIVEN, runner.poll()[owner.pubKey])
            assertTrue(printer.calls.isEmpty())
        }

    @Test
    fun anOnionNameIsNeverResolved() {
        val asked = mutableListOf<String>()
        val dns =
            GuardedDns(
                object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        asked += hostname
                        return listOf(InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34)))
                    }
                },
            )
        assertFailsWith<RefusedUrlException> { dns.lookup("${"c".repeat(56)}.onion") }
        assertFailsWith<RefusedUrlException> { dns.lookup("x.ONION.") }
        assertTrue(asked.isEmpty())
        assertEquals(UrlGuard.ONION, UrlGuard.refusal("http://${"a".repeat(56)}.onion/v1"))
    }

    private fun node(answer: (String, String?) -> Pair<Int, String>): SingleHttpClient =
        SingleHttpClient(
            OkHttpClient
                .Builder()
                .addInterceptor(
                    Interceptor { chain ->
                        val request = chain.request()
                        val (code, body) = answer(request.url.encodedPath, request.header("Authorization"))
                        Response
                            .Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(code)
                            .message("x")
                            .body(body.toResponseBody("application/json".toMediaType()))
                            .build()
                    },
                ).build(),
        )

    @Test
    fun theNodesInfoAndBalanceAreReadFromTheKeysOwnUrl() =
        runBlocking {
            val seen = mutableListOf<Pair<String, String?>>()
            val http =
                node { path, auth ->
                    seen += path to auth
                    when (path) {
                        "/v1/info" ->
                            200 to
                                """{"name":"Node","npub":"npub1node","mints":["https://mint.example"],"onion_url":"http://x.onion"}"""
                        "/v1/balance/info" -> 200 to """{"api_key":"${nodeKey.key}","balance":"612345","reserved":0}"""
                        else -> 404 to "{}"
                    }
                }
            val node = HttpRoutstrNode(http)
            assertTrue(node.info(nodeKey)!!.isRoutstr)
            assertEquals(612_345L, node.balanceMsats(nodeKey))
            // /info takes no key; the balance does.
            assertEquals(listOf("/v1/info" to null, "/v1/balance/info" to "Bearer ${nodeKey.key}"), seen)
        }

    @Test
    fun aNodeThatIsDownHasNotSaidWhatItIs() =
        runBlocking {
            val node = HttpRoutstrNode(node { _, _ -> 502 to "bad gateway" })
            assertFailsWith<IOException> { node.info(nodeKey) }
            assertNull(node.balanceMsats(nodeKey))
            // Not remembered as "not Routstr": asked again next time.
            val funds = RoutstrFunds(node)
            assertFalse(funds.isRoutstr(nodeKey) { emptyList() })
            val up = RoutstrFunds(FakeNode(info = routstrInfo))
            assertTrue(up.isRoutstr(nodeKey) { emptyList() })
        }

    @Test
    fun theWritersRoutstrFailuresAreReadFromEitherShape() {
        assertEquals(
            WriterFailure.SETTLEMENT_FAILED,
            writerExceptionFor(
                503,
                """{"error":{"message":"m","type":"payment_settlement_failed","code":"payment_settlement_failed"}}""",
            ).failure,
        )
        assertEquals(WriterFailure.LOW_BALANCE, writerExceptionFor(402, """{"detail":{"error":{"code":"insufficient_balance"}}}""").failure)
        assertEquals(WriterFailure.UPSTREAM_DOWN, writerExceptionFor(424, "{}").failure)
        assertEquals(WriterFailure.HTTP, writerExceptionFor(503, "busy").failure)
    }

    @Test
    fun aPrintNeedsTwiceTheMaxCostRoundedUp() {
        assertEquals(2 * 301 * 1000L, RoutstrFunds.requiredMsats(AgentModel("m", sats = SatsPricing(null, null, null, 300.2))))
        assertEquals(1L, RoutstrFunds.requiredMsats(AgentModel("m")))
    }
}
