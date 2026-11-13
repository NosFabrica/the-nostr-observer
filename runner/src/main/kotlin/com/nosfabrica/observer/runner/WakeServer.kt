package com.nosfabrica.observer.runner

import com.nosfabrica.assistant.core.SettledAnswer
import com.nosfabrica.assistant.core.WakeRequest
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.vitorpamplona.quartz.utils.Log
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * `POST /v1/run`, the trigger contract (v1), on the JDK's own HTTP server: one route, a body of a
 * few bytes, no TLS (inside the cluster), so no dependency earns its place.
 *
 * No token: the endpoint is internal-only, and a wake only makes the service do what the owner's
 * own signed schedule asks, which it reads and checks itself. Anything else answers `404` (another
 * path) or `405` (another method).
 */
class WakeServer(
    private val service: WakeService,
    port: Int,
    host: String = "0.0.0.0",
    threads: Int = 4,
) : AutoCloseable {
    private val executor: ExecutorService = Executors.newFixedThreadPool(threads)
    private val server: HttpServer = HttpServer.create(InetSocketAddress(host, port), BACKLOG)

    init {
        server.createContext("/") { exchange -> handle(exchange) }
        server.executor = executor
    }

    /** The port it listens on: the one asked for, or the one the OS picked for port 0. */
    val port: Int get() = server.address.port

    fun start() = server.start()

    /** Stops taking wakes: the listener closes, exchanges under way get [delaySeconds] to finish. */
    fun stop(delaySeconds: Int = 1) {
        server.stop(delaySeconds)
        executor.shutdown()
    }

    override fun close() = stop(0)

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            try {
                route(it)
            } catch (e: IOException) {
                // The runner hung up: nothing to answer.
                Log.d(TAG) { "exchange failed: ${e.javaClass.simpleName}" }
            } catch (e: Exception) {
                // The class only. A 503 makes the runner wake again later.
                Log.w(TAG) { "wake failed: ${e.javaClass.simpleName}" }
                try {
                    respond(it, 503, """{"why":"unavailable"}""")
                } catch (e: IOException) {
                    // Headers already sent, or the runner gone.
                }
            }
        }
    }

    private fun route(exchange: HttpExchange) {
        if (exchange.requestURI.rawPath != WakeRequest.PATH) return respond(exchange, 404, """{"why":"not found"}""")
        if (exchange.requestMethod != "POST") {
            exchange.responseHeaders.add("Allow", "POST")
            return respond(exchange, 405, """{"why":"POST only"}""")
        }
        val body = exchange.requestBody.use { it.readNBytes(MAX_BODY_BYTES + 1) }
        if (body.size > MAX_BODY_BYTES) return malformed(exchange, "body too large")
        val request =
            when (val parsed = WakeRequest.parse(body.decodeToString())) {
                is WakeRequest.Parsed.Invalid -> return malformed(exchange, parsed.why)
                is WakeRequest.Parsed.Valid -> parsed.request
            }
        // On this server thread: the service only reads a row and launches the run in the background.
        when (val answer = runBlocking { service.wake(request) }) {
            is WakeAnswer.Accepted -> {
                respond(exchange, 202, buildJsonObject { put("joined", answer.joined) }.toString())
            }

            is WakeAnswer.Settled -> {
                respond(exchange, 200, SettledAnswer(answer.outcome).toJson().toString())
            }

            is WakeAnswer.Busy -> {
                exchange.responseHeaders.add("Retry-After", answer.retryAfterSeconds.toString())
                respond(exchange, 429, """{"why":"busy"}""")
            }

            is WakeAnswer.Malformed -> {
                malformed(exchange, answer.why)
            }
        }
    }

    private fun malformed(
        exchange: HttpExchange,
        why: String,
    ) = respond(exchange, 400, buildJsonObject { put("why", why) }.toString())

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        json: String,
    ) {
        val bytes = json.encodeToByteArray()
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    companion object {
        private const val TAG = "WakeServer"

        /** A wake is about 150 bytes; anything much past it is not one. */
        const val MAX_BODY_BYTES = 16 * 1024

        private const val BACKLOG = 64
    }
}
