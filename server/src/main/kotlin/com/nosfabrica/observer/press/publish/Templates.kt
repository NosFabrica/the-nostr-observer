package com.nosfabrica.observer.press.publish

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip09Deletions.DeletionEvent
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress
import com.vitorpamplona.quartz.nip5aStaticWebsites.NamedSiteEvent
import com.vitorpamplona.quartz.nip5aStaticWebsites.siteAggregateHash
import com.vitorpamplona.quartz.nip5aStaticWebsites.siteDescription
import com.vitorpamplona.quartz.nip5aStaticWebsites.sitePaths
import com.vitorpamplona.quartz.nip5aStaticWebsites.siteServers
import com.vitorpamplona.quartz.nip5aStaticWebsites.siteTitle
import com.vitorpamplona.quartz.nip5aStaticWebsites.tags.PathTag
import com.vitorpamplona.quartz.nipB7Blossom.BlossomAuthorizationEvent

/**
 * The two events a reader signs to publish, built here and signed there.
 *
 * The server never holds a key, so it cannot sign either of these. It builds
 * exactly what should be signed, hands it over, and checks what comes back
 * (see [Countersign]). Building the template server-side is not a formality:
 * it is what makes the check possible at all. A flow where the client invents
 * its own event and the server just relays it has nothing to compare against.
 *
 * Every publish is two signatures, the upload authorization and the manifest,
 * and that is a real cost to the reader rather than an implementation detail.
 * With an extension it is two prompts; with a remote signer it is two round
 * trips through a path already known to be awkward on mobile.
 */
object Templates {
    /**
     * ONE SITE PER EDITION, and the day is its name.
     *
     * The alternative was one site carrying every day as a path, and it looked
     * tidier right up to the point where you notice that a `kind 35128`
     * REPLACES: publishing Tuesday means rewriting the event that holds Monday,
     * so every publish had to first read the current manifest and merge, and a
     * read that came back empty for the wrong reason deleted the reader's whole
     * back catalogue. That hazard needed a canary read, a fail-closed refusal
     * and a precondition on every publish, and none of it is needed here —
     * a new `d` each day replaces nothing.
     *
     * It also makes removing one edition a plain NIP-09 deletion against that
     * day's address, rather than a republish of everything except it. And it
     * stops the manifest growing by a tag a day toward the relay's message
     * limit.
     *
     * The cost is one entry per day in whatever lists a reader's sites, and
     * more events on their relays than a single replaceable one. Both are
     * honest: they did publish a paper a day.
     */
    fun site(day: String): String = "$PREFIX$day"

    /** The day back out of a `d` tag, or null if this is not one of ours. */
    fun dayOf(identifier: String): String? = identifier.removePrefix(PREFIX).takeIf { it != identifier && DAY.matches(it) }

    /**
     * One edition as an `naddr1…`.
     *
     * The same thing as `35128:<pubkey>:observer-2026-08-18`, which is right for
     * an `a` tag and wrong for a person — sixty-four characters of hex with
     * punctuation. This is what gets shown and what a reader can paste.
     */
    fun address(
        pubkey: String,
        day: String,
    ): String? = runCatching { NAddress.create(NamedSiteEvent.KIND, pubkey, site(day), emptyList()) }.getOrNull()

    private const val PREFIX = "observer-"
    private val DAY = Regex("""\d{4}-\d{2}-\d{2}""")

    /**
     * Take one edition off the network.
     *
     * A NIP-09 deletion naming that day's address. This is only a plain,
     * one-event request BECAUSE each edition is its own site: when a single
     * site held every day as a path, removing Tuesday meant republishing a
     * manifest of everything except it, and a kind 5 against the site would
     * have deleted the lot — relays "delete all versions of the replaceable
     * event" named by an `a` tag.
     *
     * What it does not do is unmake the file. The blob is content-addressed on
     * somebody's media server and anybody holding the hash can still fetch it;
     * this removes the edition from the reader's site and from the archive
     * anyone reading their relays would see. Saying that plainly is the honest
     * version — content-addressed storage does not really forget.
     */
    fun deletion(
        pubkey: String,
        day: String,
        createdAt: Long,
    ): EventTemplate<Event> =
        Event.build(DeletionEvent.KIND, "Remove the edition for $day", createdAt) {
            add(arrayOf("a", "${NamedSiteEvent.KIND}:$pubkey:${site(day)}"))
            add(arrayOf("k", NamedSiteEvent.KIND.toString()))
        }

    /**
     * BUD-01 upload authorization: what may be uploaded, by whom, until when.
     *
     * The `x` tag binds the authorization to the SHA-256 of one specific blob,
     * so a leaked auth cannot be replayed to upload something else. Built by
     * hand rather than through quartz's `createUploadAuth` because that helper
     * signs as it builds, taking a signer, and the whole point here is to
     * produce the unsigned thing.
     */
    fun uploadAuth(
        sha256: String,
        size: Long,
        createdAt: Long,
        expiration: Long,
    ): EventTemplate<Event> {
        require(sha256.matches(Regex("^[0-9a-f]{64}$"))) { "not a sha256: $sha256" }
        return Event.build(BlossomAuthorizationEvent.KIND, "Upload today's edition", createdAt) {
            add(arrayOf("t", "upload"))
            add(arrayOf("x", sha256))
            add(arrayOf("size", size.toString()))
            add(arrayOf("expiration", expiration.toString()))
        }
    }

    /**
     * One day's edition: one page, at one path, under its own address.
     *
     * This used to take the WHOLE path list, because the site held every day at
     * once and a `kind 35128` replaces rather than appends — so a manifest
     * missing a day deleted it. There is nothing to lose here: this event names
     * today and nothing else, and yesterday's is a different event that no
     * publish will ever touch.
     */
    fun manifest(
        day: String,
        sha256: String,
        servers: List<String>,
        masthead: String,
        /**
         * The day's lead headline, so an archive can be read.
         *
         * Without it a back issue is a date, and a list of dates tells a reader
         * nothing about which paper was which — the alternative is fetching
         * every past page to read its `<h1>`, which is absurd for a list. It
         * costs one tag, and the site's own title tag was carrying
         * "The Nostr Observer — 2026-08-18", which says nothing the date does
         * not.
         */
        headline: String?,
        createdAt: Long,
    ): EventTemplate<Event> {
        require(sha256.matches(Regex("^[0-9a-f]{64}$"))) { "not a sha256: $sha256" }
        val tags = listOf(PathTag("/index.html", sha256))
        return Event.build(NamedSiteEvent.KIND, "", createdAt) {
            add(arrayOf("d", site(day)))
            sitePaths(tags)
            // The manifest's own server list comes first when a host resolves a
            // path; the reader's kind 10063 is the fallback. Naming them here
            // means an edition keeps resolving even if they later edit that list.
            siteServers(servers)
            siteTitle(masthead)
            headline?.takeIf { it.isNotBlank() }?.let { siteDescription(it.take(200)) }
            siteAggregateHash(tags)
        }
    }
}
