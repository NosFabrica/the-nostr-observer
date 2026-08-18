package com.nosfabrica.observer

import com.anthropic.models.messages.OutputConfig
import com.nosfabrica.observer.corpus.Art
import com.nosfabrica.observer.corpus.ArtDesk
import com.nosfabrica.observer.corpus.Digest
import com.nosfabrica.observer.nostr.Corpus
import com.nosfabrica.observer.nostr.Readiness
import com.nosfabrica.observer.nostr.ReadinessProbe
import com.nosfabrica.observer.nostr.Relays
import com.nosfabrica.observer.safe.Sanitizer
import com.nosfabrica.observer.safe.Validator
import com.nosfabrica.observer.write.Continuity
import com.nosfabrica.observer.write.Writer

/** 24 hours, fixed. Settled in the plan; not a knob. */
const val WINDOW_SECONDS = 24L * 60 * 60

/**
 * The pipeline, once, for both callers.
 *
 * The CLI and the web app must run the SAME steps in the same order or the
 * thing verified by a `--dry-run` is not the thing a reader publishes. This
 * class exists so there is only one place that decides what an edition is;
 * `Main` and the server differ only in how they report [Step]s and what they
 * do with the result.
 */
class Press(
    private val relays: Relays,
    private val searchRelay: String,
    private val effort: OutputConfig.Effort = OutputConfig.Effort.HIGH,
) {
    /**
     * One writer, not one per edition.
     *
     * Each `Writer` lazily builds an `AnthropicOkHttpClient`, and each of those
     * owns a connection pool and thread pools. Building one per edition leaks
     * both for the life of the process, which a CLI run never notices and a
     * server does.
     */
    private val writer by lazy { Writer(effort = effort) }

    /**
     * Progress, as it happens.
     *
     * A full edition is a minute of relay reads and several minutes of
     * generation. Anything watching it — a console, a browser polling, a log —
     * needs to know which of those it is waiting on, so the steps are pushed
     * rather than inferred from elapsed time.
     */
    sealed interface Step {
        data class Reading(
            val relay: String,
            val observer: String,
        ) : Step

        data class Lensed(
            val verdict: Readiness.Verdict,
        ) : Step

        data class Pulled(
            val events: Int,
            val voices: Int,
            val profiles: Int,
            val control: Int,
            val overlap: Int,
        ) : Step

        data class Digested(
            val kept: Int,
            val dropped: Int,
            val approxTokens: Int,
            val art: Int,
        ) : Step

        data object Writing : Step

        data class Written(
            val chars: Int,
            val inputTokens: Long,
            val outputTokens: Long,
            val costUsd: Double,
        ) : Step

        data class Cleaned(
            val removed: List<String>,
        ) : Step

        data class Checked(
            val report: Validator.Report,
        ) : Step
    }

    /** Why an edition did not happen. Each one is a different thing to tell a reader. */
    class Refused(
        val reason: Reason,
        override val message: String,
    ) : Exception(message) {
        enum class Reason {
            /** The window held nothing. A real answer, and not a paper. */
            QUIET,

            /**
             * No usable lens, so there is no ranked paper to print.
             *
             * There used to be a fallback here: a provisional edition built from
             * the reader's follows and follows-of-follows. It was removed. It
             * produced a RECENCY feed and presented it as the product, so a
             * first-time reader's first impression was the one version that
             * cannot show what the product is for -- measured, an overlap of 0
             * of 400 with the unranked control where a real lens gives 1. The
             * readiness chain already says exactly which link is unmet, and that
             * is a better answer than a paper that misrepresents itself.
             */
            NO_LENS,
        }
    }

    data class Edition(
        val observer: String,
        val since: Long,
        val until: Long,
        val html: String,
        /**
         * What the model wrote, before the sanitizer.
         *
         * Kept for exactly one reason: the masthead announcement is an HTML
         * comment and the sanitizer drops comments, so by the time a page is
         * safe to serve, the thing that says what the paper is now called is
         * gone. Never serve this.
         */
        val rawHtml: String,
        val corpus: Corpus,
        val art: List<Art>,
        val digest: Digest.Rendered,
        val usage: Writer.Draft,
        val removed: List<String>,
        val report: Validator.Report,
    ) {
        /** A page that fails the check is never offered for publication. */
        val publishable: Boolean get() = report.ok
    }

    /** Where this reader's own events go, without running the whole chain to find out. */
    suspend fun writeRelaysOf(observer: String): List<String> = ReadinessProbe(relays, searchRelay).writeRelaysOf(observer)

    /** Their Blossom servers, from their own kind 10063. */
    suspend fun blossomServers(
        observer: String,
        hosts: List<String>,
    ): List<String> = ReadinessProbe(relays, searchRelay).blossomServers(observer, hosts)

    /**
     * Can this reader publish, asked BEFORE anything is written.
     *
     * Separate from [readiness] because the two chains fail independently: no
     * media server is not a broken lens, and a reader with one and not the
     * other should be told exactly which.
     */
    suspend fun storage(
        observer: String,
        writeRelays: List<String>,
        publishedBefore: Boolean? = null,
    ): Readiness.Verdict {
        val servers = blossomServers(observer, writeRelays)
        return Readiness.storage(Readiness.Storage(serverListSeen = true, servers = servers, publishedBefore = publishedBefore))
    }

    suspend fun readiness(
        observer: String,
        since: Long,
    ): Pair<Readiness.Facts, Readiness.Verdict> {
        val facts = ReadinessProbe(relays, searchRelay).gather(observer, since)
        return facts to Readiness.assess(facts)
    }

    /**
     * Everything up to but not including the model call.
     *
     * Split out because it is the whole of `--dry-run` and the whole of what a
     * preview costs before anybody spends money, and because the server wants
     * to report the corpus size to the reader while the model is still writing.
     */
    suspend fun gather(
        observer: String,
        until: Long,
        onStep: (Step) -> Unit = {},
    ): Triple<Corpus, List<Art>, Digest.Rendered> {
        val since = until - WINDOW_SECONDS
        onStep(Step.Reading(searchRelay, observer))

        // Pre-flight before anything expensive. The failure it catches is
        // silent by design: an unresolvable observer degrades to an anonymous
        // read, which on a measured window was 209 of 400 posts from one spam
        // account. Finding that out after the model call is finding it late.
        val (_, verdict) = readiness(observer, since)
        onStep(Step.Lensed(verdict))

        // The lens is a precondition, not a preference. Every filter this then
        // sends carries `observer:<pk> sort:rank`, and that token resolving to
        // nothing does not error -- it quietly becomes the anonymous ranking. So
        // the chain is the gate, and a reader who has not cleared it is told
        // which link is unmet rather than handed a paper built some other way.
        if (!verdict.ranks) throw Refused(Refused.Reason.NO_LENS, Readiness.explain(verdict))

        val corpus =
            com.nosfabrica.observer.nostr
                .Pull(relays, searchRelay)
                .corpus(observer, since, until)
        onStep(
            Step.Pulled(
                events = corpus.all().size,
                voices =
                    corpus
                        .all()
                        .map { it.pubKey }
                        .distinct()
                        .size,
                profiles = corpus.profiles.size,
                control = corpus.control.size,
                // The product thesis as a number, and the alarm for one specific
                // bug: the control run is kind 1 like the notes desk, so anything
                // that merges the two shows up here near 100% instead of near zero.
                overlap =
                    corpus
                        .all()
                        .map { it.id }
                        .intersect(corpus.control.map { it.id }.toSet())
                        .size,
            ),
        )

        if (corpus.notes.isEmpty()) {
            throw Refused(
                Refused.Reason.QUIET,
                "Nothing came back for this window through your web of trust. A quiet day is a real " +
                    "answer and a thin paper is the right response to it, but with zero notes there " +
                    "is no paper at all.",
            )
        }

        val art = ArtDesk.shortlist(corpus)
        val digest = Digest().render(corpus, art)
        onStep(Step.Digested(digest.kept, digest.dropped, digest.approxTokens, art.size))
        return Triple(corpus, art, digest)
    }

    suspend fun edition(
        observer: String,
        until: Long,
        continuity: Continuity = Continuity(),
        onStep: (Step) -> Unit = {},
    ): Edition {
        val (corpus, art, digest) = gather(observer, until, onStep)

        onStep(Step.Writing)
        val draft = writer.write(corpus, digest, art, continuity)
        onStep(Step.Written(draft.html.length, draft.inputTokens, draft.outputTokens, draft.costUsd()))

        val sanitized = Sanitizer(art, corpus.all().map { it.id }.toSet()).sanitize(draft.html)
        onStep(Step.Cleaned(sanitized.removed))

        val report = Validator(corpus, art).validate(sanitized.html)
        onStep(Step.Checked(report))

        return Edition(
            observer = observer,
            since = corpus.since,
            until = corpus.until,
            html = sanitized.html,
            rawHtml = draft.html,
            corpus = corpus,
            art = art,
            digest = digest,
            usage = draft,
            removed = sanitized.removed,
            report = report,
        )
    }
}
