package com.nosfabrica.observer.runner

import com.nosfabrica.observer.frontpage.ChatMessage
import com.nosfabrica.observer.frontpage.IRoleBasedHttpClientBuilder
import com.nosfabrica.observer.frontpage.SingleHttpClient
import com.nosfabrica.observer.frontpage.WriterException
import com.nosfabrica.observer.frontpage.WriterFailure
import com.nosfabrica.observer.frontpage.chatComplete
import com.vitorpamplona.quartz.nipFAAgentKeys.tags.AgentKeyTag
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTaskClaims.OwnRun
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What one reader's server can make the worker hold, and how long; and the worker's own state file. */
class RunnerLimitsTest {
    private fun answering(
        code: Int,
        body: ByteArray,
        type: String = "text/event-stream",
    ) = Interceptor { chain ->
        Response
            .Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("x")
            .body(body.toResponseBody(type.toMediaType()))
            .build()
    }

    private fun get(client: OkHttpClient) = client.newCall(Request.Builder().url("https://ai.example.com/v1/models").build()).execute()

    @Test
    fun aBodyPastTheCapFailsAndAnErrorBodyIsTrimmed() {
        val cap = BodyCapInterceptor(maxBytes = 1_000, maxErrorBytes = 100)
        val big =
            OkHttpClient
                .Builder()
                .addInterceptor(cap)
                .addInterceptor(answering(200, ByteArray(5_000) { 'a'.code.toByte() }))
                .build()
        assertFailsWith<IOException> { get(big).use { it.body.string() } }

        val small =
            OkHttpClient
                .Builder()
                .addInterceptor(cap)
                .addInterceptor(answering(200, ByteArray(900)))
                .build()
        assertEquals(900, get(small).use { it.body.bytes().size })

        val error =
            OkHttpClient
                .Builder()
                .addInterceptor(cap)
                .addInterceptor(answering(500, ByteArray(50_000)))
                .build()
        assertEquals(100, get(error).use { it.body.bytes().size })
    }

    @Test
    fun theGuardedClientCapsBodies() {
        assertTrue(guardedClient(OkHttpClient()).interceptors.any { it is BodyCapInterceptor })
    }

    @Test
    fun aTricklingStreamStopsWhenThePrintIsCancelled() {
        // A server of our own on loopback (never a real provider): it answers a stream's headers,
        // then one byte every few seconds, which no read timeout would ever end.
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val serving =
            thread(isDaemon = true) {
                try {
                    server.accept().use { socket ->
                        socket.getInputStream().read(ByteArray(4096))
                        val out = socket.getOutputStream()
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n\r\n".encodeToByteArray())
                        out.flush()
                        repeat(60) {
                            out.write(':'.code)
                            out.flush()
                            Thread.sleep(2_000)
                        }
                    }
                } catch (e: IOException) {
                    // The client hung up: what the test wants.
                }
            }
        try {
            // NIP-FA allows http to loopback with no key; the test client isn't the guarded one.
            val key = AgentKeyTag("", "http://127.0.0.1:${server.localPort}/v1")
            val http: IRoleBasedHttpClientBuilder = SingleHttpClient(OkHttpClient())
            val started = System.nanoTime()
            val result =
                runCatching {
                    runBlocking {
                        withTimeout(1_500) {
                            cancellingCalls(http) { tracked -> tracked.chatComplete(key, "m", listOf(ChatMessage("user", "hi"))) {} }
                        }
                    }
                }
            val seconds = (System.nanoTime() - started) / 1e9
            val failure = result.exceptionOrNull()
            assertTrue(
                failure is TimeoutCancellationException || (failure is WriterException && failure.failure == WriterFailure.UNREACHABLE),
                "$failure",
            )
            assertTrue(seconds < 10, "the print held on for $seconds s")
        } finally {
            server.close()
            serving.join(5_000)
        }
    }
}
