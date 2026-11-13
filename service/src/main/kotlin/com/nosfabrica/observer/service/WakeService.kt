package com.nosfabrica.observer.service

import com.nosfabrica.assistant.core.PendingStore
import com.nosfabrica.assistant.core.RunState
import com.nosfabrica.assistant.core.RunStore
import com.nosfabrica.assistant.core.WakeRequest
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.utils.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What a wake is answered (the trigger contract, v1: assistant-runner's `contract/v1/`). */
sealed interface WakeAnswer {
    /** `202`: the owner's path runs now. [joined] when a run for the owner was already under way: no second one starts. */
    data class Accepted(
        val joined: Boolean,
    ) : WakeAnswer

    /** `200 {"outcome": …}`: the occurrence's `runs` row says it is settled, nothing to do. */
    data class Settled(
        val outcome: String,
    ) : WakeAnswer

    /** `429` with `Retry-After`: every slot is busy, or the service is stopping. */
    data class Busy(
        val retryAfterSeconds: Long,
    ) : WakeAnswer

    /** `400` with `{"why": …}`. */
    data class Malformed(
        val why: String,
    ) : WakeAnswer
}

/**
 * The wakes, for one owner each: at most [parallelism] owners' paths at once, one per owner (a
 * wake for an owner already running joins that run), each on [scope] in the background. The
 * runner (assistant-runner) wakes again until a `runs` row exists, so a settled occurrence is
 * answered from that row, a cheap read, without reading a relay.
 *
 * The same slots serve the retry loop ([republishPending]), so a re-publish never overlaps a print
 * for the same owner.
 */
class WakeService(
    private val runner: ObserverRunner,
    private val runs: RunStore,
    private val pending: PendingStore,
    private val scope: CoroutineScope,
    private val parallelism: Int = ObserverRunner.PARALLELISM,
    /** False once the service is stopping: no wake starts a run, and the retry loop stops. */
    private val accepting: () -> Boolean = { true },
    private val retryAfterSeconds: Long = RETRY_AFTER_SECONDS,
) {
    init {
        require(parallelism >= 1) { "PARALLELISM must be at least 1" }
    }

    private val lock = Any()

    /** The owners whose path (a wake's run or a re-publish) is under way, and its job. */
    private val running = HashMap<HexKey, Job>()

    /** How many owners' paths are under way. */
    val busy: Int get() = synchronized(lock) { running.size }

    suspend fun wake(request: WakeRequest): WakeAnswer {
        val answer = answer(request)
        Log.i(TAG) { "wake owner=${request.owner} occurrence=${request.occurrence} answer=$answer" }
        return answer
    }

    private suspend fun answer(request: WakeRequest): WakeAnswer {
        // A wake for another task's service: the runner's TASK_URL_<TASK> points here by mistake.
        if (request.task != ObserverRunner.TASK) return WakeAnswer.Malformed("this service runs ${ObserverRunner.TASK}")
        if (!accepting()) return WakeAnswer.Busy(retryAfterSeconds)
        if (synchronized(lock) { request.owner in running }) return WakeAnswer.Accepted(joined = true)
        settled(request)?.let { return WakeAnswer.Settled(it) }
        synchronized(lock) {
            if (!accepting()) return WakeAnswer.Busy(retryAfterSeconds)
            if (request.owner in running) return WakeAnswer.Accepted(joined = true)
            if (running.size >= parallelism) return WakeAnswer.Busy(retryAfterSeconds)
            launch(request.owner) { runner.run(it) }
        }
        return WakeAnswer.Accepted(joined = false)
    }

    /**
     * The settled outcome of the wake's occurrence (a hint: a run decides its occurrence from the
     * schedule it reads), or null when there is no row, or it can't be read (then the run itself
     * finds out). A `started` row nobody here is running is another replica's run, or one that
     * died: neither is run again, so it is settled too.
     */
    private suspend fun settled(request: WakeRequest): String? {
        val row =
            try {
                runs.find(ObserverRunner.TASK, request.owner, request.occurrence)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG) { "owner=${request.owner} could not read the runs row: ${e.javaClass.simpleName}" }
                null
            } ?: return null
        return when (row.state) {
            RunState.ENDED -> row.outcome?.takeIf { OUTCOME.matches(it) } ?: RunOutcome.ATTEMPTED.name
            RunState.STARTED -> RunOutcome.ATTEMPTED.name
        }
    }

    /** Starts [block] for [owner] in a slot. Called holding [lock]; the slot is freed when it ends, however it ends. */
    private fun launch(
        owner: HexKey,
        block: suspend (HexKey) -> RunOutcome,
    ): Job {
        // Lazy, so it can't finish (and free its slot) before it is in [running].
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    block(owner)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG) { "owner=$owner the run failed: ${e.javaClass.simpleName}" }
                }
            }
        job.invokeOnCompletion { synchronized(lock) { if (running[owner] === job) running.remove(owner) } }
        running[owner] = job
        job.start()
        // A scope already cancelled (the process stopping) completes it at once, before it was in [running].
        if (job.isCompleted) running.remove(owner)
        return job
    }

    /**
     * One pass of the retry loop: drops the papers past their time, then publishes each owner's
     * waiting papers again ([ObserverRunner.republish]: only while their newest schedule still names
     * the Assistant), one owner at a time in a free slot. An owner whose path is under way is left
     * for the next pass, as is everyone when no slot is free.
     */
    suspend fun republishPending() {
        runner.dropExpiredPending()
        val owners =
            try {
                pending.owners(ObserverRunner.TASK)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG) { "could not list the pending papers: ${e.javaClass.simpleName}" }
                return
            }
        for (owner in owners) {
            if (!accepting()) return
            val job =
                synchronized(lock) {
                    if (owner in running || running.size >= parallelism) null else launch(owner) { runner.republish(it) }
                } ?: continue
            job.join()
        }
    }

    /**
     * Waits up to [graceMs] for every path under way to finish (the caller has stopped
     * [accepting]). True when they all did. Their papers are in the store either way.
     */
    suspend fun drain(graceMs: Long): Boolean = withTimeoutOrNull(graceMs) { idle() } != null

    /** Returns once no owner's path is under way, those started meanwhile included. */
    suspend fun idle() {
        while (true) {
            val jobs = synchronized(lock) { running.values.toList() }
            if (jobs.isEmpty()) return
            jobs.joinAll()
        }
    }

    companion object {
        private const val TAG = "WakeService"

        /** What a busy service asks the runner to wait: about one print's model calls. */
        const val RETRY_AFTER_SECONDS = 60L

        /** An outcome as `SettledAnswer` takes it; a row a newer build wrote with anything else reads as ATTEMPTED. */
        private val OUTCOME = Regex("[A-Z][A-Z0-9_]{0,63}")
    }
}
