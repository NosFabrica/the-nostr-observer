package com.nosfabrica.observer.press

import com.nosfabrica.observer.press.publish.Countersign
import com.nosfabrica.observer.press.publish.Templates
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The two events a reader signs, and the checks on what comes back.
 *
 * These are the only places where this service acts on something it did not
 * produce itself, so they are the only places a lie can enter. Everything else
 * is a read.
 */
class TemplateTest {
    @Test
    fun `an upload authorization names one blob and expires`() {
        val sha = "a".repeat(64)
        val template = Templates.uploadAuth(sha, 42_000, 1_786_900_000, 1_786_900_600)
        val tags = template.tags.associate { it[0] to it[1] }

        assertEquals(24242, template.kind)
        assertEquals("upload", tags["t"])
        // Bound to one blob: a leaked authorization cannot be replayed to upload
        // something else under the reader's key.
        assertEquals(sha, tags["x"])
        assertEquals("42000", tags["size"])
        assertEquals("1786900600", tags["expiration"])
    }

    @Test
    fun `an upload authorization refuses anything that is not a hash`() {
        // The `x` tag is the only thing standing between "authorize this page"
        // and "authorize whatever turns up", so a malformed one is a stop.
        assertThrows(IllegalArgumentException::class.java) {
            Templates.uploadAuth("not-a-hash", 1, 1, 2)
        }
    }

    @Test
    fun `a manifest carries every day, because publishing replaces it`() {
        val days = listOf("/index.html" to "b".repeat(64), "/2026-08-18" to "b".repeat(64), "/2026-08-17" to "c".repeat(64))
        val template = Templates.manifest(days, listOf("https://blossom.example.com"), "The Nostr Observer", 1_786_900_000)

        assertEquals(35128, template.kind)
        assertEquals("observer", template.tags.first { it[0] == "d" }[1])
        val paths = template.tags.filter { it[0] == "path" }.map { it[1] to it[2] }
        // Yesterday is still in there. A kind 35128 REPLACES, so a manifest
        // holding only today is a manifest that deleted the archive.
        assertEquals(days, paths)
        assertTrue(template.tags.any { it[0] == "server" && it[1] == "https://blossom.example.com" })
    }

    @Test
    fun `a manifest with no paths is refused rather than published`() {
        assertThrows(IllegalArgumentException::class.java) {
            Templates.manifest(emptyList(), listOf("https://b.example.com"), "x", 1)
        }
    }
}

class CountersignTest {
    private val reader = KeyPair()
    private val stranger = KeyPair()
    private val readerHex = reader.pubKey.toHexKey()
    private val template = Templates.uploadAuth("d".repeat(64), 100, 1_786_900_000, 1_786_900_600)

    private fun sign(
        keys: KeyPair,
        template: EventTemplate<Event>,
    ): Event = NostrSignerSync(keys).sign(template.createdAt, template.kind, template.tags, template.content)

    @Test
    fun `the reader's own signature over our own template passes`() {
        val result = Countersign.check(sign(reader, template), template, readerHex)
        assertInstanceOf(Countersign.Result.Ok::class.java, result)
    }

    @Test
    fun `a real signature from the wrong person is still the wrong person`() {
        val result = Countersign.check(sign(stranger, template), template, readerHex)
        assertInstanceOf(Countersign.Result.No::class.java, result)
    }

    @Test
    fun `a valid signature over different tags does not pass`() {
        // The one that matters. The reader really did sign this, it really is
        // their key, and the blob hash is not the one we prepared -- so using it
        // would upload something we never checked, authorized by a signature we
        // did check. Without this the template would be decoration.
        val swapped = Templates.uploadAuth("e".repeat(64), 100, template.createdAt, 1_786_900_600)
        val result = Countersign.check(sign(reader, swapped), template, readerHex)
        assertEquals("tags do not match the template", (result as Countersign.Result.No).reason)
    }

    @Test
    fun `reordered tags are the same event`() {
        // Signers reorder tags, and several do. Rejecting that would break real
        // signers for no security gain: the set is what was signed.
        val reordered = EventTemplate<Event>(template.createdAt, template.kind, template.tags.reversedArray(), template.content)
        assertInstanceOf(Countersign.Result.Ok::class.java, Countersign.check(sign(reader, reordered), template, readerHex))
    }

    @Test
    fun `a forged signature fails even when everything else lines up`() {
        val honest = sign(reader, template)
        val forged = Event(honest.id, honest.pubKey, honest.createdAt, honest.kind, honest.tags, honest.content, "0".repeat(128))
        assertEquals("bad id or signature", (Countersign.check(forged, template, readerHex) as Countersign.Result.No).reason)
    }
}
