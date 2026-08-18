package com.nosfabrica.observer.nostr

import com.vitorpamplona.quartz.nip01Core.core.hexToByteArrayOrNull
import com.vitorpamplona.quartz.nip19Bech32.toNote
import com.vitorpamplona.quartz.nip19Bech32.toNpub

/**
 * How a key is allowed to appear in front of a person.
 *
 * THE RULE: no hex, anywhere a reader can see. Not on the page, not in the
 * console, not in a progress line or an error. A person is their NAME; if we do
 * not know their name they are their `npub`; they are never
 * `460c25e682fda783…`, which identifies them to a database and to nobody else.
 *
 * A truncated hex key is the worst of the options and it was the default in
 * four different places: it is unreadable, it is not a valid identifier anyone
 * can paste anywhere, and it looks like a bug in the page.
 */
object Names {
    /** The full `npub1…`, or null if that is not a key. */
    fun npub(hex: String): String? = hex.hexToByteArrayOrNull()?.toNpub()

    /** The full `note1…` for an event, or null. */
    fun note(hex: String): String? = hex.hexToByteArrayOrNull()?.toNote()

    /**
     * An npub short enough to sit in a byline.
     *
     * `npub1` plus 63 characters is an identifier, not a name, and printing all
     * of it where a name should go turns a column of text into a wall. The
     * head-and-tail form is what every Nostr client uses and what a reader
     * recognises, and the full value is never far away — this is a label, and
     * anything that needs to be pasted gets [npub].
     */
    fun short(hex: String): String {
        val full = npub(hex) ?: return "someone"
        return if (full.length <= 20) full else full.take(10) + "…" + full.takeLast(5)
    }
}
