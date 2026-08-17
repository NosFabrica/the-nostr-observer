package com.nosfabrica.observer.nostr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One websocket to one relay, many subscriptions on it.
 *
 * NIP-01 multiplexes by subscription id, so a whole edition — nine kinds, the
 * profile batches and the control run — travels down a single connection. That
 * is not only cheaper for us: opening ten sockets to somebody else's relay to
 * ask ten questions is rude, and this relay is shared.
 *
 * Every [req] resolves on its own EOSE. A relay that never sends one resolves on
 * the timeout with whatever arrived, because a partial answer prints a thinner
 * paper and no answer prints none.
 */
class RelayClient(
    private val url: String,
) : Closeable {
    private val http =
        OkHttpClient
            .Builder()
            // The read timeout has to exceed the longest a REQ may legitimately take
            // to drain. OkHttp counts it between FRAMES, not for the whole socket, so
            // this is an idle window and not a deadline on the edition.
            .readTimeout(90, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

    private val nextId = AtomicInteger(0)
    private val subs = ConcurrentHashMap<String, Subscription>()
    private val opened = CompletableDeferred<Unit>()

    private class Subscription {
        val events = mutableListOf<NostrEvent>()
        val done = CompletableDeferred<Unit>()

        @Synchronized fun add(e: NostrEvent) {
            events.add(e)
        }

        @Synchronized fun snapshot(): List<NostrEvent> = events.toList()
    }

    private val socket: WebSocket =
        http.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(
                    webSocket: WebSocket,
                    response: Response,
                ) {
                    opened.complete(Unit)
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    text: String,
                ) {
                    val msg = runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return
                    val verb = msg.getOrNull(0)?.jsonPrimitive?.content ?: return
                    val subId = msg.getOrNull(1)?.jsonPrimitive?.content
                    when (verb) {
                        "EVENT" -> {
                            val sub = subs[subId] ?: return
                            NostrEvent.from(msg.getOrNull(2)?.jsonObject ?: return)?.let(sub::add)
                        }

                        // CLOSED means the relay declined the filter. It is an answer,
                        // and treating it as anything but "this one is finished" hangs
                        // the whole edition on one refused query.
                        "EOSE", "CLOSED" -> {
                            subs[subId]?.done?.complete(Unit)
                        }
                    }
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?,
                ) {
                    if (!opened.isCompleted) opened.completeExceptionally(t)
                    subs.values.forEach { it.done.complete(Unit) }
                }

                override fun onClosed(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String,
                ) {
                    subs.values.forEach { it.done.complete(Unit) }
                }
            },
        )

    /** Run one filter to EOSE. Returns whatever arrived, in relay order. */
    suspend fun req(
        filter: JsonObject,
        timeoutMs: Long = 45_000,
    ): List<NostrEvent> {
        withTimeout(15_000) { opened.await() }
        val id = "s${nextId.incrementAndGet()}"
        val sub = Subscription()
        subs[id] = sub
        socket.send(
            buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("REQ"))
                add(kotlinx.serialization.json.JsonPrimitive(id))
                add(filter)
            }.toString(),
        )
        try {
            withTimeout(timeoutMs) { sub.done.await() }
        } catch (_: TimeoutCancellationException) {
            // Deliberately not rethrown: see the class comment. A relay that goes
            // quiet costs us the tail of one section, not the paper.
        } finally {
            subs.remove(id)
            runCatching { socket.send("""["CLOSE","$id"]""") }
        }
        return sub.snapshot()
    }

    /** Run every filter concurrently down the one socket. */
    suspend fun reqAll(
        filters: List<JsonObject>,
        timeoutMs: Long = 45_000,
    ): List<List<NostrEvent>> = coroutineScope { filters.map { async { req(it, timeoutMs) } }.awaitAll() }

    override fun close() {
        runCatching { socket.close(1000, null) }
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }
}
