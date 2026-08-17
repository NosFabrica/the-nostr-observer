package com.nosfabrica.observer.corpus

import com.nosfabrica.observer.nostr.Corpus
import com.nosfabrica.observer.nostr.Desk
import com.nosfabrica.observer.nostr.NostrEvent
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

            sb
                .append("\n\n===== ")
                .append(desk.label.uppercase())
                .append(" (")
                .append(pruned.size)
                .append(" of ")
                .append(events.size)
                .append(") =====\n")

            for (event in pruned) {
                if (sb.length > budgetChars) {
                    dropped++
                    continue
                }
                kept++
                renderEvent(sb, desk, event, corpus, artByEvent[event.id].orEmpty())
            }
        }
        return Rendered(sb.toString().trim(), kept, dropped, sb.length)
    }

    private fun renderEvent(
        sb: StringBuilder,
        desk: Desk,
        event: NostrEvent,
        corpus: Corpus,
        art: List<Art>,
    ) {
        val profile = corpus.profiles[event.pubkey]
        sb.append("\n--- ").append(profile?.byline() ?: event.pubkey.take(8))
        profile?.nip05?.let { sb.append(" <").append(it).append(">") }
        sb.append(" · ").append(stamp.format(Instant.ofEpochSecond(event.createdAt))).append("Z")
        event.client()?.let { sb.append(" · via ").append(it) }
        sb.append(" · event ").append(event.id).append("\n")

        event.tag("title")?.let { sb.append("TITLE: ").append(it.take(200)).append("\n") }
        event.tag("summary")?.let { sb.append("SUMMARY: ").append(it.take(600)).append("\n") }
        event.tag("location")?.let { sb.append("LOCATION: ").append(it.take(120)).append("\n") }
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
        event: NostrEvent,
    ): String {
        val limit =
            when (desk) {
                Desk.ARTICLES -> 900
                Desk.CALENDAR, Desk.CLASSIFIEDS -> 400
                else -> 1400
            }
        val text = event.content.replace(Regex("\n{3,}"), "\n\n").trim()
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
        events: List<NostrEvent>,
    ): List<NostrEvent> {
        val perAuthor =
            when (desk) {
                Desk.NOTES -> 20
                Desk.ARTICLES -> 4
                Desk.CALENDAR -> 6
                else -> 8
            }
        val counts = mutableMapOf<String, Int>()
        val seen = mutableSetOf<String>()
        val out = mutableListOf<NostrEvent>()
        for (event in events) {
            val n = counts.getOrDefault(event.pubkey, 0)
            if (n >= perAuthor) continue
            val key = fingerprint(event)
            if (key.isNotEmpty() && !seen.add(key)) continue
            counts[event.pubkey] = n + 1
            out.add(event)
        }
        return out
    }

    /** Same author, same words — whitespace, case and links normalised away. */
    private fun fingerprint(event: NostrEvent): String {
        val text =
            event.content
                .replace(Regex("""https?://\S+"""), "")
                .replace(Regex("""\s+"""), " ")
                .trim()
                .lowercase()
        return if (text.length < 12) "" else event.pubkey + "|" + text.take(200)
    }
}
