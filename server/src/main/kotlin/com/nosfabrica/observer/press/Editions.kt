package com.nosfabrica.observer.press

import com.nosfabrica.observer.Press
import com.nosfabrica.observer.nostr.Readiness
import com.nosfabrica.observer.press.publish.Announce
import com.nosfabrica.observer.press.publish.Blossom
import com.nosfabrica.observer.press.publish.Templates
import com.nosfabrica.observer.press.store.Continuities
import com.nosfabrica.observer.write.Masthead
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

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
    private val runs: Runs,
    private val announce: Announce,
    private val continuities: Continuities,
    private val scope: CoroutineScope,
) {
    private val json = Json { encodeDefaults = true }

    /**
     * The reader's own timezone, or UTC if they did not say.
     *
     * Whatever the browser reports lands here, so it is checked against the
     * tz database before it becomes a `ZoneId` -- an unknown id throws, and a
     * thrown exception inside a launched coroutine fails an edition for a
     * reason nobody would guess from the message.
     */
    internal fun zoneOf(name: String?): ZoneId =
        name
            ?.takeIf { it in ZoneId.getAvailableZoneIds() }
            ?.let(ZoneId::of)
            ?: ZoneOffset.UTC

    fun start(
        pubkey: String,
        timezone: String? = null,
    ): Runs.Run {
        val (run, fresh) = runs.open(pubkey)
        // Launched outside the map's compute: a coroutine that finished fast
        // enough to touch the map from inside it would deadlock.
        if (fresh) scope.launch(Dispatchers.IO) { run(run, zoneOf(timezone)) }
        return run
    }

    private suspend fun run(
        run: Runs.Run,
        zone: ZoneId,
    ) {
        val lines = mutableListOf<Line>()

        fun say(
            text: String,
            detail: String? = null,
        ) {
            lines += Line(Instant.now().epochSecond, text, detail)
            run.lines = lines.toList()
        }

        try {
            val edition =
                press.edition(
                    observer = run.pubkey,
                    until = Instant.now().epochSecond,
                    continuity = continuities.of(run.pubkey),
                    zone = zone,
                ) { step ->
                    val (text, detail) = describe(step)
                    say(text, detail)
                }

            val blob = edition.html.toByteArray()
            run.summary =
                json.encodeToString(
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
                    ),
                )

            // THE VALIDATOR IS THE ONLY GATE LEFT.
            //
            // There used to be a reader between the checks and the world: an
            // edition that failed them was simply not offered, and one that
            // passed still waited for somebody to press Publish. Publishing
            // straight through removes the second half of that, which means the
            // first half is now the whole of it -- a page that misquotes
            // somebody must stop here, because the next step is the reader's
            // permanent archive.
            if (!edition.publishable) {
                say("Not published", "it failed its own checks: " + edition.report.violations.joinToString("; ") { it.kind.name })
                run.error = "This edition quoted something it could not find in a source event, so it was not published."
                run.state = Runs.State.FAILED
                return
            }

            // Where it goes, asked now rather than remembered: this is the
            // moment before it is sent, and a stale list publishes to an
            // address they have moved away from.
            val relays = press.writeRelaysOf(run.pubkey)
            val servers = announce.servers(run.pubkey, relays)
            if (servers.isEmpty()) {
                say("Not published", "nowhere to put it")
                run.error =
                    "You have not set up anywhere to store files, so there is nowhere to publish to. " +
                    "Add one in your usual Nostr app and print again."
                run.state = Runs.State.FAILED
                return
            }

            val now = Instant.now().epochSecond
            val day = DAY.format(Instant.ofEpochSecond(now).atZone(zone))
            val sha = Blossom.sha256(blob)
            run.html = blob
            run.sha = sha
            run.day = day
            run.servers = servers
            run.relays = relays
            // Read before the manifest is built, because the lead headline goes
            // INTO it: a back issue that lists only its date tells a reader
            // nothing about which paper was which.
            val next = Masthead.next(edition.rawHtml, continuities.of(run.pubkey))
            run.upload = Templates.uploadAuth(sha, blob.size.toLong(), now, now + 600)
            run.manifest = Templates.manifest(day, sha, servers, next.masthead, next.recentHeadlines.firstOrNull(), now)
            say("Ready to publish", "your signer will ask twice")
            run.state = Runs.State.SIGNING

            // Tomorrow's paper is the same paper. Remembered before the signing
            // round trip, because a reader who declines still made this page and
            // the masthead they were given is the one they should see again.
            continuities.remember(run.pubkey, next.masthead, next.motto, next.sections, next.recentHeadlines)
        } catch (refused: Press.Refused) {
            say("Stopped", refused.message)
            run.error = refused.message
            run.state = Runs.State.FAILED
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // A shutdown is not an edition that failed. Recording it as one
            // would leave a FAILED run the reader is told to retry, on a process
            // that is going away.
            throw cancelled
        } catch (failure: Exception) {
            // The reader gets the class and the message, not a stack trace: the
            // interesting half of a failure here is nearly always "which relay"
            // or "which API", and the rest belongs in the log.
            say("Failed", failure.message ?: failure::class.simpleName)
            run.error = failure.message ?: failure::class.simpleName ?: "unknown failure"
            run.state = Runs.State.FAILED
        }
    }

    private fun describe(step: Press.Step): Pair<String, String?> =
        when (step) {
            is Press.Step.Reading -> {
                pair("Reading your web of trust", step.relay)
            }

            // The reader is watching a progress list, not reading a status
            // page. `Lens: READY` and "unranked posts" are how this code talks
            // to itself; the sentence beside them already says the same thing
            // in words, so the label says the plain half and the detail carries
            // the rest.
            is Press.Step.Lensed -> {
                pair("Checked your web of trust", Readiness.explain(step.verdict))
            }

            is Press.Step.Pulled -> {
                pair(
                    "Read ${step.events} posts from ${step.voices} people" +
                        (step.surfaced?.let { " of $it we could see" } ?: ""),
                    "reading the same window without your web of trust returns ${step.control} posts, " +
                        "${step.overlap} of them the same",
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

            is Press.Step.Proofed -> {
                // The reader is watching a progress line, not reading a report.
                // What they need to know is whether their page is being written
                // a second time and why, because that is the only step here
                // that costs them a wait they did not expect.
                when {
                    step.fellBack -> pair("Fell back to the house layout", step.report.summary())
                    step.report.ok -> pair("Opened it in a browser", step.report.summary())
                    step.attempt == 1 -> pair("It did not read; writing it again", step.report.summary())
                    else -> pair("It still did not read", step.report.summary())
                }
            }
        }
}

/** The day this edition is for, in the reader's own zone. */
private val DAY =
    java.time.format.DateTimeFormatter
        .ofPattern("yyyy-MM-dd")

private fun pair(
    text: String,
    detail: String? = null,
) = text to detail
