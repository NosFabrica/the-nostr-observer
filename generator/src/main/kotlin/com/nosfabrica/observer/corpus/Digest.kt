package com.nosfabrica.observer.corpus

import com.nosfabrica.observer.nostr.Corpus
import com.nosfabrica.observer.nostr.Desk
import com.nosfabrica.observer.nostr.client
import com.nosfabrica.observer.nostr.hashtags
import com.nosfabrica.observer.nostr.value
import com.vitorpamplona.quartz.nip01Core.core.Event
import java.time.Instant
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
        val sb = StringBuilder()
        var kept = 0
        var dropped = 0
        val artByEvent = art.groupBy { it.eventId }

        for (desk in Desk.entries) {
            val events = corpus.ranked[desk].orEmpty()
            if (events.isEmpty()) continue
            val pruned = prune(desk, events)
            dropped += events.size - pruned.size
            if (pruned.isEmpty()) continue

            // Rendered first, counted second. The header used to be written
            // before the events and claimed `pruned.size` of them, which stopped
            // being true the moment the budget cut the desk short -- and the
            // header is one of the few things in the digest the writer is
            // entitled to treat as fact.
            val desked = StringBuilder()
            var here = 0
            for (event in pruned) {
                if (sb.length + desked.length > budgetChars) {
                    dropped++
                    continue
                }
                here++
                renderEvent(desked, desk, event, corpus, artByEvent[event.id].orEmpty())
            }
            if (here == 0) continue
            kept += here

            sb
                .append("\n\n===== ")
                .append(desk.label.uppercase())
                .append(" (")
                .append(here)
                .append(" of ")
                .append(events.size)
                .append(") =====\n")
                .append(desked)
        }
        return Rendered(sb.toString().trim(), kept, dropped, sb.length)
    }

    private fun renderEvent(
        sb: StringBuilder,
        desk: Desk,
        event: Event,
        corpus: Corpus,
        art: List<Art>,
    ) {
        val profile = corpus.profiles[event.pubKey]
        sb.append("\n--- ").append(profile?.display() ?: event.pubKey.take(8))
        profile?.nip05?.let { sb.append(" <").append(it).append(">") }
        sb.append(" · ").append(stamp.format(Instant.ofEpochSecond(event.createdAt))).append("Z")
        event.client()?.let { sb.append(" · via ").append(it) }
        sb.append(" · event ").append(event.id).append("\n")

        event.value("title")?.let { sb.append("TITLE: ").append(it.take(200)).append("\n") }
        event.value("summary")?.let { sb.append("SUMMARY: ").append(it.take(600)).append("\n") }
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
