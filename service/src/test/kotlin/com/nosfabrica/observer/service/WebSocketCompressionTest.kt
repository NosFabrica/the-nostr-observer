package com.nosfabrica.observer.service

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.Deflater
import javax.net.SocketFactory
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `permessage-deflate` on an owner's relay: OkHttp offers it on every WebSocket, and inflates a
 * whole message in memory, so a few kilobytes on the wire (what [ReadBudget] counts) could be
 * gigabytes on the heap. A loopback WebSocket server, no relay.
 */
class WebSocketCompressionTest {
    /** How big one message is once inflated: far past what its compressed bytes say. */
    private val inflated = 8 * 1024 * 1024

    private val server = ServerSocket(0, 10, InetAddress.getLoopbackAddress())

    /** The `Sec-WebSocket-Extensions` the client sent, as the server saw it. */
    private val offered = AtomicReference<String?>()

    @AfterTest
    fun close() = server.close()

    /**
     * Accepts one WebSocket and sends one text message of [inflated] bytes. [always] negotiates
     * deflate and sends the message compressed whatever the client offered (a hostile relay);
     * otherwise only when offered, as an honest relay (strfry) does, and plain when not.
     */
    private fun serve(always: Boolean) =
        thread(isDaemon = true) {
            server.accept().use { socket ->
                val input = socket.getInputStream()
                val headers = readHeaders(input)
                val key = headers["sec-websocket-key"]!!
                offered.set(headers["sec-websocket-extensions"])
                val deflate = always || offered.get()?.contains("permessage-deflate") == true
                val accept =
                    Base64.getEncoder().encodeToString(
                        MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encodeToByteArray()),
                    )
                val out = socket.getOutputStream()
                out.write(
                    (
                        "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                            "Sec-WebSocket-Accept: $accept\r\n" +
                            (if (deflate) "Sec-WebSocket-Extensions: permessage-deflate\r\n" else "") +
                            "\r\n"
                    ).encodeToByteArray(),
                )
                val message = ByteArray(inflated) { 'a'.code.toByte() }
                if (deflate) frame(out, compressed(message), rsv1 = true) else frame(out, message, rsv1 = false)
                out.flush()
                // Held open until the client gives up on it.
                try {
                    input.read()
                } catch (e: Exception) {
                }
            }
        }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val head = ByteArrayOutputStream()
        while (!head.toString().endsWith("\r\n\r\n")) head.write(input.read())
        return head
            .toString()
            .split("\r\n")
            .drop(1)
            .filter { ':' in it }
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
    }

    /** RFC 7692: raw deflate, sync-flushed, without its trailing `00 00 ff ff`. */
    private fun compressed(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(data)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = deflater.deflate(buf, 0, buf.size, Deflater.SYNC_FLUSH)
            if (n == 0) break
            out.write(buf, 0, n)
        }
        val bytes = out.toByteArray()
        return bytes.copyOf(bytes.size - 4)
    }

    private fun frame(
        out: OutputStream,
        payload: ByteArray,
        rsv1: Boolean,
    ) {
        out.write(if (rsv1) 0xC1 else 0x81)
        when {
            payload.size < 126 -> {
                out.write(payload.size)
            }

            payload.size <= 0xffff -> {
                out.write(126)
                out.write(payload.size ushr 8)
                out.write(payload.size and 0xff)
            }

            else -> {
                out.write(127)
                for (i in 7 downTo 0) out.write(((payload.size.toLong() ushr (8 * i)) and 0xff).toInt())
            }
        }
        out.write(payload)
    }

    /** The guarded relay client as the runner builds it, let through to loopback for this test only. */
    private fun relayClient(): OkHttpClient =
        guardedClient(OkHttpClient(), setOf("wss"))
            .newBuilder()
            .apply { interceptors().removeAll { it is UrlGuardInterceptor } }
            .socketFactory(SocketFactory.getDefault())
            .dns(Dns.SYSTEM)
            .build()

    private class Outcome {
        val biggest = AtomicInteger(-1)
        val failure = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
    }

    private fun connect(client: OkHttpClient): Outcome {
        val outcome = Outcome()
        client.newWebSocket(
            Request.Builder().url("ws://127.0.0.1:${server.localPort}/").build(),
            object : WebSocketListener() {
                override fun onMessage(
                    webSocket: WebSocket,
                    text: String,
                ) {
                    outcome.biggest.accumulateAndGet(text.length, ::maxOf)
                    webSocket.cancel()
                    outcome.done.countDown()
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?,
                ) {
                    outcome.failure.set(t)
                    outcome.done.countDown()
                }
            },
        )
        assertTrue(outcome.done.await(30, TimeUnit.SECONDS), "no answer")
        return outcome
    }

    @Test
    fun aRelayThatCompressesAnywayIsDroppedAndNothingIsInflated() {
        serve(always = true)
        val outcome = connect(relayClient())
        assertEquals(-1, outcome.biggest.get(), "a compressed message reached the listener")
        assertNotNull(outcome.failure.get())
    }

    @Test
    fun deflateIsNeverOfferedSoAnHonestRelaySendsPlainFrames() {
        serve(always = false)
        val outcome = connect(relayClient())
        assertEquals(null, offered.get())
        // Plain frames: every byte of this message went through the socket, and its budget.
        assertEquals(inflated, outcome.biggest.get())
    }
}
