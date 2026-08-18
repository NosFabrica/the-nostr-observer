package com.nosfabrica.observer.press

import com.nosfabrica.observer.Press
import com.nosfabrica.observer.nostr.Readiness
import com.nosfabrica.observer.press.publish.Blossom
import com.nosfabrica.observer.press.store.Continuities
import com.nosfabrica.observer.press.store.Drafts
import com.nosfabrica.observer.write.Masthead
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * What the browser is told while an edition is being made.
 *
 * A run is a minute of relay reads and several minutes of generation, so the
 * page cannot simply wait on a response. It polls, and this is what it reads:
 * a growing list of lines, each one a thing that has actually happened. Not a
 * percentage, because there is no honest denominator, and a bar that crawls to
 * 90% and sits there is worse than a list that says what it is doing.
 */
@Serializable
data class Line(
    val at: Long,
    val text: String,
    val detail: String? = null,
)

@Serializable
data class Summary(
    val events: Int,
    val voices: Int,
    val control: Int,
    val overlap: Int,
    val bytes: Int,
    val costUsd: Double,
    val publishable: Boolean,
    val violations: List<String>,
)

/**
 * One edition at a time, per reader.
 *
 * Generation costs real money and reads somebody else's relay hard. Two clicks
 * on a slow button must not become two runs, so a reader with a job already
 * running gets handed the one they have.
 */
class Editions(
    private val press: Press,
    private val drafts: Drafts,
    private val continuities: Continuities,
    private val scope: CoroutineScope,
) {
    private val json = Json { encodeDefaults = true }
    private val running = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun start(pubkey: String): String {
        var started: String? = null

        // compute(), not get-then-put. Check-then-act here is a race between two
        // clicks on a slow button, and losing it means two model calls and two
        // bills for one edition. ConcurrentHashMap.compute holds the bin lock
        // for this key, so the check and the claim are one step.
        val id =
            running.compute(pubkey) { _, existing ->
                if (existing != null && drafts.of(existing, pubkey)?.state == Drafts.State.RUNNING) {
                    existing
                } else {
                    drafts.open(pubkey).also { started = it }
                }
            }!!

        // Launched outside compute: the mapping function must be short and must
        // not call back into the map, and a coroutine that finishes fast enough
        // to call running.remove() from inside it would deadlock.
        started?.let { fresh -> scope.launch(Dispatchers.IO) { run(fresh, pubkey) } }
        return id
    }

    private suspend fun run(
        id: String,
        pubkey: String,
    ) {
        val lines = mutableListOf<Line>()

        fun say(
            text: String,
            detail: String? = null,
        ) {
            lines += Line(Instant.now().epochSecond, text, detail)
            drafts.progress(id, json.encodeToString(lines))
        }

        try {
            val edition =
                press.edition(
                    observer = pubkey,
                    until = Instant.now().epochSecond,
                    continuity = continuities.of(pubkey),
                ) { step ->
                    val (text, detail) = describe(step)
                    say(text, detail)
                }

            val blob = edition.html.toByteArray()
            val summary =
                Summary(
                    events = edition.corpus.all().size,
                    voices =
                        edition.corpus
                            .all()
                            .map { it.pubKey }
                            .distinct()
                            .size,
                    control = edition.corpus.control.size,
                    overlap =
                        edition.corpus
                            .all()
                            .map { it.id }
                            .intersect(
                                edition.corpus.control
                                    .map { it.id }
                                    .toSet(),
                            ).size,
                    bytes = blob.size,
                    costUsd = edition.usage.costUsd(),
                    publishable = edition.publishable,
                    violations = edition.report.violations.map { "${it.kind}: ${it.detail}" },
                )
            drafts.ready(id, edition.html, Blossom.sha256(blob), json.encodeToString(summary), json.encodeToString(lines))

            // Tomorrow's paper is the same paper. Recorded after the edition is
            // safely stored, because a failure to remember a masthead must not
            // cost the reader a page they already have.
            val next = Masthead.next(edition.rawHtml, continuities.of(pubkey))
            continuities.remember(pubkey, next.masthead, next.motto, next.sections, next.recentHeadlines)
        } catch (refused: Press.Refused) {
            say("Stopped", refused.message)
            drafts.failed(id, refused.message, json.encodeToString(lines))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // A shutdown is not an edition that failed. Recording it as one
            // would leave a FAILED draft the reader is told to retry, on a
            // process that is going away.
            throw cancelled
        } catch (failure: Exception) {
            // The reader gets the class and the message, not a stack trace: the
            // interesting half of a failure here is nearly always "which relay"
            // or "which API", and the rest belongs in the log.
            say("Failed", failure.message ?: failure::class.simpleName)
            drafts.failed(id, failure.message ?: failure::class.simpleName ?: "unknown failure", json.encodeToString(lines))
        } finally {
            running.remove(pubkey, id)
        }
    }

    private fun describe(step: Press.Step): Pair<String, String?> =
        when (step) {
            is Press.Step.Reading -> {
                pair("Reading your web of trust", step.relay)
            }

            is Press.Step.Lensed -> {
                pair("Lens: ${step.verdict.state}", Readiness.explain(step.verdict))
            }

            is Press.Step.Pulled -> {
                pair(
                    "Read ${step.events} posts from ${step.voices} people",
                    "${step.overlap} of ${step.control} unranked posts made it in",
                )
            }

            is Press.Step.Digested -> {
                pair("Chose ${step.kept} of them", "${step.art} pictures on the shortlist")
            }

            Press.Step.Writing -> {
                pair("Writing your front page", "this is the slow part")
            }

            is Press.Step.Written -> {
                pair("Wrote ${step.chars} characters", "$${"%.2f".format(step.costUsd)}")
            }

            is Press.Step.Cleaned -> {
                if (step.removed.isEmpty()) {
                    pair("Checked the markup", "nothing removed")
                } else {
                    pair("Cleaned the markup", step.removed.joinToString("; "))
                }
            }

            is Press.Step.Checked -> {
                pair("Checked every quote", step.report.summary())
            }
        }
}

private fun pair(
    text: String,
    detail: String? = null,
) = text to detail
