package com.nosfabrica.observer.runner

import com.nosfabrica.observer.frontpage.AgentModel
import com.nosfabrica.observer.frontpage.DEFAULT_DIGEST_BUDGET
import com.nosfabrica.observer.frontpage.EditionContext
import com.nosfabrica.observer.frontpage.FrontPageParams
import com.nosfabrica.observer.frontpage.JsoupPaperBoundary
import com.nosfabrica.observer.frontpage.PrintResult
import com.nosfabrica.observer.frontpage.RoutstrPricing
import com.nosfabrica.observer.frontpage.SatsPricing
import com.nosfabrica.observer.frontpage.SingleHttpClient
import com.nosfabrica.observer.frontpage.WriterFailure
import com.nosfabrica.observer.frontpage.WritingEffort
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.EmptyNostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.INostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.SubscriptionListener
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip5aStaticWebsites.observer.ObserverEdition
import com.vitorpamplona.quartz.nip5aStaticWebsites.observer.ObserverEditions
import com.vitorpamplona.quartz.nip5aStaticWebsites.observer.ObserverEditionsCodec
import com.vitorpamplona.quartz.nip5aStaticWebsites.observer.ObserverSite
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The worker with Amethyst's two-pass engine: Brainstorm's links, the schedule's effort, the plan
 * call and its fallback, the edition's continuity, and the Routstr reserve. The search relay, the
 * AI endpoint and Blossom are all faked.
 */
class RunnerEngineTest : RunnerWorld() {
    private val search = relay("wss://search.example")
    private val writer = newSigner()

    /** The search relay, faked: kind 1 desks answer [notes] ranked and anonymous; names answer [names]. */
    private class SearchRelay(
        val notes: List<Event>,
        val names: List<Event> = emptyList(),
    ) : INostrClient by EmptyNostrClient() {
        override fun subscribe(
            subId: String,
            filters: Map<NormalizedRelayUrl, List<Filter>>,
            listener: SubscriptionListener?,
        ) {
            filters.forEach { (relay, list) ->
                list.forEach { filter ->
                    val answer =
                        when (filter.kinds) {
                            listOf(1) -> notes
                            listOf(0) -> names
                            else -> emptyList()
                        }
                    answer.forEach { runBlocking { listener?.onEvent(it, isLive = false, relay = relay, forFilters = null) } }
                }
                listener?.onEose(relay, forFilters = null)
            }
        }

        override fun unsubscribe(subId: String) {}
    }

    /** An OpenAI-compatible endpoint, faked: each call takes the next of [answers] (status, text) and its body is kept. */
    private class FakeAi(
        vararg answers: Pair<Int, String>,
    ) {
        private val queue = answers.toMutableList()
        val bodies = mutableListOf<String>()

        val client: OkHttpClient =
            OkHttpClient
                .Builder()
                .addInterceptor(
                    Interceptor { chain ->
                        synchronized(bodies) { bodies += Buffer().also { chain.request().body?.writeTo(it) }.readUtf8() }
                        val (code, text) = synchronized(queue) { queue.removeAt(0) }
                        // Answered whole: the engine reads a non-stream answer to its stream request.
                        val body =
                            if (code in 200..299) {
                                """{"choices":[{"message":{"content":${JsonPrimitive(
                                    text,
                                )}}}],"usage":{"prompt_tokens":10,"completion_tokens":5}}"""
                            } else {
                                text
                            }
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(code)
                            .message("x")
                            .body(body.toResponseBody("application/json".toMediaType()))
                            .build()
                    },
                ).build()

        fun planCalls() = bodies.filter { it.contains(PLAN_MARK) }

        fun pageCalls() = bodies.filterNot { it.contains(PLAN_MARK) }

        companion object {
            /** Words of the engine's planning instruction, sent only in the plan call. */
            const val PLAN_MARK = "Before you write today's front page, plan it."

            /** The opening of the `<full>` block as it goes out in JSON: only a planned page call has one. */
            const val FULL_BLOCK = "<full>\\n"
        }
    }

    private suspend fun note() =
        writer.signed(
            1,
            emptyArray(),
            "The relay answered in three seconds flat.",
            createdAt =
                TimeUtils.now() - 3_600,
        )

    private fun plan(note: Event) = """{"lead": {"ids": ["${note.id}"], "angle": "The relay held"}, "full": ["${note.id}"]}"""

    /** A page in the writer's own forms: a verbatim quote, a citation and a byline by hex. */
    private fun page(note: Event) =
        """<!doctype html><html><head><title>The Observer</title><style id="house"></style></head><body>""" +
            """<h1>The relay held</h1><p><q>The relay answered in three seconds flat.</q> """ +
            """<a href="https://brainstorm.world/e/${note.id}">Source</a> """ +
            """by <a href="https://brainstorm.world/p/${note.id}">its author</a></p></body></html>"""

    private fun pressWith(
        notes: List<Event>,
        ai: FakeAi,
    ) = PressPrinter(SearchRelay(notes), search, SingleHttpClient(ai.client), JsoupPaperBoundary)

    @Test
    fun theAssistantsPublishedPageLinksToBrainstorm() =
        runBlocking {
            val note = note()
            val ai = FakeAi(200 to plan(note), 200 to page(note))
            world()
            schedule()
            assertEquals(RunOutcome.PRINTED, runnerWith(printer = pressWith(listOf(note), ai)).poll()[owner.pubKey])

            // The same bytes went to each of the owner's servers.
            val published =
                blossom.stored.values
                    .map { it.decodeToString() }
                    .filter { it.contains("<q>") }
                    .distinct()
                    .single()
            // Resolved by the boundary into Brainstorm's encoded forms, nothing else's.
            assertTrue(published.contains("https://brainstorm.world/e/nevent1"), published)
            assertTrue(published.contains("https://brainstorm.world/p/npub1"), published)
            assertFalse(published.contains("brainstorm.world/e/${note.id}"))
            assertFalse(published.contains("jumble.social"))
            // The writer was told the same bases.
            assertTrue(ai.bodies.all { it.contains("https://brainstorm.world/e/") && it.contains("https://brainstorm.world/p/") })
        }

    @Test
    fun deepEffortIsSentOnBothCallsAndStandardSendsNothing() =
        runBlocking {
            val note = note()
            val deep = FakeAi(200 to plan(note), 200 to page(note))
            val printed =
                pressWith(
                    listOf(note),
                    deep,
                ).print(openRouter, "anthropic/claude-sonnet-4.5", owner.pubKey, "Vitor", PrintOptions(effort = WritingEffort.DEEP))
            assertIs<PrintResult.Printed>(printed)
            assertEquals(2, deep.bodies.size)
            // OpenRouter's knob, and an explicit output limit, on the plan and on the page.
            assertTrue(deep.bodies.all { it.contains(""""reasoning":{"effort":"high"}""") }, deep.bodies.toString())
            assertTrue(deep.bodies.all { it.contains(""""max_tokens":""") })

            val standard = FakeAi(200 to plan(note), 200 to page(note))
            pressWith(listOf(note), standard).print(openRouter, "anthropic/claude-sonnet-4.5", owner.pubKey, "Vitor", PrintOptions())
            assertTrue(standard.bodies.none { it.contains(""""reasoning":""") || it.contains(""""reasoning_effort":""") })
            assertTrue(standard.bodies.all { it.contains(""""max_tokens":""") })
        }

    @Test
    fun theSchedulesEffortParamReachesThePrint() =
        runBlocking {
            world()
            schedule(FrontPageParams.withEffort(task(), WritingEffort.DEEP))
            assertEquals(RunOutcome.PRINTED, runner.poll()[owner.pubKey])
            assertEquals(WritingEffort.DEEP, printer.options.single().effort)
        }

    @Test
    fun noEffortParamIsStandard() =
        runBlocking {
            world()
            schedule()
            assertEquals(RunOutcome.PRINTED, runner.poll()[owner.pubKey])
            assertEquals(WritingEffort.STANDARD, printer.options.single().effort)
        }

    @Test
    fun thePlanIsAskedForFirstAndItsFailureLeavesOnePass() =
        runBlocking {
            val note = note()
            val planned = FakeAi(200 to plan(note), 200 to page(note))
            val first =
                assertIs<PrintResult.Printed>(
                    pressWith(listOf(note), planned).print(openRouter, "m", owner.pubKey, "Vitor", PrintOptions()),
                )
            assertTrue(first.planned)
            assertEquals(1, planned.planCalls().size)
            assertTrue(planned.bodies.first().contains(FakeAi.PLAN_MARK), "the plan is the first call")
            // The page call carries the plan's requested posts in full.
            assertTrue(planned.bodies.last().contains(FakeAi.FULL_BLOCK))

            // The plan call fails (the provider's 500): the page is written from the digest alone.
            val unplanned = FakeAi(500 to """{"error":{"message":"busy"}}""", 200 to page(note))
            val second =
                assertIs<PrintResult.Printed>(
                    pressWith(listOf(note), unplanned).print(openRouter, "m", owner.pubKey, "Vitor", PrintOptions()),
                )
            assertFalse(second.planned)
            assertEquals(2, unplanned.bodies.size)
            assertFalse(unplanned.bodies.last().contains(FakeAi.FULL_BLOCK))
        }

    @Test
    fun aPageCallThatFailsAfterTheAnsweredPlanHasSpent() =
        runBlocking {
            val note = note()
            val ai = FakeAi(200 to plan(note), 402 to """{"detail":{"error":{"code":"insufficient_balance"}}}""")
            val e =
                assertFailsWith<AfterAnswerException> {
                    pressWith(listOf(note), ai).print(openRouter, "m", owner.pubKey, "Vitor", PrintOptions())
                }
            assertEquals(WriterFailure.LOW_BALANCE, e.writer.failure)
        }

    /** A site with [editions] whose pages and `/editions.json` are on the owner's Blossom server. */
    private suspend fun siteWith(
        editions: List<Pair<ObserverEdition, String>>,
        assistantsSite: Boolean,
    ) {
        val server = "blossom.owner.example"
        val withHashes =
            editions.map { (edition, html) ->
                val bytes = html.encodeToByteArray()
                val sha = sha256Hex(bytes)
                blossom.stored["$server/$sha"] = bytes
                edition.copy(sha256 = sha)
            }
        val index = ObserverEditionsCodec.encode(ObserverEditions(title = "Vitor's Observer", editions = withHashes)).encodeToByteArray()
        val indexSha = sha256Hex(index)
        blossom.stored["$server/$indexSha"] = index
        val template =
            ObserverSite.build(
                withHashes,
                "d".repeat(64),
                indexSha,
                "Vitor",
                listOf("https://$server"),
                now - 600,
                forReader = if (assistantsSite) owner.pubKey else null,
            )
        if (assistantsSite) {
            relays.put(
                assistantRelay,
                typed(assistant.sign(template)),
            )
        } else {
            relays.put(ownerRelay, typed(owner.sign(template)))
        }
    }

    private fun edition(
        day: String,
        headline: String,
    ) = ObserverEdition(
        day = day,
        path = "/$day.html",
        sha256 = "",
        headline = headline,
        printedAt = Instant.parse("${day}T06:00:00Z").epochSecond,
    )

    @Test
    fun theEditionCountsBothSitesAndTheRecordsAndReadsBackTheNewestPage() =
        runBlocking {
            world()
            schedule()
            // The Assistant printed yesterday's; the owner's own site has the two days before.
            siteWith(
                listOf(
                    edition("2026-11-11", "Relays hold through the storm") to
                        "<html><head><!-- masthead: The Vitor Ledger | his name --><!-- motto: All the notes fit to keep | a promise --></head><body></body></html>",
                ),
                assistantsSite = true,
            )
            siteWith(
                listOf(
                    edition("2026-11-10", "Zaps double overnight") to "<html><head><!-- masthead: The Old Name --></head></html>",
                    edition("2026-11-09", "A quiet Monday") to "<html></html>",
                ),
                assistantsSite = false,
            )
            // A run the worker remembers on a day neither site shows (its paper never went out).
            records.started(owner.pubKey, six - 7 * 86_400)
            records.ended(owner.pubKey, six - 7 * 86_400)

            assertEquals(RunOutcome.PRINTED, runner.poll()[owner.pubKey])
            val context: EditionContext = printer.options.single().context
            // Four days before today's: No. 5.
            assertEquals(5, context.number)
            assertEquals(listOf("2026-11-11", "2026-11-10", "2026-11-09"), context.recent.map { it.day })
            assertEquals("Relays hold through the storm", context.recent.first().headline)
            // Read back from the newest page, the Assistant's yesterday.
            assertEquals("The Vitor Ledger", context.paper.masthead)
            assertEquals("All the notes fit to keep", context.paper.motto)
            // The schedule's own zone, not the server's.
            assertEquals("Europe/London", context.clock.zoneId)
        }

    @Test
    fun aFirstEditionIsNumberOneWithNothingToRecall() =
        runBlocking {
            world()
            schedule()
            assertEquals(RunOutcome.PRINTED, runner.poll()[owner.pubKey])
            val context = printer.options.single().context
            assertEquals(1, context.number)
            assertTrue(context.recent.isEmpty())
            assertNotNull(context.paper.masthead)
        }

    @Test
    fun theRoutstrReserveCoversBothPassesAndDeep() {
        val sats = SatsPricing(0.003, 0.015, 1.0, 300.0)
        val model = AgentModel("m", sats = sats)
        val estimate = RoutstrPricing.editionSats(sats, DEFAULT_DIGEST_BUDGET)!!
        val deepEstimate = RoutstrPricing.editionSats(sats, DEFAULT_DIGEST_BUDGET, effort = WritingEffort.DEEP)!!
        assertTrue(deepEstimate > estimate)
        // At these prices the plan and the page cost more than one reserve.
        assertTrue(estimate > 300, "$estimate")
        // One reserve in flight plus what the calls before it cost, never under two reserves.
        assertEquals((300 + estimate) * 1000, RoutstrFunds.requiredMsats(model))
        assertEquals((300 + deepEstimate) * 1000, RoutstrFunds.requiredMsats(model, WritingEffort.DEEP))
        // A cheap model: two reserves dominate.
        val cheap = AgentModel("c", sats = SatsPricing(0.000001, 0.000001, 0.0, 300.0))
        assertEquals(600_000L, RoutstrFunds.requiredMsats(cheap))
        // Prices but no max_cost: the estimate alone.
        val noCap = AgentModel("n", sats = SatsPricing(0.003, 0.015, 1.0, null))
        assertEquals(estimate * 1000, RoutstrFunds.requiredMsats(noCap))
    }
}
