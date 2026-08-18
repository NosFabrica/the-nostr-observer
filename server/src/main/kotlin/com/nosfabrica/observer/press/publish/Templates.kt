package com.nosfabrica.observer.press.publish

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip5aStaticWebsites.NamedSiteEvent
import com.vitorpamplona.quartz.nip5aStaticWebsites.siteAggregateHash
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
    /** The site's `d` tag. One named site per reader, so their other nsites are untouched. */
    const val SITE = "observer"

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
     * The whole archive, as one replaceable event.
     *
     * `kind 35128` REPLACES: publishing today's edition with only today's path
     * tag does not add a day, it deletes every other day. So [paths] must always
     * be the complete list, rebuilt from our own index of what this reader has
     * published. Most nsite material in circulation still describes `kind 34128`,
     * one event per file, which is deprecated and would not have this hazard.
     */
    fun manifest(
        paths: List<Pair<String, String>>,
        servers: List<String>,
        masthead: String,
        createdAt: Long,
    ): EventTemplate<Event> {
        require(paths.isNotEmpty()) { "a site with no paths is a deletion, not a publish" }
        val tags = paths.map { PathTag(it.first, it.second) }
        return Event.build(NamedSiteEvent.KIND, "", createdAt) {
            add(arrayOf("d", SITE))
            sitePaths(tags)
            // The manifest's own server list comes first when a host resolves a
            // path; the reader's kind 10063 is the fallback. Naming them here
            // means an edition keeps resolving even if they later edit that list.
            siteServers(servers)
            siteTitle(masthead)
            siteAggregateHash(tags)
        }
    }
}
