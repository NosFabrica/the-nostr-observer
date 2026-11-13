package com.nosfabrica.observer.runner

import com.nosfabrica.assistant.core.MemoryPendingStore
import com.nosfabrica.assistant.core.SettledAnswer
import com.nosfabrica.assistant.core.WakeRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `POST /v1/run` over a real socket on localhost, against the trigger contract's vectors (copied
 * from assistant-runner's `contract/v1/vectors/` into the test resources; a change there that
 * breaks one is a `v2`). Everything behind it is the usual fakes.
 */
class WakeServerTest : RunnerWorld() {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val gate = CompletableDeferred<Unit>()
    private var gated = false
    private val gatedPrinter =
        PaperPrinter { key, model, who, name, options ->
            if (gated) gate.await()
            printer.print(key, model, who, name, options)
        }
    private val service =
        WakeService(runnerWith(printer = gatedPrinter), runs, MemoryPendingStore(), scope, parallelism = 1)
    private val server = WakeServer(service, port = 0, host = "127.0.0.1").also { it.start() }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @AfterTest
    fun stop() {
        server.close()
        scope.cancel()
    }

    private fun vector(name: String): String =
        assertNotNull(javaClass.getResourceAsStream("/contract/v1/vectors/$name"), name).use { it.readBytes().decodeToString() }

    private fun post(
        body: String,
        path: String = WakeRequest.PATH,
        method: String = "POST",
    ): HttpResponse<String> =
        http.send(
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:${server.port}$path"))
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun json(response: HttpResponse<String>): JsonObject = Json.parseToJsonElement(response.body()).jsonObject

    private fun wakeOf(owner: String) = WakeRequest("observer", owner, six).toJson().toString()

    @Test
    fun theContractsWakeIsAccepted() {
        // The vector's owner has no Assistant here: accepted all the same, and settled in the background.
        val response = post(vector("wake-observer.json"))
        assertEquals(202, response.statusCode())
        assertEquals(false, json(response)["joined"]?.jsonPrimitive?.content?.toBooleanStrict())
    }

    @Test
    fun theContractsInvalidWakesAre400WithTheirWhy() {
        for (name in listOf("wake-invalid-owner.json", "wake-invalid-version.json")) {
            val text = vector(name)
            val response = post(text)
            assertEquals(400, response.statusCode(), name)
            assertEquals(Json.parseToJsonElement(text).jsonObject["why"], json(response)["why"], name)
        }
    }

    @Test
    fun aMalformedBodyIs400() {
        val owner = owner.pubKey
        for (body in listOf(
            "",
            "{",
            "[]",
            "not json",
            """{"v":1,"task":"observer","owner":"$owner"}""",
            """{"v":1,"task":"observer","owner":"$owner","occurrence":"1763020800"}""",
            """{"v":1,"task":"observer","owner":"$owner","occurrence":-1}""",
            """{"v":1,"task":"Observer!","owner":"$owner","occurrence":1}""",
            """{"v":1,"task":"podcast","owner":"$owner","occurrence":1}""",
            " ".repeat(WakeServer.MAX_BODY_BYTES + 1),
        )) {
            val response = post(body)
            assertEquals(400, response.statusCode(), body.take(80))
            assertTrue(json(response)["why"] is JsonPrimitive, body.take(80))
        }
    }

    @Test
    fun aSettledOccurrenceIs200WithItsOutcome() =
        runBlocking {
            runs.claim(ObserverRunner.TASK, owner.pubKey, six)
            runs.end(ObserverRunner.TASK, owner.pubKey, six, RunOutcome.PRINTED.name)
            val response = post(wakeOf(owner.pubKey))
            assertEquals(200, response.statusCode())
            assertEquals("PRINTED", SettledAnswer.parse(response.body())?.outcome)
            // As the contract's own answer vector is shaped.
            assertEquals("NOT_DUE", SettledAnswer.parse(vector("answer-not-due.json"))?.outcome)
        }

    @Test
    fun aJoinedWakeIs202AndAWakePastTheSlotsIs429WithRetryAfter() =
        runBlocking {
            world()
            schedule()
            secondOwner()
            gated = true
            assertEquals(202, post(wakeOf(owner.pubKey)).statusCode())
            // Past its claim: the print is under way, held at the gate.
            val deadline = System.currentTimeMillis() + 30_000
            while (!printing()) {
                assertTrue(System.currentTimeMillis() < deadline, "the run never reached its claim")
                delay(20)
            }
            val joined = post(wakeOf(owner.pubKey))
            assertEquals(202, joined.statusCode())
            assertEquals(true, json(joined)["joined"]?.jsonPrimitive?.content?.toBooleanStrict())
            val busy = post(wakeOf(stranger.pubKey))
            assertEquals(429, busy.statusCode())
            assertEquals(WakeService.RETRY_AFTER_SECONDS.toString(), busy.headers().firstValue("Retry-After").orElse(null))
            gate.complete(Unit)
            assertTrue(service.drain(30_000))
            assertEquals(1, printer.calls.size)
        }

    /** Whether the owner's run is past its claim. */
    private suspend fun printing() = runs.find(ObserverRunner.TASK, owner.pubKey, six) != null

    @Test
    fun anotherPathIs404AndAnotherMethod405() {
        assertEquals(404, post(wakeOf(owner.pubKey), path = "/v1/run/").statusCode())
        assertEquals(404, post(wakeOf(owner.pubKey), path = "/v2/run").statusCode())
        assertEquals(404, post(wakeOf(owner.pubKey), path = "/").statusCode())
        val get = post("", method = "GET")
        assertEquals(405, get.statusCode())
        assertEquals("POST", get.headers().firstValue("Allow").orElse(null))
        assertEquals(405, post(wakeOf(owner.pubKey), method = "PUT").statusCode())
    }
}
