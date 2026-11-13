package com.nosfabrica.observer.service

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.EmptyNostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.INostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.SubscriptionListener
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [QuartzRelayAccess] against relays that misbehave: no socket, a scripted client. */
class QuartzRelayAccessTest {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun stop() = scope.cancel()

    private val honest = relay("wss://honest.example")
    private val junk = relay("wss://junk.example")

    /** [honest] sends [held] and EOSE; [junk] sends an unsigned event every millisecond, forever, and never EOSE. */
    private inner class Scripted(
        val held: List<Event>,
    ) : INostrClient by EmptyNostrClient() {
        val open = ConcurrentHashMap.newKeySet<String>()

        override fun subscribe(
            subId: String,
            filters: Map<NormalizedRelayUrl, List<Filter>>,
            listener: SubscriptionListener?,
        ) {
            open += subId
            for (relay in filters.keys) {
                scope.launch {
                    if (relay == junk) {
                        var n = 0
                        val author = "a".repeat(64)
                        while (isActive && subId in open) {
                            val e = Event("%064x".format(n++), author, 1, 1, emptyArray(), "junk", "0".repeat(128))
                            listener?.onEvent(e, isLive = false, relay = relay, forFilters = null)
                            delay(1)
                        }
                    } else {
                        held.forEach { listener?.onEvent(it, isLive = false, relay = relay, forFilters = null) }
                        listener?.onEose(relay, forFilters = null)
                    }
                }
            }
        }

        override fun unsubscribe(subId: String) {
            open -= subId
        }
    }

    @Test
    fun aRelayStreamingJunkIsCutOffAtTheDeadlineAndTheOthersStillServe() =
        runBlocking {
            val note = newSigner().signed(1, emptyArray(), "hello", createdAt = 1)
            val client = Scripted(listOf(note))
            val access = QuartzRelayAccess(client, idleMs = 5_000, deadlineMs = 800, maxEvents = Int.MAX_VALUE)
            val started = System.nanoTime()
            val read = access.fetch(setOf(honest, junk), Filter(kinds = listOf(1)))
            val tookMs = (System.nanoTime() - started) / 1_000_000
            // Its events never let Quartz's idle window run out: only the wall clock ends it.
            assertTrue(tookMs < 4_000, "took $tookMs ms")
            assertEquals(setOf(honest), read.served)
            assertEquals(listOf(note.id), read.events.map { it.id })
            // And its subscription is closed.
            assertTrue(client.open.isEmpty())
        }

    @Test
    fun aRelaySendingMoreThanTheCapIsCutOffAtOnce() =
        runBlocking {
            val client = Scripted(emptyList())
            val access = QuartzRelayAccess(client, idleMs = 5_000, deadlineMs = 60_000, maxEvents = 50)
            val started = System.nanoTime()
            val read = access.fetch(setOf(junk), Filter(kinds = listOf(1)))
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(tookMs < 10_000, "took $tookMs ms")
            assertTrue(read.served.isEmpty())
            assertTrue(read.events.isEmpty())
            assertTrue(client.open.isEmpty())
        }
}
