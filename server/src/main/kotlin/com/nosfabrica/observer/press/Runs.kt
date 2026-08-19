package com.nosfabrica.observer.press

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import java.security.SecureRandom
import java.time.Instant
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap

/**
 * A press run, in memory, for as long as it takes to finish one.
 *
 * NOTHING ABOUT AN EDITION IS WRITTEN DOWN ANY MORE. There was a `drafts`
 * table: a row per generation, holding the finished page so a reader could
 * read it, think about it, and press Publish later. Removing the review step
 * removed the reason for it — a run now goes from "print" to "published"
 * without stopping, and the only thing that has to survive in between is the
 * few minutes it takes the model to write and the reader to sign.
 *
 * So the page lives here, in memory, and then it lives on the reader's own
 * servers. A restart loses a run in flight, which costs whoever was mid-print
 * a dollar and a retry; it does not lose an edition, because an edition that
 * finished is published and one that did not was never anything.
 *
 * This also merges what `Pendings` did. The templates and the bytes they
 * authorize were held in two places keyed by the same id, which is one place
 * too many for two halves of one thing.
 */
class Runs(
    /**
     * Long enough for a slow model and a slow signer, and no longer.
     *
     * The upload authorization inside a finished run expires in ten minutes,
     * so an entry older than this is holding a page nobody can publish. Without
     * a sweep, every abandoned print — a declined prompt, a closed tab, a phone
     * that never answered — is a leak the size of an edition.
     */
    val ttlSeconds: Long = 30 * 60,
) {
    enum class State {
        /** The pipeline is working. */
        RUNNING,

        /** Written and checked. The reader's signer is the only thing left. */
        SIGNING,

        /** On their servers, announced on their relays. */
        PUBLISHED,

        /** It did not work, and [Run.error] says why in words. */
        FAILED,
    }

    class Run(
        val id: String,
        val pubkey: String,
        val startedAt: Long = Instant.now().epochSecond,
    ) {
        @Volatile var state: State = State.RUNNING

        @Volatile var lines: List<Line> = emptyList()

        /** The finished page. Held only until it is somebody else's. */
        @Volatile var html: ByteArray? = null

        @Volatile var sha: String? = null

        @Volatile var day: String? = null

        @Volatile var upload: EventTemplate<Event>? = null

        @Volatile var manifest: EventTemplate<Event>? = null

        @Volatile var servers: List<String> = emptyList()

        @Volatile var relays: List<String> = emptyList()

        @Volatile var summary: String? = null

        @Volatile var report: String? = null

        @Volatile var error: String? = null

        /**
         * Once it is theirs, we do not need our copy.
         *
         * Not a memory optimisation — a promise. "We keep nothing" is either
         * true at a particular line of code or it is a sentence in a footer.
         */
        fun forget() {
            html = null
            upload = null
            manifest = null
        }
    }

    private val runs = ConcurrentHashMap<String, Run>()
    private val random = SecureRandom()

    /**
     * One run at a time, per reader.
     *
     * Generation costs real money and reads somebody else's relay hard, so two
     * clicks on a slow button must not become two runs. `compute` holds the bin
     * lock for the key, so the check and the claim are one step — a
     * get-then-put here is a race that only appears under exactly the condition
     * that causes it.
     */
    fun open(pubkey: String): Pair<Run, Boolean> {
        var fresh = false
        val run =
            runs.compute(pubkey) { _, existing ->
                if (existing != null && existing.state == State.RUNNING) {
                    existing
                } else {
                    fresh = true
                    Run(HexFormat.of().formatHex(ByteArray(16).also(random::nextBytes)), pubkey)
                }
            }!!
        return run to fresh
    }

    /** Their current run, whatever state it is in. */
    fun of(pubkey: String): Run? = runs[pubkey]

    /**
     * The run named in a URL, and only if this reader owns it.
     *
     * The pubkey is compared here rather than trusted from the path, because a
     * run id is a bearer token otherwise.
     */
    fun of(
        id: String,
        pubkey: String,
    ): Run? = runs[pubkey]?.takeIf { it.id == id }

    fun sweep(): Int {
        val cutoff = Instant.now().epochSecond - ttlSeconds
        val gone = runs.entries.filter { it.value.startedAt < cutoff && it.value.state != State.RUNNING }
        gone.forEach { runs.remove(it.key, it.value) }
        return gone.size
    }
}
