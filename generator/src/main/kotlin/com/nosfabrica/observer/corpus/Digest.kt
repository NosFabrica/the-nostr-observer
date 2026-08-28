package com.nosfabrica.observer.corpus

import com.nosfabrica.observer.nostr.Corpus
import com.nosfabrica.observer.nostr.Desk
import com.nosfabrica.observer.nostr.Names
import com.nosfabrica.observer.nostr.Streams
import com.nosfabrica.observer.nostr.client
import com.nosfabrica.observer.nostr.hashtags
import com.nosfabrica.observer.nostr.value
import com.nosfabrica.observer.nostr.values
import com.vitorpamplona.quartz.nip01Core.core.Event
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The corpus, pruned and rendered as the text the generator reads.
 *
 * The window is fixed at 24 hours, so length is no longer a function of how long
 * the reader was away — but it is still a function of how rich their lens is. A
 * reader following five thousand people gets far more in a day than one
 * following fifty, so the cap here is on VOLUME, not time.
 *
 * Everything below is untrusted third-party text. It is rendered into a
 * delimited block and framed once, in the fixed system prompt, as content that
 * is never an instruction. Nothing here tries to detect an injection attempt:
 * that is a losing game, and the sanitizer is what makes losing it survivable.
 */
class Digest(
    private val budgetChars: Int = 250_000,
) {
    data class Rendered(
        val text: String,
        val kept: Int,
        val dropped: Int,
        val chars: Int,
    ) {
        /** Rough, and knowingly so — a token count would need an API round-trip to be exact. */
        val approxTokens: Int get() = chars / 4
    }

    private val stamp = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneOffset.UTC)

    fun render(
        corpus: Corpus,
        art: List<Art>,
    ): Rendered {
        val artByEvent = art.groupBy { it.eventId }
        var dropped = 0

        // Everything rendered first, so allocation is a decision about text
        // that exists rather than a race down the desk list.
        val pages =
            Desk.entries.mapNotNull { desk ->
                val events = corpus.ranked[desk].orEmpty()
                if (events.isEmpty()) return@mapNotNull null
                val pruned = prune(desk, events)
                dropped += events.size - pruned.size
                if (pruned.isEmpty()) return@mapNotNull null
                desk to
                    Page(
                        events.size,
                        pruned.map { event ->
                            StringBuilder().also { renderEvent(it, desk, event, corpus, artByEvent[event.id].orEmpty()) }.toString()
                        },
                    )
            }

        // A FAIR SHARE FIRST, then rank order takes the rest.
        //
        // This used to be one pass in desk order, spending the budget
        // first-come: a verbose notes desk exhausted it and whichever desk was
        // declared LAST in the enum silently lost its content. Observed between
        // two consecutive runs, app releases went from 3 of 3 to 1 of 3 for no
        // reason but upstream verbosity, and the enum's declaration order was
        // quietly acting as an editorial priority. Adding desks made it worse:
        // there are thirteen now and there were nine when it was found.
        val share = if (pages.isEmpty()) 0 else budgetChars / pages.size
        val taken = pages.associate { (desk, _) -> desk to mutableListOf<String>() }
        var used = 0

        for ((desk, page) in pages) {
            for (block in page.blocks) {
                if (taken.getValue(desk).sumOf { it.length } + block.length > share) break
                taken.getValue(desk).add(block)
                used += block.length
            }
        }
        for ((desk, page) in pages) {
            for (block in page.blocks.drop(taken.getValue(desk).size)) {
                if (used + block.length > budgetChars) break
                taken.getValue(desk).add(block)
                used += block.length
            }
        }

        val sb = StringBuilder()
        var kept = 0
        for ((desk, page) in pages) {
            val blocks = taken.getValue(desk)
            if (blocks.isEmpty()) continue
            kept += blocks.size
            dropped += page.blocks.size - blocks.size
            // Rendered first, counted second. The header used to be written
            // before the events and claimed the pruned count, which stopped
            // being true the moment the budget cut the desk short -- and the
            // header is one of the few things in the digest the writer is
            // entitled to treat as fact.
            sb
                .append("\n\n===== ")
                .append(desk.label.uppercase())
                .append(" (")
                .append(blocks.size)
                .append(" of ")
                .append(page.returned)
                .append(") =====\n")
            blocks.forEach(sb::append)
        }
        return Rendered(sb.toString().trim(), kept, dropped, sb.length)
    }

    /** One desk's events, rendered but not yet allocated a share of the budget. */
    private data class Page(
        val returned: Int,
        val blocks: List<String>,
    )

    private fun renderEvent(
        sb: StringBuilder,
        desk: Desk,
        event: Event,
        corpus: Corpus,
        art: List<Art>,
    ) {
        val profile = corpus.profiles[event.pubKey]
        // A highlight is somebody ELSE's sentence. Saying so in the byline is
        // the whole fix: see [highlight].
        sb.append("\n--- ").append(if (desk == Desk.HIGHLIGHTS) "HIGHLIGHTED BY " else "")
        sb.append(profile?.display() ?: Names.short(event.pubKey))
        profile?.nip05?.let { sb.append(" <").append(it).append(">") }
        sb.append(" · ").append(stamp.format(Instant.ofEpochSecond(event.createdAt))).append("Z")
        event.client()?.let { sb.append(" · via ").append(it) }
        // `note1…`, not the raw id. If the writer ever prints a citation, this
        // is the form a reader can paste; a 64-character hex string in a column
        // of prose reads as a fault in the page.
        sb.append(" · event ").append(Names.note(event.id) ?: event.id).append("\n")

        // `name` and `description` are what a git repository calls these.
        (event.value("title") ?: event.value("name"))?.let { sb.append("TITLE: ").append(it.take(200)).append("\n") }
        (event.value("summary") ?: event.value("description"))?.let { sb.append("SUMMARY: ").append(it.take(600)).append("\n") }
        if (desk == Desk.HIGHLIGHTS) highlight(sb, event, corpus)
        if (desk == Desk.LIVE) live(sb, event)
        if (desk == Desk.POLLS) poll(sb, event)
        if (desk == Desk.CALENDAR) calendar(sb, event)
        if (desk == Desk.CLASSIFIEDS) classified(sb, event)
        event.value("location")?.let { sb.append("LOCATION: ").append(it.take(120)).append("\n") }
        // Length is most of what a reader needs to decide about a video, and
        // it is the one fact the body text never carries.
        event.value("duration")?.toIntOrNull()?.takeIf { it > 0 }?.let {
            sb.append("DURATION: ").append(if (it < 60) "${it}s" else "${it / 60}m ${it % 60}s").append("\n")
        }
        if (art.isNotEmpty()) {
            sb.append("ART: ").append(art.joinToString(", ") { it.id }).append("\n")
        }
        event.hashtags().takeIf { it.isNotEmpty() }?.let {
            sb.append("TAGS: ").append(it.take(12).joinToString(", ")).append("\n")
        }

        val body = body(desk, event)
        if (body.isNotBlank()) sb.append(body).append("\n")
    }

    /**
     * Who actually wrote the sentence, and where it came from.
     *
     * THE BUG THIS FIXES: a `kind 9802` highlight's content is a verbatim
     * excerpt of somebody else's writing, and the digest rendered it exactly
     * like a post — byline of the highlighter, no source, no original author.
     * A model reading that writes `Gigi wrote: "human code review has very
     * nearly run its course"` when Gigi merely marked the passage. It is a real
     * quote attributed to the wrong person, published under the reader's key.
     *
     * The validator cannot catch it. It checks that quoted text appears
     * VERBATIM in a source event, and the text does — in the highlight. Text
     * fidelity and correct attribution are different properties, and only one
     * of them was being checked.
     *
     * Measured 2026-08-18 over 31 highlights: 11 carry a `p` naming the author,
     * 20 an `r` for the source URL, 7 an `a` for a long-form address, and 18 a
     * `context` giving the surrounding passage. All of it was being discarded.
     */
    private fun highlight(
        sb: StringBuilder,
        event: Event,
        corpus: Corpus,
    ) {
        sb.append("EXCERPT — these are NOT the highlighter's words. Attribute the quote to the author below.\n")
        // Named when the highlight names them, and explicitly unknown when it
        // does not -- measured 2026-08-18, only 11 of 31 carried a `p`. Silence
        // here is what invites the writer to fall back on the byline above,
        // which is the highlighter and the wrong person.
        val author = event.value("p")?.takeIf { it.length == 64 }
        if (author != null) {
            sb.append("AUTHOR: ").append(corpus.byline(author)).append("\n")
        } else {
            sb.append("AUTHOR: not named — cite the source below, never the highlighter\n")
        }
        (event.value("r") ?: event.value("a"))?.let { sb.append("SOURCE: ").append(it.take(200)).append("\n") }
        event.value("context")?.takeIf { it.isNotBlank() }?.let {
            sb.append("CONTEXT (surrounding passage, do not quote as the excerpt): ").append(it.take(500)).append("\n")
        }
    }

    /** A stream is only news while it is running, so say when it started and who is there. */
    private fun live(
        sb: StringBuilder,
        event: Event,
    ) {
        event.value("starts")?.toLongOrNull()?.let {
            sb.append("ON AIR SINCE: ").append(stamp.format(Instant.ofEpochSecond(it))).append("Z\n")
        }
        event.value("current_participants")?.let { sb.append("WATCHING: ").append(it).append("\n") }
        // Streams.live / Sanitizer / Validator only allowlist events with a `d`
        // tag; printing a watch URL without one invites a link the sanitizer
        // unwraps before publish.
        if (!event.value("d").isNullOrBlank()) {
            sb.append("watch: ").append(Streams.writerUrl(event.id)).append("\n")
        }
    }

    /**
     * A poll is its question and its options, and the options are tags.
     *
     * Two shapes in the wild, measured 2026-08-18: `["option", "0", "A) €25,000"]`
     * and `["option", "Bu2a9f", "Yes"]`. The first field is an id in both, so the
     * label is always the second.
     */
    private fun poll(
        sb: StringBuilder,
        event: Event,
    ) {
        val options = event.values("option").mapNotNull { it.getOrNull(1) }.filter { it.isNotBlank() }
        if (options.isNotEmpty()) {
            sb.append("OPTIONS: ").append(options.take(8).joinToString(" / ") { it.take(80) }).append("\n")
        }
        event.value("endsAt")?.toLongOrNull()?.let {
            sb.append("CLOSES: ").append(stamp.format(Instant.ofEpochSecond(it))).append("Z\n")
        }
    }

    /**
     * When it actually happens, which is the only reason a listing exists.
     *
     * THE BUG THIS FIXES: a calendar entry was rendered with its title, its
     * location and its body and NO DATE. The one timestamp on the block was
     * `created_at` — the moment somebody posted the listing — so the writer
     * either dropped the entry or would have had to invent a day for it. Two
     * real editions printed zero of twenty-eight calendar events between them,
     * which was the right call on the text they were handed: "Lexington
     * bitcoin meetup, at the Cellar Bar" is not news until it has a day.
     *
     * Measured 2026-08-18 over 100 real entries: 100 carried `start`, 100
     * `start_tzid`, 97 `end`. All of it was being discarded.
     *
     * The time is printed in the ORGANISER's zone, not ours. A meetup at seven
     * in the evening in Kentucky is not a meetup at 23:00, and the tzid is
     * there precisely so nobody has to guess.
     */
    private fun calendar(
        sb: StringBuilder,
        event: Event,
    ) {
        val zone = runCatching { ZoneId.of(event.value("start_tzid") ?: "UTC") }.getOrElse { ZoneOffset.UTC }
        val starts = event.value("start")?.let { moment(it, zone) }
        if (starts == null) {
            // Silence here is what lets the posting time stand in for the
            // event time, so the absence is stated rather than left blank.
            sb.append("WHEN: not stated — this listing has no date, do not give it one\n")
            return
        }
        sb.append("WHEN: ").append(starts)
        event.value("end")?.let { moment(it, zone) }?.let { sb.append(" until ").append(it) }
        sb.append("\n")
    }

    /**
     * A NIP-52 timestamp, in both shapes it comes in.
     *
     * Kind 31923 dates a moment in unix seconds; kind 31922 is the all-day
     * half and writes a bare `YYYY-MM-DD` with no time and no zone. They share
     * a desk, so reading only the first would drop every all-day entry while
     * looking like it worked.
     */
    private fun moment(
        raw: String,
        zone: ZoneId,
    ): String? =
        raw.toLongOrNull()?.let { WHEN.format(Instant.ofEpochSecond(it).atZone(zone)) + " " + zone.id }
            ?: raw.takeIf { DATE.matches(it) }?.plus(" (all day)")

    /**
     * What it costs, and whether it is still for sale.
     *
     * A classified is an OFFER and the offer was being dropped: price, status
     * and condition are all tags, so the digest rendered a title, a body and no
     * number. The one listing that reached a real edition was described as
     * "super rare vintage" with no price attached, because there was no price
     * to attach.
     *
     * NIP-99 writes `["price", "210000", "SATS"]`, with an optional fourth
     * field naming a frequency for things rented rather than sold. Measured
     * 2026-08-18 over 15 real listings: 18 price tags, 6 statuses, 3
     * conditions.
     */
    private fun classified(
        sb: StringBuilder,
        event: Event,
    ) {
        event.values("price").firstOrNull()?.let { price ->
            val amount = price.getOrNull(0)?.trim()?.takeIf { it.isNotBlank() } ?: return@let
            sb.append("PRICE: ").append(amount.take(20))
            price
                .getOrNull(1)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { sb.append(" ").append(it.take(12)) }
            price
                .getOrNull(2)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { sb.append(" per ").append(it.take(12)) }
            sb.append("\n")
        }
        // `sold` is the one that changes what may be written: a page that
        // advertises a sold item sends readers after something that is gone.
        event.value("status")?.takeIf { it.isNotBlank() }?.let { sb.append("STATUS: ").append(it.take(20)).append("\n") }
        event.value("condition")?.takeIf { it.isNotBlank() }?.let { sb.append("CONDITION: ").append(it.take(20)).append("\n") }
    }

    /**
     * How much of an event's text the generator needs to judge it.
     *
     * Long-form is the case that matters: a 12,000-word essay contributes exactly
     * as much to a front page as its title, summary and opening — and there were
     * sixty of them in the prototype window, mostly from two bots republishing
     * their whole back catalogue.
     */
    private fun body(
        desk: Desk,
        event: Event,
    ): String {
        val limit =
            when (desk) {
                Desk.ARTICLES -> 900
                Desk.CALENDAR, Desk.CLASSIFIEDS -> 400
                else -> 1400
            }
        val text = event.content.replace(BLANK_LINES, "\n\n").trim()
        return if (text.length <= limit) text else text.take(limit) + " …[trimmed]"
    }

    /**
     * Two prunes, both learned from the prototype window rather than guessed.
     *
     * A per-author cap, because one bot filing its archive can own a whole desk —
     * a single account supplied sixty of a hundred long-form events, and without
     * a cap it would have supplied the section. And a duplicate collapse, because
     * the same post arrives repeatedly when a client retries against several
     * hosts; four identical tiger photographs was one person, not four.
     */
    private fun prune(
        desk: Desk,
        events: List<Event>,
    ): List<Event> {
        val perAuthor =
            when (desk) {
                Desk.NOTES -> 20

                Desk.ARTICLES -> 4

                Desk.CALENDAR -> 6

                // Video is posted in runs -- one account uploading a day's
                // clips is the normal shape, not the exception.
                Desk.SHORTS -> 5

                else -> 8
            }
        val counts = mutableMapOf<String, Int>()
        val seen = mutableSetOf<String>()
        val out = mutableListOf<Event>()
        for (event in events) {
            val n = counts.getOrDefault(event.pubKey, 0)
            if (n >= perAuthor) continue
            val key = fingerprint(event)
            if (key.isNotEmpty() && !seen.add(key)) continue
            counts[event.pubKey] = n + 1
            out.add(event)
        }
        return out
    }

    // Compiled once. These run per event over a few hundred events per edition,
    // and Regex(...) inside the loop recompiles the pattern every time.
    private companion object {
        val BLANK_LINES = Regex("\n{3,}")

        /** Day of week included: for a meetup that is most of what a reader wants. */
        val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm")

        /** The all-day shape, and nothing looser -- a partial date is not a date. */
        val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
        val URL = Regex("""https?://\S+""")
        val WHITESPACE = Regex("""\s+""")
    }

    /** Same author, same words — whitespace, case and links normalised away. */
    private fun fingerprint(event: Event): String {
        val text =
            event.content
                .replace(URL, "")
                .replace(WHITESPACE, " ")
                .trim()
                .lowercase()
        return if (text.length < 12) "" else event.pubKey + "|" + text.take(200)
    }
}
