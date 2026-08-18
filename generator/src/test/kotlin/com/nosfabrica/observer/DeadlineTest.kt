package com.nosfabrica.observer

import com.nosfabrica.observer.nostr.Relays
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * A relay that connects and then never speaks.
 *
 * Read this for what it is: a CHARACTERISATION test, not a regression test. It
 * passes with the deadline in `Relays` removed, because quartz's own idle clock
 * already covers this particular shape — a socket that accepts the connection
 * and never completes the websocket handshake.
 *
 * It is kept because it pins the contract the rest of the code relies on, and
 * that contract is easy to break by accident: a dead relay yields an EMPTY list
 * and a NULL count, promptly, and a count that nobody answered never becomes a
 * zero. "We could not ask" and "the answer is none" are different sentences
 * with different next steps, and only one of them is allowed to reach a reader.
 *
 * The intermittent hang seen against a live relay on 2026-08-18 is NOT what
 * this reproduces; that one also reproduces on the previous commit and remains
 * undiagnosed. See `Relays.deadline`.
 */
class DeadlineTest {
    @Test
    fun `a silent relay returns nothing instead of hanging`() {
        val server = ServerSocket(0)
        val held = mutableListOf<java.net.Socket>()
        val accepting =
            thread(isDaemon = true) {
                runCatching { while (true) held += server.accept() }
            }

        try {
            val url = "ws://127.0.0.1:${server.localPort}"
            Relays(idleMs = 1_000).use { relays ->
                runBlocking {
                    val began = System.currentTimeMillis()
                    val events = relays.fetch(url, Filter(kinds = listOf(1), limit = 1), idle = 1_000)
                    val count = relays.count(url, Filter(kinds = listOf(1)), idle = 1_000)
                    val took = System.currentTimeMillis() - began

                    assertTrue(events.isEmpty(), "a relay that says nothing has said nothing")
                    // Null, not zero. "We could not ask" and "the answer is none"
                    // are different sentences with different next steps.
                    assertNull(count, "a count nobody answered must not become a number")
                    assertTrue(took < 30_000, "both calls should give up promptly, took ${took}ms")
                }
            }
        } finally {
            accepting.interrupt()
            held.forEach { runCatching { it.close() } }
            server.close()
        }
    }
}
