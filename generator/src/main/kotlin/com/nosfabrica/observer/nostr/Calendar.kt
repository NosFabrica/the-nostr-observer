package com.nosfabrica.observer.nostr

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress

/** NIP-52 calendar — 31922 all-day, 31923 timed. Both are parameterized replaceable. */
val CALENDAR_KINDS = setOf(31922, 31923)

/**
 * njump calendar URLs derived from listings we actually read.
 *
 * Same shape as [Streams] and [Classifieds]: the writer cites the digest's
 * `calendar:` line in writer form (`njump.me/<64-hex>`);
 * [com.nosfabrica.observer.safe.Sanitizer] encodes that to njump's canonical
 * naddr form afterwards. An nevent would freeze one revision of a replaceable
 * event; the naddr is the listing. jumble.social has no calendar view.
 */
object Calendar {
    private val WRITER = Regex("""^https://njump\.me/([0-9a-f]{64})(?:[/?#].*)?$""", RegexOption.IGNORE_CASE)
    private val NADDR = Regex("""^https://njump\.me/(naddr1[0-9a-z]+)(?:[/?#].*)?$""", RegexOption.IGNORE_CASE)
    private val EVENT_ID = Regex("^[0-9a-f]{64}$")

    /** Calendar listings with a `d` tag — the only ones a calendar link may name. */
    fun listed(corpus: Corpus): List<Event> =
        corpus.ranked[Desk.CALENDAR].orEmpty().filter {
            it.kind in CALENDAR_KINDS && !it.value("d").isNullOrBlank()
        }

    /** Writer form: event id hex. The sanitizer encodes the naddr afterwards. */
    fun writerUrl(eventId: String): String {
        val id = eventId.lowercase()
        require(EVENT_ID.matches(id)) { "Not an event id: ${eventId.take(16)}" }
        return "https://njump.me/$id"
    }

    /** Canonical njump page for a calendar listing. */
    fun canonicalUrl(event: Event): String {
        val d = event.value("d") ?: error("Not a calendar address")
        require(event.kind in CALENDAR_KINDS) { "Not a calendar address" }
        val naddr = NAddress.create(event.kind, event.pubKey, d, emptyList())
        return "https://njump.me/$naddr"
    }

    /**
     * Event id if [href] is a verified njump calendar link for one of [listings];
     * otherwise null.
     *
     * Two shapes: canonical naddr, or the writer form the digest prints. Either
     * way the address must match a 31922/31923 we read — not merely appear in
     * somebody's post. Writer form shares its host with ordinary njump
     * permalinks; membership in [listings] is what separates them.
     */
    fun calendarLinkTarget(
        href: String,
        listings: Collection<Event>,
    ): String? {
        WRITER.find(href)?.groupValues?.get(1)?.lowercase()?.let { id ->
            if (listings.any { it.id.equals(id, ignoreCase = true) }) return id
        }
        val naddr = NADDR.find(href)?.groupValues?.get(1) ?: return null
        return calendarFromNaddr(naddr, listings)?.id?.lowercase()
    }

    private fun calendarFromNaddr(
        naddr: String,
        listings: Collection<Event>,
    ): Event? {
        val parsed = NAddress.parse(naddr) ?: return null
        if (parsed.kind !in CALENDAR_KINDS) return null
        return listings.find {
            it.kind == parsed.kind &&
                it.pubKey.equals(parsed.author, ignoreCase = true) &&
                it.value("d") == parsed.dTag
        }
    }
}
