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
    val lens: String,
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

    fun start(
        pubkey: String,
        forceProvisional: Boolean,
    ): String {
        running[pubkey]?.let { existing ->
            if (drafts.of(existing, pubkey)?.state == Drafts.State.RUNNING) return existing
        }
        val id = drafts.open(pubkey)
        running[pubkey] = id

        // Detached on purpose: the HTTP request that starts a run must return
        // immediately with the id, and the run outlives it. It is bound to the
        // application scope so a shutdown cancels it rather than leaving a model
        // call billing against nothing.
        scope.launch(Dispatchers.IO) { run(id, pubkey, forceProvisional) }
        return id
    }

    private suspend fun run(
        id: String,
        pubkey: String,
        forceProvisional: Boolean,
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
                    forceProvisional = forceProvisional,
                ) { step ->
                    val (text, detail) = describe(step)
                    say(text, detail)
                }

            val blob = edition.html.toByteArray()
            val summary =
                Summary(
                    lens = edition.lens.label,
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

            is Press.Step.Provisional -> {
                pair(
                    "Building a provisional lens",
                    "${step.lens.direct} follows and ${step.lens.extended} vouched-for strangers",
                )
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
