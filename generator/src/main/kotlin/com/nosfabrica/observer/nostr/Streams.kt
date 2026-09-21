package com.nosfabrica.observer.nostr

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress

/** Kind 30311 — a replaceable live-stream listing. */
const val LIVE_KIND = 30311

/**
 * zap.stream watch URLs derived from live events we actually read.
 *
 * The writer cites the digest's `watch:` line in writer form
 * (`/stream/<64-hex>`); [com.nosfabrica.observer.safe.Sanitizer] encodes that to
 * zap.stream's canonical naddr form afterwards, matching the skill's resolve step.
 */
object Streams {
    private val WRITER = Regex("""^https://zap\.stream/stream/([0-9a-f]{64})(?:[/?#].*)?$""", RegexOption.IGNORE_CASE)
    private val NADDR = Regex("""^https://zap\.stream/(naddr1[0-9a-z]+)(?:[/?#].*)?$""", RegexOption.IGNORE_CASE)
    private val EVENT_ID = Regex("^[0-9a-f]{64}$")

    /** Live streams with a `d` tag — the only ones a watch link may name. */
    fun live(corpus: Corpus): List<Event> = corpus.ranked[Desk.LIVE].orEmpty().filter { !it.value("d").isNullOrBlank() }

    /** Writer form: event id hex. The sanitizer encodes the naddr afterwards. */
    fun writerUrl(eventId: String): String {
        val id = eventId.lowercase()
        require(EVENT_ID.matches(id)) { "Not an event id: ${eventId.take(16)}" }
        return "https://zap.stream/stream/$id"
    }

    /** Canonical zap.stream watch page for a live event. */
    fun canonicalUrl(event: Event): String {
        val d = event.value("d") ?: error("Not a live stream address")
        require(event.kind == LIVE_KIND) { "Not a live stream address" }
        val naddr = NAddress.create(LIVE_KIND, event.pubKey, d, emptyList())
        return "https://zap.stream/$naddr"
    }

    /**
     * Event id if [href] is a verified zap.stream watch link for one of [streams];
     * otherwise null.
     *
     * Two shapes: canonical naddr, or the writer form the digest prints. Either
     * way the address must match a kind 30311 we read — not merely appear in
     * somebody's post.
     */
    fun streamLinkTarget(
        href: String,
        streams: Collection<Event>,
    ): String? {
        WRITER.find(href)?.groupValues?.get(1)?.lowercase()?.let { id ->
            if (streams.any { it.id.equals(id, ignoreCase = true) }) return id
        }
        val naddr = NADDR.find(href)?.groupValues?.get(1) ?: return null
        return streamFromNaddr(naddr, streams)?.id?.lowercase()
    }

    private fun streamFromNaddr(
        naddr: String,
        streams: Collection<Event>,
    ): Event? {
        val parsed = NAddress.parse(naddr) ?: return null
        if (parsed.kind != LIVE_KIND) return null
        return streams.find {
            it.pubKey.equals(parsed.author, ignoreCase = true) && it.value("d") == parsed.dTag
        }
    }
}
