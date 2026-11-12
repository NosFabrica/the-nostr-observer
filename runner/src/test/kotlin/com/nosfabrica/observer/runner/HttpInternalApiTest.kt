package com.nosfabrica.observer.runner

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** brainstorm_server's internal API over HTTP, mocked by an interceptor: no socket is opened. */
class HttpInternalApiTest {
    private class Answer(
        val code: Int,
        val body: String,
        val reason: String? = null,
    )

    private val seen = mutableListOf<Pair<Request, String>>()

    private fun api(vararg answers: Answer): HttpInternalApi {
        val queue = answers.toMutableList()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor(
                    Interceptor { chain ->
                        val request = chain.request()
                        val body = request.body?.let { Buffer().also { b -> it.writeTo(b) }.readUtf8() }.orEmpty()
                        seen += request to body
                        val answer = queue.removeAt(0)
                        Response
                            .Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(answer.code)
                            .message("x")
                            .apply { answer.reason?.let { header("X-Reason", it) } }
                            .body(answer.body.toResponseBody("application/json".toMediaType()))
                            .build()
                    },
                ).build()
        return HttpInternalApi("http://brainstorm-server:8000/", "runner-token", client)
    }

    @Test
    fun pagesTheAssistantsWithTheBearerToken() =
        runTest {
            val owner = "a".repeat(64)
            val assistant = "b".repeat(64)
            val body = """{"code":200,"data":{"items":[{"owner":"$owner","assistant":"$assistant"}],"next":"$owner"},"message":""}"""
            val page = api(Answer(200, body)).assistants(null)
            assertEquals(listOf(AssistantPair(owner, assistant)), page.items)
            assertEquals(owner, page.next)
            val (request, _) = seen.single()
            assertEquals("Bearer runner-token", request.header("Authorization"))
            assertEquals("/internal/observerRunner/assistants", request.url.encodedPath)
            assertEquals("500", request.url.queryParameter("limit"))
        }

    @Test
    fun decryptSendsTheWholeSignedEvent() =
        runTest {
            val owner = newSigner()
            val event = owner.signed(34817, arrayOf(arrayOf("d", "observer"), arrayOf("p", "b".repeat(64))), "ciphertext", createdAt = 100)
            val plaintext =
                api(
                    Answer(200, """{"code":200,"data":{"plaintext":"[[\"task\",\"observer\"]]"},"message":""}"""),
                ).decrypt(owner.pubKey, event)
            assertEquals("[[\"task\",\"observer\"]]", plaintext)
            val sent = Json.parseToJsonElement(seen.single().second).jsonObject
            assertEquals(owner.pubKey, sent["owner"]!!.jsonPrimitive.content)
            val sentEvent = sent["event"] as JsonObject
            // The whole signed event, so the server can verify it before it decrypts: no bare ciphertext mode.
            assertEquals(event.id, sentEvent["id"]!!.jsonPrimitive.content)
            assertEquals(event.sig, sentEvent["sig"]!!.jsonPrimitive.content)
            assertEquals(setOf("owner", "event"), sent.keys)
        }

    @Test
    fun noAssistantIsItsOwnAnswerAndAPlain404IsTheToken() =
        runTest {
            val owner = newSigner()
            val event = owner.signed(34817, emptyArray(), "x", createdAt = 1)
            assertFailsWith<NoAssistantException> {
                api(Answer(404, """{"detail":"Not Found"}""", reason = "no-assistant")).decrypt(owner.pubKey, event)
            }
            assertFailsWith<NoAssistantException> {
                api(Answer(404, "{}", reason = "no-assistant")).sign(owner.pubKey, EventTemplate<Event>(1, 24242, emptyArray(), ""))
            }
            assertFailsWith<RunnerTokenRejectedException> { api(Answer(404, """{"detail":"Not Found"}""")).decrypt(owner.pubKey, event) }
            assertFailsWith<RunnerTokenRejectedException> { api(Answer(404, "{}")).assistants(null) }
            val refused =
                assertFailsWith<RefusedByServerException> {
                    api(Answer(403, """{"detail":"An Observer site needs exactly one p tag, the owner."}""")).decrypt(owner.pubKey, event)
                }
            assertTrue(refused.detail.startsWith("An Observer site"))
        }

    @Test
    fun signReturnsTheServersEventNotTheOneSent() =
        runTest {
            val assistant = newSigner()
            val template =
                EventTemplate<Event>(
                    1_000,
                    24242,
                    arrayOf(arrayOf("t", "upload"), arrayOf("expiration", "1300"), arrayOf("client", "Amethyst")),
                    "Upload",
                )
            val serverSigned =
                assistant.signed(
                    24242,
                    arrayOf(arrayOf("t", "upload"), arrayOf("expiration", "1300"), arrayOf("client", "Brainstorm")),
                    "Upload",
                    createdAt = 1_000,
                )
            val signed = api(Answer(200, """{"code":200,"data":${serverSigned.toJson()},"message":""}""")).sign("a".repeat(64), template)
            assertEquals(serverSigned.id, signed.id)
            assertEquals("Brainstorm", signed.tags.first { it[0] == "client" }[1])
            val sent = Json.parseToJsonElement(seen.single().second).jsonObject["event"]!!.jsonObject
            assertEquals(setOf("kind", "created_at", "tags", "content"), sent.keys)
        }

    @Test
    fun aSignedEventThatDoesNotVerifyIsRefused() =
        runTest {
            val assistant = newSigner()
            val good = assistant.signed(24242, arrayOf(arrayOf("t", "upload")), "Upload", createdAt = 1_000)
            val forged = good.toJson().replace("\"Upload\"", "\"Uploaded\"")
            assertFailsWith<InternalApiException> {
                api(
                    Answer(200, """{"code":200,"data":$forged,"message":""}"""),
                ).sign("a".repeat(64), EventTemplate<Event>(1_000, 24242, emptyArray(), "Upload"))
            }
        }
}
