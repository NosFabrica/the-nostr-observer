package com.nosfabrica.observer.nostr

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress

/**
 * A desk whose events get a real destination: a host's own page for an
 * addressable event we actually read.
 *
 * Three desks qualify — Live now to zap.stream, Classifieds to Shopstr, Diary &
 * Calendar to njump — and they differ only in host and path. The writer cites
 * the digest's line in writer form (`<host><writerPath><64-hex>`);
 * [com.nosfabrica.observer.safe.Sanitizer] encodes that to the host's canonical
 * naddr afterwards, matching the skill's resolve step. Either way the address
 * must match an event on this desk that we read — not merely appear in
 * somebody's post — so a fabricated watch or listing link is structurally
 * impossible rather than merely detectable.
 *
 * Only events carrying a `d` tag qualify: without one there is no address to
 * build.
 */
class DeskLink private constructor(
    val desk: Desk,
    private val host: String,
    writerPath: String,
    private val naddrPath: String,
) {
    private val writerPrefix = "https://$host$writerPath"
    private val writer =
        Regex("""^${Regex.escape(writerPrefix)}([0-9a-f]{64})(?:[/?#].*)?$""", RegexOption.IGNORE_CASE)
    private val naddr =
        Regex("""^${Regex.escape("https://$host$naddrPath")}(naddr1[0-9a-z]+)(?:[/?#].*)?$""", RegexOption.IGNORE_CASE)

    /** Events on this desk with a `d` tag — the only ones a link may name. */
    fun listed(corpus: Corpus): List<Event> =
        corpus.ranked[desk].orEmpty().filter {
            it.kind in desk.kinds && !it.value("d").isNullOrBlank()
        }

    /** Writer form: event id hex. The sanitizer encodes the naddr afterwards. */
    fun writerUrl(eventId: String): String {
        val id = eventId.lowercase()
        require(EVENT_ID.matches(id)) { "Not an event id: ${eventId.take(16)}" }
        return "$writerPrefix$id"
    }

    /** The host's canonical page for [event], addressed by naddr. */
    fun canonicalUrl(event: Event): String {
        val d = event.value("d") ?: error("Not an address on ${desk.label}")
        require(event.kind in desk.kinds) { "Not an address on ${desk.label}" }
        return "https://$host$naddrPath${NAddress.create(event.kind, event.pubKey, d, emptyList())}"
    }

    /**
     * Event id if [href] is a verified link to one of [listed]; otherwise null.
     *
     * Two shapes: canonical naddr, or the writer form the digest prints. On
     * njump the writer form shares its host with ordinary permalinks;
     * membership in [listed] is what separates them.
     */
    fun target(
        href: String,
        listed: Collection<Event>,
    ): String? {
        writer.find(href)?.groupValues?.get(1)?.lowercase()?.let { id ->
            if (listed.any { it.id.equals(id, ignoreCase = true) }) return id
        }
        val parsed =
            naddr
                .find(href)
                ?.groupValues
                ?.get(1)
                ?.let(NAddress::parse) ?: return null
        if (parsed.kind !in desk.kinds) return null
        return listed
            .find {
                it.kind == parsed.kind &&
                    it.pubKey.equals(parsed.author, ignoreCase = true) &&
                    it.value("d") == parsed.dTag
            }?.id
            ?.lowercase()
    }

    companion object {
        private val EVENT_ID = Regex("^[0-9a-f]{64}$")

        /** zap.stream watch pages for kind 30311 streams. */
        val LIVE = DeskLink(Desk.LIVE, "zap.stream", writerPath = "/stream/", naddrPath = "/")

        /** Shopstr listing pages for NIP-99 classifieds. */
        val CLASSIFIEDS = DeskLink(Desk.CLASSIFIEDS, "shopstr.store", writerPath = "/listing/", naddrPath = "/listing/")

        /**
         * njump pages for NIP-52 listings, 31922 all-day and 31923 timed.
         *
         * An nevent would freeze one revision of a replaceable event; the naddr
         * is the listing. jumble.social has no calendar view.
         */
        val CALENDAR = DeskLink(Desk.CALENDAR, "njump.me", writerPath = "/", naddrPath = "/")
    }
}
