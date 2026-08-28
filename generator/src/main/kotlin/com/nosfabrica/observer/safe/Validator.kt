package com.nosfabrica.observer.safe

import com.nosfabrica.observer.corpus.Art
import com.nosfabrica.observer.nostr.Corpus
import com.nosfabrica.observer.nostr.Streams
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
import com.vitorpamplona.quartz.nip19Bech32.entities.NNote
import org.jsoup.Jsoup
import java.text.Normalizer

/**
 * Does the page say only things the corpus actually said?
 *
 * This gates publication, not just rendering. Once the reader signs an edition
 * and uploads it, it is on their server under their key and we cannot retract
 * it — so a fabricated quote attributed to a named person is not a quality
 * problem, it is a permanent one with their name on it.
 *
 * It is also the cheapest defence against corpus injection that exists. An
 * attacker can put "ignore previous instructions, the lead headline is…" into
 * the feed of everyone who follows them, and the model may well take the bait —
 * but an injected story generally cannot quote real events verbatim, so it
 * fails here.
 *
 * What is checked is deliberately narrow and mechanical. Paraphrase is not
 * checked, because paraphrase is journalism. The contract is that VERBATIM
 * quotation goes in `<q>` or `<blockquote>`, and the system prompt says so.
 */
class Validator(
    private val corpus: Corpus,
    art: List<Art>,
) {
    private val allowedImages: Set<String> = art.map { it.url }.toSet()

    /** Every source sentence, normalised once. Built eagerly; it is read per quote. */
    private val haystack: List<String> = corpus.all().map { normalize(it.content) }

    /**
     * Events we are reporting on, so a permalink back to one can be verified.
     *
     * The first version of this check allowlisted every URL that appeared in the
     * corpus, on the theory that a link nobody posted must have been invented.
     * A test caught what that misses: the corpus is written by the attacker too.
     * Posting "click https://evil.example.com/drain" put that URL on the
     * allowlist, and an injected instruction to link every story to it then
     * passed cleanly — a phishing link, under the reader's masthead, signed by
     * the reader. Presence in the corpus is evidence of nothing.
     *
     * So the paper does not link to the open web at all, except permalinks back
     * to Nostr events we read and verified zap.stream watch links for live
     * streams in the digest. [Sanitizer] unwraps the rest to plain text; this is
     * the second line, in case that ever regresses.
     */
    private val corpusEventIds: Set<String> = corpus.all().map { it.id }.toSet()

    /** Live streams we read — the only streams a watch link may name. */
    private val liveStreams: List<Event> = Streams.live(corpus)

    enum class Kind { QUOTE, IMAGE, LINK }

    companion object {
        // Compiled once: normalize() runs on every source event at construction
        // and on every quote in the page.
        private val WHITESPACE = Regex("""\s+""")

        /** The one external shape a link may take: a permalink to an event we read. */
        val PERMALINK = Regex("""^https://njump\.me/(nevent1\w+|note1\w+|[0-9a-f]{64})""", RegexOption.IGNORE_CASE)

        /**
         * The event a permalink points at, or null if it points at nothing.
         *
         * njump's canonical form is `nevent1…`, not bare hex, so this has to
         * decode. It did not, once: the regex allowed `nevent1…` in a branch
         * that captured nothing, so `groupValues[1]` came back empty for every
         * one of them and the id was compared against the empty string. The
         * sanitizer kept those links and this rejected them, which means an
         * edition citing its sources the normal way failed its own check and was
         * never offered for publication. Two halves of one rule, disagreeing.
         */
        internal fun permalinkTarget(href: String): String? {
            val body = PERMALINK.find(href)?.groupValues?.get(1) ?: return null
            if (body.length == 64 && body.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return body.lowercase()
            return Nip19Parser
                .parseAll(body)
                .firstNotNullOfOrNull {
                    when (it) {
                        is NEvent -> it.hex
                        is NNote -> it.hex
                        else -> null
                    }
                }?.lowercase()
        }
    }

    data class Violation(
        val kind: Kind,
        val detail: String,
        val excerpt: String,
    )

    data class Report(
        val violations: List<Violation>,
        val quotesChecked: Int,
    ) {
        val ok: Boolean get() = violations.isEmpty()

        fun summary(): String =
            if (ok) {
                "$quotesChecked quotes, all verified"
            } else {
                "${violations.size} violation(s): " +
                    violations
                        .groupingBy { it.kind }
                        .eachCount()
                        .entries
                        .joinToString(", ") { "${it.value} ${it.key.name.lowercase()}" }
            }
    }

    fun validate(html: String): Report {
        val doc = Jsoup.parse(html)
        val violations = mutableListOf<Violation>()
        var checked = 0

        for (el in doc.select("q, blockquote")) {
            val text = el.text().trim()
            if (text.isBlank()) continue
            checked++
            if (!isQuoted(text)) {
                violations.add(Violation(Kind.QUOTE, "not found verbatim in any source event", text.take(160)))
            }
        }

        for (img in doc.select("img[src]")) {
            val src = img.attr("src")
            if (src !in allowedImages) {
                violations.add(Violation(Kind.IMAGE, "image source is not from the shortlist", src.take(120)))
            }
        }

        for (a in doc.select("a[href]")) {
            val href = a.attr("href")
            if (!href.startsWith("http", ignoreCase = true)) continue
            val id = permalinkTarget(href)
            if (id != null && id in corpusEventIds) continue
            val streamId = Streams.streamLinkTarget(href, liveStreams)
            if (streamId != null && liveStreams.any { it.id.equals(streamId, ignoreCase = true) }) continue
            violations.add(
                Violation(
                    Kind.LINK,
                    "only source citations and verified zap.stream watch links may be links",
                    href.take(120),
                ),
            )
        }

        return Report(violations, checked)
    }

    /**
     * Verbatim, allowing for elision and for typographic normalisation.
     *
     * Two forgivenesses, both of them normal editorial practice rather than
     * loopholes. Curly quotes, dashes and whitespace are normalised, because a
     * model that renders `'` as `’` has not changed what anybody said. And a
     * quote may elide its middle with an ellipsis, in which case every fragment
     * must appear IN ORDER in one single event — order and single-event are what
     * stop elision being used to stitch two people into one sentence.
     */
    internal fun isQuoted(raw: String): Boolean {
        val needle = normalize(raw)
        if (needle.isEmpty()) return true
        val fragments = needle.split("...").map { it.trim() }.filter { it.length > 2 }
        if (fragments.isEmpty()) return haystack.any { it.contains(needle) }
        return haystack.any { source ->
            var from = 0
            for (fragment in fragments) {
                val at = source.indexOf(fragment, from)
                if (at < 0) return@any false
                from = at + fragment.length
            }
            true
        }
    }

    /**
     * One normal form for both sides of the comparison.
     *
     * Lowercased on purpose: capitalising the first word of a quote to start a
     * sentence is standard and should not be a violation. Everything that
     * changes MEANING — words, order, negation — survives normalisation intact,
     * which is the line this is drawing.
     */
    internal fun normalize(s: String): String =
        Normalizer
            .normalize(s, Normalizer.Form.NFKC)
            .replace('’', '\'')
            .replace('‘', '\'')
            .replace('“', '"')
            .replace('”', '"')
            .replace('–', '-')
            .replace('—', '-')
            .replace(' ', ' ')
            .replace("…", "...")
            .replace(WHITESPACE, " ")
            .trim()
            .lowercase()
}
