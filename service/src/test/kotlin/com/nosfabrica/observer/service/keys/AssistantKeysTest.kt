package com.nosfabrica.observer.service.keys

import com.nosfabrica.observer.service.state.Db
import com.nosfabrica.observer.service.testing.BrainstormSchema
import com.nosfabrica.observer.service.testing.TestPostgres
import com.nosfabrica.observer.service.testing.capturingLogs
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip19Bech32.toNsec
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.security.SecureRandom
import java.sql.SQLException
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** brainstorm_server's `brainstorm_nsec` read as its repo reads it, its fallback rules, and nothing logged. */
class AssistantKeysTest {
    private class Person {
        val keys = KeyPair()
        val pubkey = keys.pubKey.toHexKey()
        val nsec = keys.privKey!!.toNsec()
        val privHex = keys.privKey!!.toHexKey()
    }

    private fun newFernetKey() = Base64.getUrlEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

    private val serverKey = newFernetKey()
    private val lostKey = newFernetKey()
    private val keyFile =
        FernetKeyFile(Files.createTempDirectory("keys").resolve("nsec_encryption_keys").also { Files.writeString(it, serverKey) })

    private fun encrypt(
        text: String,
        key: String = serverKey,
    ) = Fernet(key).encrypt(text.encodeToByteArray())

    private fun world(): Db = TestPostgres.database().also { BrainstormSchema.create(it) }

    @Test
    fun theServersFallbackRulesAndEveryFailure() =
        runTest {
            val db = world()
            val keys = AssistantKeys(db, keyFile)
            val secrets = ArrayList<String>()

            fun owner(
                assistant: Person = Person(),
                nsec: String = assistant.nsec,
                encrypted: String? = encrypt(assistant.nsec),
                withAssistant: Boolean = true,
            ): Pair<String, Person> {
                val o = Person().pubkey
                BrainstormSchema.insert(db, o, if (withAssistant) assistant.pubkey else null, nsec, encrypted)
                secrets += (listOf(assistant.nsec, assistant.privHex, nsec) + listOfNotNull(encrypted)).filter { it.isNotEmpty() }
                return o to assistant
            }

            val encrypted = owner()
            val legacy = owner(encrypted = null)
            val hexLegacy = Person().let { a -> owner(a, nsec = a.privHex, encrypted = null) }
            // brainstorm_nsec.nsec still holds a plaintext beside the token; the token wins, as on the server.
            val stalePlaintext = Person().let { a -> owner(a, nsec = Person().nsec, encrypted = encrypt(a.nsec)) }
            val neverEncrypted = Person().let { a -> owner(a, encrypted = a.nsec) }
            // The server's `if encrypted_nsec:` reads an empty token as none and falls back to `nsec`.
            val emptyToken = owner(encrypted = "")
            val unreadable = Person().let { a -> owner(a, encrypted = encrypt(a.nsec, lostKey)) }
            val notAnNsec = Person().let { a -> owner(a, encrypted = encrypt("hello")) }
            val mismatch = Person().let { a -> owner(a, encrypted = encrypt(Person().nsec)) }
            val noAssistant = owner(withAssistant = false)
            secrets += listOf(serverKey, lostKey)

            val (results, logs) =
                capturingLogs {
                    listOf(
                        encrypted,
                        legacy,
                        hexLegacy,
                        stalePlaintext,
                        neverEncrypted,
                        emptyToken,
                        unreadable,
                        notAnNsec,
                        mismatch,
                        noAssistant,
                    ).map { (o, _) -> keys.key(o) }
                }
            results.take(6).zip(listOf(encrypted, legacy, hexLegacy, stalePlaintext, neverEncrypted, emptyToken)).forEach { (r, expected) ->
                val ready = assertIs<AssistantKey.Ready>(r, r.toString())
                assertEquals(expected.second.pubkey, ready.signer.pubKey)
            }
            assertEquals(
                listOf(KeyFailure.NSEC_UNREADABLE, KeyFailure.NSEC_UNREADABLE, KeyFailure.NSEC_MISMATCH, KeyFailure.NO_ASSISTANT),
                results.drop(6).map { assertIs<AssistantKey.Failed>(it).failure },
            )
            assertEquals(KeyFailure.NO_ASSISTANT, assertIs<AssistantKey.Failed>(keys.key(Person().pubkey)).failure)
            for (secret in secrets) assertFalse(secret in logs, "logged a secret")
            for (r in results) for (secret in secrets) assertFalse(secret in r.toString())
        }

    @Test
    fun pairsArePagedWithoutDecryptingAnything() =
        runTest {
            val db = world()
            val people = List(7) { Person() to Person() }
            people.forEach { (o, a) -> BrainstormSchema.insert(db, o.pubkey, a.pubkey, "not read", "not a token either") }
            BrainstormSchema.insert(db, Person().pubkey, null, "x", null)
            // No key file at all: listing must not need one.
            val keys = AssistantKeys(db, FernetKeyFile(Files.createTempDirectory("none").resolve("missing")))
            val first = keys.page(null, 3)
            val second = keys.page(first.last().owner, 3)
            val third = keys.page(second.last().owner, 3)
            val expected = people.map { (o, a) -> AssistantPair(o.pubkey, a.pubkey) }.sortedBy { it.owner }
            assertEquals(expected, first + second + third)
            assertEquals(expected, keys.pairs())
        }

    @Test
    fun theServicesRoleReadsItsFourColumnsAndNoOther() =
        runTest {
            val db = world()
            val a = Person()
            val owner = Person().pubkey
            BrainstormSchema.insert(db, owner, a.pubkey, a.nsec, encrypt(a.nsec))
            val role = TestPostgres.serviceRole(db)
            val keys = AssistantKeys(role, keyFile)
            assertIs<AssistantKey.Ready>(keys.key(owner))
            assertEquals(listOf(AssistantPair(owner, a.pubkey)), keys.pairs())
            for (column in listOf("session_version", "nip05_local_part", "*")) {
                assertFailsWith<SQLException>(column) {
                    role.use { c -> c.createStatement().use { it.executeQuery("SELECT $column FROM brainstorm_nsec") } }
                }
            }
        }

    @Test
    fun anOwnerRowWithoutAnAssistantIsNoAssistantAndNotListed() =
        runTest {
            val db = world()
            val a = Person()
            val owner = Person().pubkey
            // A user who never got an Assistant: the server's row, with its nsec, and no assistant_pubkey.
            BrainstormSchema.insert(db, owner, null, a.nsec, encrypt(a.nsec))
            val keys = AssistantKeys(db, keyFile)
            assertEquals(KeyFailure.NO_ASSISTANT, assertIs<AssistantKey.Failed>(keys.key(owner)).failure)
            assertEquals(emptyList(), keys.pairs())
        }
}
