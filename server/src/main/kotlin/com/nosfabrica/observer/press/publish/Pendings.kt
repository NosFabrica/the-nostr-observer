package com.nosfabrica.observer.press.publish

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Templates issued but not yet signed.
 *
 * They exist only to be compared against what comes back, so they need to
 * survive a round trip through a signer and nothing longer. Held in memory:
 * losing them on restart costs the reader one press of Prepare.
 *
 * They expire, and that is not tidiness. A plain map here grows by one entry
 * for every Prepare that the reader then abandoned — a signer prompt declined,
 * a tab closed, a phone that never answered — and nothing in the publish path
 * ever removes those. The upload authorization inside is dead after ten minutes
 * anyway, so an entry older than that is a leak holding a thing that can no
 * longer be used.
 */
class Pendings(
    private val ttlSeconds: Long = 15 * 60,
) {
    data class Pending(
        val upload: EventTemplate<Event>,
        val manifest: EventTemplate<Event>,
        val servers: List<String>,
        val relays: List<String>,
        val sha: String,
        val day: String,
        val issuedAt: Long = Instant.now().epochSecond,
    )

    private val held = ConcurrentHashMap<String, Pending>()

    operator fun set(
        draftId: String,
        pending: Pending,
    ) {
        sweep()
        held[draftId] = pending
    }

    operator fun get(draftId: String): Pending? = held[draftId]?.takeIf { it.issuedAt + ttlSeconds > Instant.now().epochSecond }

    fun remove(draftId: String) {
        held.remove(draftId)
    }

    fun sweep(): Int {
        val cutoff = Instant.now().epochSecond - ttlSeconds
        val before = held.size
        held.entries.removeIf { it.value.issuedAt <= cutoff }
        return before - held.size
    }

    fun size() = held.size
}
