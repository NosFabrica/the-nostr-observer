package com.nosfabrica.observer.nostr

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress

/** Kind 30402 — a NIP-99 classified listing. */
const val CLASSIFIED_KIND = 30402

/**
 * Shopstr listing URLs derived from classifieds we actually read.
 *
 * Same shape as [Streams]: the writer cites the digest's `listing:` line in
 * writer form (`/listing/<64-hex>`); [com.nosfabrica.observer.safe.Sanitizer]
 * encodes that to Shopstr's canonical naddr form afterwards.
 */
object Classifieds {
    private val WRITER = Regex("""^https://shopstr\.store/listing/([0-9a-f]{64})(?:[/?#].*)?$""", RegexOption.IGNORE_CASE)
    private val NADDR = Regex("""^https://shopstr\.store/listing/(naddr1[0-9a-z]+)(?:[/?#].*)?$""", RegexOption.IGNORE_CASE)
    private val EVENT_ID = Regex("^[0-9a-f]{64}$")

    /** Classifieds with a `d` tag — the only ones a listing link may name. */
    fun listed(corpus: Corpus): List<Event> = corpus.ranked[Desk.CLASSIFIEDS].orEmpty().filter { !it.value("d").isNullOrBlank() }

    /** Writer form: event id hex. The sanitizer encodes the naddr afterwards. */
    fun writerUrl(eventId: String): String {
        val id = eventId.lowercase()
        require(EVENT_ID.matches(id)) { "Not an event id: ${eventId.take(16)}" }
        return "https://shopstr.store/listing/$id"
    }

    /** Canonical Shopstr listing page for a classified. */
    fun canonicalUrl(event: Event): String {
        val d = event.value("d") ?: error("Not a classified address")
        require(event.kind == CLASSIFIED_KIND) { "Not a classified address" }
        val naddr = NAddress.create(CLASSIFIED_KIND, event.pubKey, d, emptyList())
        return "https://shopstr.store/listing/$naddr"
    }

    /**
     * Event id if [href] is a verified Shopstr listing link for one of [listings];
     * otherwise null.
     *
     * Two shapes: canonical naddr, or the writer form the digest prints. Either
     * way the address must match a kind 30402 we read — not merely appear in
     * somebody's post.
     */
    fun listingLinkTarget(
        href: String,
        listings: Collection<Event>,
    ): String? {
        WRITER.find(href)?.groupValues?.get(1)?.lowercase()?.let { id ->
            if (listings.any { it.id.equals(id, ignoreCase = true) }) return id
        }
        val naddr = NADDR.find(href)?.groupValues?.get(1) ?: return null
        return listingFromNaddr(naddr, listings)?.id?.lowercase()
    }

    private fun listingFromNaddr(
        naddr: String,
        listings: Collection<Event>,
    ): Event? {
        val parsed = NAddress.parse(naddr) ?: return null
        if (parsed.kind != CLASSIFIED_KIND) return null
        return listings.find {
            it.pubKey.equals(parsed.author, ignoreCase = true) && it.value("d") == parsed.dTag
        }
    }
}
