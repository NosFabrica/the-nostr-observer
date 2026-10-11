package com.nosfabrica.observer

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.nosfabrica.observer.corpus.Digest
import com.nosfabrica.observer.nostr.Corpus
import com.nosfabrica.observer.nostr.Desk
import com.nosfabrica.observer.write.Continuity
import com.nosfabrica.observer.write.Writer
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The half-price path, against a fake of the Message Batches API.
 *
 * There is no API key in the dev container, so this is the only place the
 * batch round trip runs before it meets the real thing: create, poll until
 * ended, read the one result back. The shapes are the API's documented ones,
 * trimmed to what the SDK reads.
 */
class BatchWriterTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val base get() = "http://127.0.0.1:${server.address.port}"
    private val requests = CopyOnWriteArrayList<String>()
    private val polls = AtomicInteger()

    /** What the one request in the batch comes back as. */
    private var result: String = succeeded("end_turn", null)

    /** How many polls fail with a 500 before the API answers again. */
    private var failingPolls = 0

    init {
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            requests += "${exchange.requestMethod} $path " + exchange.requestBody.readBytes().decodeToString()
            val body =
                when {
                    exchange.requestMethod == "POST" && path == "/v1/messages/batches" -> batch("in_progress")

                    path.endsWith("/results") -> result + "\n"

                    path.endsWith("/cancel") -> batch("canceling")

                    // Ended on the second look, so the loop has to actually poll.
                    else -> batch(if (polls.incrementAndGet() >= 2) "ended" else "in_progress")
                }
            val failing = exchange.requestMethod == "GET" && path == "/v1/messages/batches/msgbatch_test" && failingPolls-- > 0
            val bytes = (if (failing) """{"type":"error","error":{"type":"api_error","message":"down"}}""" else body).toByteArray()
            exchange.responseHeaders.add("Content-Type", if (path.endsWith("/results")) "application/x-jsonl" else "application/json")
            exchange.sendResponseHeaders(if (failing) 500 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun batch(status: String) =
        """
        {"id":"msgbatch_test","type":"message_batch","processing_status":"$status",
         "request_counts":{"processing":1,"succeeded":0,"errored":0,"canceled":0,"expired":0},
         "ended_at":null,"created_at":"2026-10-11T00:00:00Z","expires_at":"2026-10-12T00:00:00Z",
         "cancel_initiated_at":null,"archived_at":null,
         "results_url":"$base/v1/messages/batches/msgbatch_test/results"}
        """.trimIndent()

    private fun succeeded(
        stop: String,
        details: String?,
    ) = """{"custom_id":"edition","result":{"type":"succeeded","message":{"id":"msg_1","type":"message","role":"assistant",""" +
        """"model":"claude-sonnet-5-5","content":[{"type":"thinking","thinking":"","signature":"sig"},""" +
        """{"type":"text","text":"```html\n<!doctype html><html><body><h1>Front</h1></body></html>\n```"}],""" +
        """"stop_reason":"$stop","stop_sequence":null,"stop_details":${details ?: "null"},""" +
        """"usage":{"input_tokens":70000,"output_tokens":20000}}}}"""

    private fun writer() =
        Writer(pollMillis = 10) {
            AnthropicOkHttpClient
                .builder()
                .apiKey("test")
                .baseUrl(base)
                .maxRetries(0)
                .build()
        }

    private fun write(writer: Writer = writer()): Pair<Writer.Draft, List<String>> {
        val queued = mutableListOf<String>()
        val corpus =
            Corpus(
                observer = Fixtures.OBSERVER,
                since = 1_787_018_640L - 86_400,
                until = 1_787_018_640L,
                ranked = mapOf(Desk.NOTES to listOf(Fixtures.plain)),
                control = emptyList(),
                dayNotes = 100,
                profiles = emptyMap(),
            )
        val draft =
            runBlocking {
                writer.write(corpus, Digest().render(corpus, emptyList()), emptyList(), Continuity()) { queued += it }
            }
        return draft to queued
    }

    @Test
    fun `a batched edition is created, polled until it ends, and read back`() {
        val (draft, queued) = write()

        assertEquals(listOf("msgbatch_test"), queued)
        assertTrue(polls.get() >= 2, "it should have polled until the batch ended")
        // The fence is stripped and the thinking block contributes nothing.
        assertEquals("<!doctype html><html><body><h1>Front</h1></body></html>", draft.html)
        assertEquals(70_000, draft.inputTokens)
        assertEquals(20_000, draft.outputTokens)
        assertTrue(draft.batched)

        val created = requests.first { it.startsWith("POST /v1/messages/batches ") }
        assertTrue(created.contains("\"model\":\"claude-sonnet-5-5\""), created)
        assertTrue(created.contains("\"effort\":\"medium\""), created)
        assertTrue(created.contains("\"custom_id\":\"edition\""), created)
    }

    @Test
    fun `a batched sonnet edition costs half the list price`() {
        val (draft, _) = write()
        // 70K in at $2/M plus 20K out at $10/M is $0.34 at list; a batch halves it.
        assertEquals(0.17, draft.costUsd(), 1e-9)
        assertEquals(0.34, draft.copy(batched = false).costUsd(), 1e-9)
    }

    @Test
    fun `a refusal is a sentence, not a page`() {
        result = succeeded("refusal", """{"type":"refusal","category":"cyber","explanation":null}""")
        val e = assertThrows(Writer.Unwritten::class.java) { write() }
        assertTrue(e.message.contains("declined") && e.message.contains("cyber"), e.message)
    }

    @Test
    fun `an expired batch says so`() {
        result = """{"custom_id":"edition","result":{"type":"expired"}}"""
        val e = assertThrows(Writer.Unwritten::class.java) { write() }
        assertTrue(e.message.contains("expired"), e.message)
    }

    @Test
    fun `a few failed polls do not abandon a page still being written`() {
        failingPolls = 3
        val (draft, _) = write()
        assertTrue(draft.html.contains("Front"))
        assertTrue(requests.none { it.contains("/cancel") }, "a hiccup cancelled the batch")
    }

    @Test
    fun `a run of failed polls gives up, and cancels the batch it was paying for`() {
        failingPolls = Int.MAX_VALUE
        assertThrows(com.anthropic.errors.AnthropicException::class.java) { write() }
        assertTrue(requests.any { it.startsWith("POST /v1/messages/batches/msgbatch_test/cancel") }, "the batch was left running")
    }

    @Test
    fun `an errored result reads as the API's own sentence`() {
        result =
            """{"custom_id":"edition","result":{"type":"errored","error":{"type":"error",""" +
            """"error":{"type":"invalid_request_error","message":"prompt is too long"}}}}"""
        val e = assertThrows(Writer.Unwritten::class.java) { write() }
        assertEquals("The model API rejected the edition: prompt is too long", e.message)
    }

    @Test
    fun `a model without a price is refused before anything is spent`() {
        assertThrows(IllegalArgumentException::class.java) { Writer(model = "claude-unpriced-1") }
    }
}
