package com.nosfabrica.observer.service.keys

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Against tokens Python's `cryptography.fernet` made (core/src/test/resources/fernet/vectors.json). */
class FernetTest {
    private val vectors: JsonObject =
        Json.parseToJsonElement(javaClass.getResource("/fernet/vectors.json")!!.readText()).jsonObject
    private val keys = vectors["keys"]!!.jsonArray.map { it.jsonPrimitive.content }
    private val plaintext = vectors["plaintext"]!!.jsonPrimitive.content
    private val tokenK1 = vectors["token_k1"]!!.jsonPrimitive.content
    private val tokenK2 = vectors["token_k2"]!!.jsonPrimitive.content
    private val emptyK1 = vectors["token_empty_k1"]!!.jsonPrimitive.content

    private val k1 = Fernet(keys[0])
    private val k2 = Fernet(keys[1])

    @Test
    fun pythonsTokensOpenWithTheirKeyAndNoOther() {
        assertEquals(plaintext, k1.decrypt(tokenK1)!!.decodeToString())
        assertEquals(plaintext, k2.decrypt(tokenK2)!!.decodeToString())
        assertNull(k2.decrypt(tokenK1))
        assertNull(k1.decrypt(tokenK2))
        assertContentEquals(ByteArray(0), k1.decrypt(emptyK1))
    }

    @Test
    fun multiFernetTriesTheKeysInOrder() {
        for (order in listOf(listOf(k1, k2), listOf(k2, k1))) {
            val multi = MultiFernet(order)
            assertEquals(plaintext, multi.decrypt(tokenK1)!!.decodeToString())
            assertEquals(plaintext, multi.decrypt(tokenK2)!!.decodeToString())
        }
        assertNull(MultiFernet(listOf(k2)).decrypt(tokenK1))
        assertNull(MultiFernet(emptyList()).decrypt(tokenK1))
    }

    @Test
    fun theKeyFileTextIsReadAsTheServerReadsIt() {
        var skipped = 0
        val multi = MultiFernet.parse(" ${keys[1]} ,, not-a-key ,${keys[0]}\n") { skipped++ }
        assertEquals(2, multi.keys.size)
        assertEquals(1, skipped)
        assertEquals(plaintext, multi.decrypt(tokenK1)!!.decodeToString())
    }

    @Test
    fun aTamperedTokenOpensWithNoKey() {
        val raw = Base64.getUrlDecoder().decode(tokenK1)

        fun flipped(at: Int) = Base64.getUrlEncoder().encodeToString(raw.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() })
        // The version, the timestamp, the IV, the ciphertext and the HMAC: every byte is covered.
        for (at in listOf(0, 3, 12, 30, raw.size - 33, raw.size - 1)) assertNull(k1.decrypt(flipped(at)), "byte $at")
        assertNull(k1.decrypt(Base64.getUrlEncoder().encodeToString(raw.copyOf(raw.size - 1))))
        assertNull(k1.decrypt(Base64.getUrlEncoder().encodeToString(raw.copyOf(raw.size - 32))))
        assertNull(k1.decrypt("not base64 at all!"))
        assertNull(k1.decrypt(""))
    }

    @Test
    fun whatItEncryptsItDecrypts() {
        val token = k1.encrypt("an nsec".encodeToByteArray(), at = 1_700_000_000)
        assertTrue(token.startsWith("gAAAAA"))
        assertEquals("an nsec", k1.decrypt(token)!!.decodeToString())
        assertNull(k2.decrypt(token))
    }

    @Test
    fun aKeyThatIsNotOneIsRefusedWithoutEchoingIt() {
        val bad = "c2hvcnQ="
        val e = assertFailsWith<IllegalArgumentException> { Fernet(bad) }
        assertFalse(bad in e.message.orEmpty())
        assertFailsWith<IllegalArgumentException> { Fernet("!!!!") }
        assertFalse(keys[0] in k1.toString())
    }
}
