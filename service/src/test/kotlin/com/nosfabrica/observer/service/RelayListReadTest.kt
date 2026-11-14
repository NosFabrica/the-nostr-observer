package com.nosfabrica.observer.service

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The batch reads of NIP-65 lists ([RunnerNostr.writeRelaysOf]) and how a read's copies are judged. Ported from assistant-runner. */
class RelayListReadTest : RunnerWorld() {
    private val nostr get() = RunnerNostr(relays, setOf(index), null, now = { clock }, authorsPerReq = 4, minChunk = 1)

    private suspend fun lists(n: Int) =
        List(
            n,
        ) { newSigner() }.onEach { relays.put(index, it.signed(10002, arrayOf(arrayOf("r", ownerRelay.url, "write")), createdAt = 1)) }

    @Test
    fun aChunkTheEventCapCutOffIsAskedOnNotDropped() =
        runTest {
            val people = lists(4)
            // Every read past 3 events is cut off by the read's event cap: neither served nor refused.
            relays.cap = { if (it == index) 3 else null }
            val read = nostr.writeRelaysOf(people.map { it.pubKey })
            assertTrue(people.all { read[it.pubKey]?.state == WriteRelays.State.LISTED }, read.toString())
        }

    @Test
    fun aRelayThatSaysRateLimitedIsNotAskedAgain() =
        runTest {
            val people = lists(3)
            relays.refuses = { relay, _ -> relay == index }
            relays.refusal = "rate-limited: slow down"
            val read = nostr.writeRelaysOf(people.map { it.pubKey })
            assertEquals(1, relays.asked.size)
            assertTrue(read.values.all { it.state == WriteRelays.State.UNKNOWN })
        }

    @Test
    fun aClosedChunkIsHalvedFromItsOwnSizeAndASingleAuthorNotAskedAgain() =
        runTest {
            val people = lists(3)
            relays.refuses = { relay, _ -> relay == index }
            nostr.writeRelaysOf(people.map { it.pubKey })
            assertEquals(listOf(3, 2, 1, 1, 1), relays.asked.map { it.second.authors!!.size })
        }

    @Test
    fun aFullPageIsJudgedOnWhatTheRelaySentNotOnWhatVerified() =
        runTest {
            val (first, second) = lists(2)
            // A forged list for the second person, newer than their real one: the page of 2 is full
            // as sent, though only one verifies. The second person isn't taken as having no list.
            val real = relays.held[index]!!.first { it.pubKey == second.pubKey }
            relays.put(index, Event("f".repeat(64), second.pubKey, 5, 10002, real.tags, "", "0".repeat(128)))
            val read = nostr.writeRelaysOf(listOf(first.pubKey, second.pubKey))
            assertEquals(WriteRelays.State.LISTED, read[first.pubKey]?.state)
            assertNotEquals(WriteRelays.State.NONE, read[second.pubKey]?.state)
        }

    @Test
    fun aForgedCopyUnderTheSameIdDoesNotHideTheRealOne() =
        runTest {
            world()
            val real = schedule(onIndex = false)
            val forged = Event(real.id, real.pubKey, real.createdAt, real.kind, real.tags, real.content, "0".repeat(128))
            // One read of the owner's relays and the index: the forgery comes first.
            val forging =
                object : RelayAccess by relays {
                    override suspend fun fetch(
                        relays: Set<NormalizedRelayUrl>,
                        filter: Filter,
                    ): RelayRead {
                        val read = this@RelayListReadTest.relays.fetch(relays, filter)
                        if (ownerRelay !in relays) return read
                        return RelayRead(listOf(forged) + read.events, read.served, read.refused, sent = read.sent + 1)
                    }
                }
            val read = RunnerNostr(forging, setOf(index), null, now = { clock }).schedule(owner.pubKey)
            assertEquals(real.sig, read?.event?.sig)
        }

    @Test
    fun onlyAnOwnersFirstEightWriteRelaysAreAsked() =
        runTest {
            val many = List(30) { relay("wss://r$it.example") }
            relays.put(index, owner.signed(10002, many.map { arrayOf("r", it.url, "write") }.toTypedArray(), createdAt = 1))
            val event = schedule()
            many.take(8).forEach { relays.put(it, event) }
            val read = RunnerNostr(relays, setOf(index), null, now = { clock }).schedule(owner.pubKey)
            assertTrue(read!!.quorum)
            val asked = relays.asked.flatMap { it.first }.toSet()
            assertEquals(many.take(8).toSet(), asked - index)
        }
}
