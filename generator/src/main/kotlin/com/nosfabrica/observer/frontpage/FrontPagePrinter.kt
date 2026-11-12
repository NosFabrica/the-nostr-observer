/*
 * Copyright (c) 2025 Vitor Pamplona
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.nosfabrica.observer.frontpage

import com.vitorpamplona.quartz.nipFAAgentKeys.tags.AgentKeyTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlin.concurrent.Volatile

/*
 * The account-free half of Amethyst's FrontPagePrinter.kt. The `FrontPagePrinter` class itself
 * (one account's print, its local store, notifications, the per-device morning claim) is the
 * app's, and is not ported: the Assistant's worker (`runner/`) is this repo's printer.
 * [FrontPageGate] and [FrontPageRun] come from Amethyst's FrontPageViewModel.kt.
 */

/** What stands between the reader and a paper, one thing at a time. */
enum class FrontPageGate {
    /** No AI key in kind 12000: nothing can write the page. */
    NO_KEY,

    /** No rank provider in the reader's kind 10040: the relay would rank through nobody. */
    NO_LENS,

    /** No HTML boundary on this platform yet: an unchecked page never ships. */
    UNSUPPORTED,
    READY,
}

sealed interface FrontPageRun {
    data object Idle : FrontPageRun

    /** What [FrontPagePrinter.printNow] returns for a print the reader cancelled; [FrontPagePrinter.run] shows [Idle]. */
    data object Cancelled : FrontPageRun

    class Running(
        val step: PrintStep,
    ) : FrontPageRun

    class Done(
        val result: PrintResult,
    ) : FrontPageRun

    class Failed(
        val failure: WriterFailure,
        val status: Int? = null,
        val providerSaid: String = "",
    ) : FrontPageRun
}

/** Who asked for a print: the reader, or their morning schedule. */
enum class PrintTrigger { MANUAL, SCHEDULED }

/** What a scheduled run could not get past on its own. */
enum class ScheduledOutcome {
    PRINTED,
    FAILED,
    NO_KEY,
    NO_LENS,
    NEEDS_SIGNER,
    UNSUPPORTED,
    BUSY,

    /** Today's edition already exists (a manual print, another device, Brainstorm): done for today. */
    ALREADY_PRINTED,

    /** This device doesn't print the mornings, the schedule is off, or it can't be run here. */
    NOT_RUNNER,

    /** No occurrence has come yet. */
    NOT_DUE,

    /** Another device (or this one, earlier) claimed this occurrence. */
    ALREADY_CLAIMED,

    /** Another device's claim won, before or during the print. */
    LOST_CLAIM,

    /** Not enough of the reader's relays answered to claim safely. */
    NO_QUORUM,

    /**
     * This install already ran this occurrence and the run ended (it failed, or was stopped): it
     * isn't run again on its own, since the failed run may have been paid for. The reader can
     * still print by hand.
     */
    ALREADY_ATTEMPTED,

    /** The reader cancelled the print, or the platform stopped the run. */
    CANCELLED,
}

/** What stands between the reader and a paper, from the key, the lens and the platform. */
fun gateOf(
    key: AgentKeyTag?,
    hasLens: Boolean,
    boundary: PaperBoundary?,
): FrontPageGate =
    when {
        boundary == null -> FrontPageGate.UNSUPPORTED
        key == null -> FrontPageGate.NO_KEY
        !hasLens -> FrontPageGate.NO_LENS
        else -> FrontPageGate.READY
    }

/** How the last publish to the reader's Observer site went. */
sealed interface ObserverStatus {
    data object Idle : ObserverStatus

    /** A clean print would publish, but the reader hasn't said yes to a public site yet. */
    data object AwaitingConsent : ObserverStatus

    data object Publishing : ObserverStatus

    data class Published(
        val at: Long,
    ) : ObserverStatus

    data class Failed(
        val reason: ObserverFailure,
    ) : ObserverStatus
}

/** How a scheduled print's [ended] run reads as a [ScheduledOutcome]. */
fun scheduledOutcomeOf(ended: FrontPageRun?): ScheduledOutcome =
    when {
        // The press was taken: nothing was started.
        ended == null -> ScheduledOutcome.BUSY
        ended is FrontPageRun.Done && ended.result is PrintResult.Printed -> ScheduledOutcome.PRINTED
        ended == FrontPageRun.Cancelled -> ScheduledOutcome.CANCELLED
        // The page was dropped: another device's claim won before it was kept.
        ended == FrontPageRun.Idle -> ScheduledOutcome.LOST_CLAIM
        else -> ScheduledOutcome.FAILED
    }

/** The reader's Cancel, as the print's cancellation cause. */
class PrintCancelled : CancellationException("The reader cancelled the print")

/**
 * One print at a time ([tryHold], [release]), and a Cancel that stops whichever print is running
 * ([runCancellable]): the reader's, or the morning's that a scheduled run holds the slot for from
 * before its claim to its end.
 */
class PrintSlot {
    private val mutex = Mutex()

    @Volatile
    private var current: Job? = null

    /** Whether a print (or a scheduled run on its way to one) holds the slot. */
    val isHeld: Boolean get() = mutex.isLocked

    fun tryHold(): Boolean = mutex.tryLock()

    fun release() = mutex.unlock()

    /**
     * [body] as its own job, so [cancel] stops it alone: then [cancelled] comes back and the
     * caller (a scheduled run) carries on to record and say how it ended. A caller that is
     * itself cancelled (the platform stopping its run) is cancelled as usual.
     */
    suspend fun <T> runCancellable(
        cancelled: T,
        body: suspend () -> T,
    ): T =
        coroutineScope {
            val work = async { body() }
            current = work
            try {
                work.await()
            } catch (e: CancellationException) {
                if (work.isCancelled && isActive) cancelled else throw e
            } finally {
                if (current === work) current = null
            }
        }

    /** Stops the print running now, if any. */
    fun cancel() {
        current?.cancel(PrintCancelled())
    }
}

/**
 * Who was asked, for the log: the provider as the reader knows it and the model id. Never the key,
 * and never the URL's path or query (a custom endpoint may carry a token there): only its host.
 */
fun askedOf(
    key: AgentKeyTag,
    model: AgentModel,
): String {
    val provider = AgentProviderPresets.forUrl(key.url)?.name ?: AgentProviderPresets.hostOf(key.url)?.substringAfterLast('@') ?: "?"
    return "$provider \u00b7 ${model.id}"
}

/** The hash an edition is published under: its bytes once the paper's CSP is in ([withPaperCsp]). */
fun publishedSha256(html: String): String = ObserverPublisher.sha256Hex(withPaperCsp(html).encodeToByteArray())

/**
 * Whether [next] is worth a new [FrontPageRun.Running]: every step change is, but the writer's
 * running character count only every [WRITE_PROGRESS_CHARS], since the stream reports each chunk.
 */
fun progressWorthShowing(
    shown: PrintStep,
    next: PrintStep,
): Boolean {
    if (next !is PrintStep.Write || shown !is PrintStep.Write || next.round != shown.round) return true
    if (next.phase != shown.phase || next.section != shown.section) return true
    return next.chars - shown.chars >= WRITE_PROGRESS_CHARS
}

const val WRITE_PROGRESS_CHARS = 400
