package com.nosfabrica.observer.press.publish

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate

/**
 * Did the signer sign what we asked for?
 *
 * The template goes out to a browser, and what comes back is whatever the
 * browser chose to send. Three separate things can be wrong and only the first
 * is obvious:
 *
 *  1. The signature is invalid, or the id does not hash the content.
 *  2. It is validly signed by SOMEBODY ELSE: a real event, wrong author.
 *  3. It is validly signed by the right reader but says something different
 *     from what we handed over, such as a different blob hash or path list.
 *
 * The third is the one that matters here. The server uploads a blob using this
 * authorization and publishes this manifest to the reader's relays; if the tags
 * can drift between issue and use, then the template was decoration.
 */
object Countersign {
    sealed interface Result {
        data class Ok(
            val event: Event,
        ) : Result

        data class No(
            val reason: String,
        ) : Result
    }

    fun check(
        signed: Event,
        template: EventTemplate<Event>,
        expectedPubkey: String,
    ): Result {
        if (signed.pubKey != expectedPubkey) {
            return Result.No("signed by a different key than the one signed in")
        }
        if (signed.kind != template.kind) return Result.No("kind ${signed.kind}, expected ${template.kind}")
        if (signed.createdAt != template.createdAt) return Result.No("created_at was changed")
        if (signed.content != template.content) return Result.No("content was changed")

        // Order-insensitive, because a signer is allowed to reorder tags and
        // several do. Multiplicity is not ignored: two `path` tags for the same
        // file is a different manifest from one.
        val asked = template.tags.map { it.toList() }.sortedBy { it.joinToString(" ") }
        val got = signed.tags.map { it.toList() }.sortedBy { it.joinToString(" ") }
        if (asked != got) return Result.No("tags do not match the template")

        // Last, because it is the expensive one and the cheap checks above have
        // already rejected everything that is merely wrong rather than forged.
        if (!signed.verify()) return Result.No("bad id or signature")
        return Result.Ok(signed)
    }
}
