package com.nosfabrica.observer.write

import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Document

/**
 * What today's paper called itself, ready for tomorrow.
 *
 * The prompt tells the writer to keep the masthead and to announce a change in
 * a `<!-- masthead: New Name | why -->` comment on the first line. This reads
 * that back so "softly in place" is a record rather than an intention: without
 * it, the continuity row is written once and never updated, and every edition
 * is the first edition.
 *
 * ## Why this is a trust boundary
 *
 * The page is written by a model that has just read a corpus strangers can
 * write to, and whatever is stored here goes into TOMORROW's prompt. That makes
 * this the one path by which today's corpus can speak to tomorrow's
 * instructions — a slow, one-day-latency version of prompt injection.
 *
 * The defence is that a masthead is a NAME. It is length-capped, stripped to a
 * single line, and reduced to plain text with no markup, so the channel is a
 * few dozen characters of prose rather than a place to put a paragraph. It is
 * also read from the model's raw output rather than the sanitized page on
 * purpose: the sanitizer drops comments, so by then the announcement is gone.
 */
object Masthead {
    /** A name is a few words. Anything longer is not a name and is not stored. */
    const val MAX_MASTHEAD = 60
    const val MAX_HEADLINE = 140

    private val ANNOUNCEMENT = Regex("""^\s*masthead\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)

    fun next(
        rawHtml: String,
        previous: Continuity,
    ): Continuity {
        val document = Jsoup.parse(rawHtml)
        return Continuity(
            masthead = announced(document)?.let(::clean)?.takeIf { it.isNotBlank() } ?: previous.masthead,
            motto = previous.motto,
            sections = sections(document).ifEmpty { previous.sections },
            recentHeadlines = headlines(document),
        )
    }

    /** The `<!-- masthead: ... -->` announcement, if the writer made one. */
    private fun announced(document: Document): String? =
        document
            .body()
            .childNodes()
            .filterIsInstance<Comment>()
            .firstNotNullOfOrNull { ANNOUNCEMENT.find(it.data)?.groupValues?.get(1) }
            // Everything after the pipe is the writer's reason, addressed to a
            // person reading the source. The name is the part before it.
            ?.substringBefore('|')

    private fun sections(document: Document): List<String> =
        document
            .select("section > h2, section > header > h2")
            .map { clean(it.text()) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(8)

    /**
     * Yesterday's front page, as a handful of lines.
     *
     * Enough for the writer to avoid repeating itself and to say "as we
     * reported"; not enough to become a second corpus.
     */
    private fun headlines(document: Document): List<String> =
        document
            .select("article h2, article h3, h1 + p.lede, .lede")
            .map { clean(it.text(), MAX_HEADLINE) }
            .filter { it.length >= 8 }
            .distinct()
            .take(6)

    /**
     * To plain, single-line, bounded text.
     *
     * Newlines are removed rather than collapsed because the thing this stops
     * is a stored string that ends a sentence and starts a new instruction on
     * the next line when it lands in tomorrow's prompt.
     */
    private fun clean(
        raw: String,
        limit: Int = MAX_MASTHEAD,
    ): String =
        raw
            .replace(Regex("""[\r\n]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(limit)
}
