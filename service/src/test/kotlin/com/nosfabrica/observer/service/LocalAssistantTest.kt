package com.nosfabrica.observer.service

import com.nosfabrica.assistant.core.KeyFailure
import com.nosfabrica.assistant.core.capturingLogs
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.utils.EventFactory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The Assistant's allowlist, ported case by case from brainstorm_server's
 * `tests/test_observer_runner.py` (its `decrypt` and `sign` sections), which tested the same rules
 * when the server enforced them. Two owners, A and B, each with an Assistant; signing and NIP-44
 * are real. What is gone with the HTTP API (the token, a malformed JSON body, a kind sent as a
 * string) has no case here: the events are typed.
 */
class LocalAssistantTest {
    private class Owner {
        val keys = newSigner()
        val assistant = newSigner()
        val pubkey get() = keys.pubKey
        val assistantPubkey get() = assistant.pubKey
    }

    private val a = Owner()
    private val b = Owner()
    private val now = 1_763_020_800L
    private val local = LocalAssistant(MemoryDirectory(mapOf(a.pubkey to a.assistant, b.pubkey to b.assistant)), { now })

    private suspend fun event(
        signer: NostrSignerInternal,
        kind: Int,
        content: String,
        tags: List<List<String>> = emptyList(),
        createdAt: Long = now - 60,
    ): Event = typed(signer.sign(EventTemplate<Event>(createdAt, kind, tags.map { it.toTypedArray() }.toTypedArray(), content)))

    /** The owner's kind 34817, encrypted owner -> their Assistant, naming it. */
    private suspend fun schedule(
        owner: Owner,
        runner: String? = null,
        tags: List<List<String>>? = null,
        private: String = SCHEDULE,
    ): Event =
        event(
            owner.keys,
            34817,
            owner.keys.nip44Encrypt(private, owner.assistantPubkey),
            tags ?: listOf(listOf("d", "observer"), listOf("p", runner ?: owner.assistantPubkey)),
        )

    /** The Assistant's own kind 12000, encrypted to itself. */
    private suspend fun aiKeys(owner: Owner): Event =
        event(owner.assistant, 12000, owner.assistant.nip44Encrypt(AI_KEYS, owner.assistantPubkey))

    private suspend fun resigned(
        e: Event,
        signer: NostrSignerInternal,
    ) = event(signer, e.kind, e.content, e.tags.map { it.toList() }, e.createdAt)

    private fun forged(
        e: Event,
        id: String = e.id,
        createdAt: Long = e.createdAt,
        sig: String = e.sig,
    ): Event = EventFactory.create(id, e.pubKey, createdAt, e.kind, e.tags, e.content, sig)

    private suspend fun refused(block: suspend () -> Unit) = assertFailsWith<RefusedException> { block() }

    // --- decrypt ---------------------------------------------------------------------------

    @Test
    fun decryptTheOwnersSchedule() =
        runTest {
            assertEquals(SCHEDULE, local.decrypt(a.pubkey, schedule(a)))
        }

    @Test
    fun decryptTheAssistantsOwnAiKeys() =
        runTest {
            assertEquals(AI_KEYS, local.decrypt(a.pubkey, aiKeys(a), schedule = schedule(a)))
        }

    @Test
    fun aScheduleSentBesideAScheduleIsIgnored() =
        runTest {
            assertEquals(SCHEDULE, local.decrypt(a.pubkey, schedule(a), schedule = schedule(b)))
        }

    @Test
    fun theAiKeysOpenOnlyBesideTheOwnersScheduleNamingTheAssistant() =
        runTest {
            val cases: Map<String, suspend () -> Event?> =
                mapOf(
                    "no-schedule" to { null },
                    // Another owner's schedule, even one naming A's Assistant.
                    "other-owners" to { schedule(b) },
                    "other-owner-naming-a" to {
                        event(
                            b.keys,
                            34817,
                            b.keys.nip44Encrypt(SCHEDULE, a.assistantPubkey),
                            listOf(listOf("d", "observer"), listOf("p", a.assistantPubkey)),
                        )
                    },
                    // A's schedule naming another runner, no runner, two.
                    "other-runner" to { schedule(a, runner = b.assistantPubkey) },
                    "no-runner" to { schedule(a, tags = listOf(listOf("d", "observer"))) },
                    "two-runners" to { schedule(a, tags = listOf(listOf("p", a.assistantPubkey), listOf("p", b.assistantPubkey))) },
                    // Not a verified 34817.
                    "bad-sig" to { forged(schedule(a), sig = schedule(b).sig) },
                    "tampered" to { schedule(a).let { forged(it, createdAt = it.createdAt + 1) } },
                    "kind-34818" to {
                        event(a.keys, 34818, a.keys.nip44Encrypt(SCHEDULE, a.assistantPubkey), listOf(listOf("p", a.assistantPubkey)))
                    },
                    "the-12000-itself" to { aiKeys(a) },
                )
            for ((name, make) in cases) {
                val (result, logs) = capturingLogs { runCatching { local.decrypt(a.pubkey, aiKeys(a), schedule = make()) } }
                assertIs<RefusedException>(result.exceptionOrNull(), name)
                assertTrue("owner=${a.pubkey} kind=12000 outcome=refused" in logs, name)
                assertFalse("sk-or-v1-very-secret" in logs, name)
            }
        }

    @Test
    fun decryptRefusesAnythingButTheTwoEvents() =
        runTest {
            val stranger = newSigner()
            val cases: Map<String, suspend () -> Event> =
                mapOf(
                    // Another kind, even one sealed between the same two keys.
                    "kind-4" to
                        { event(a.keys, 4, a.keys.nip44Encrypt(SCHEDULE, a.assistantPubkey), listOf(listOf("p", a.assistantPubkey))) },
                    "kind-14" to {
                        event(a.keys, 14, a.keys.nip44Encrypt(SCHEDULE, a.assistantPubkey), listOf(listOf("p", a.assistantPubkey)))
                    },
                    "kind-34818" to {
                        event(a.keys, 34818, a.keys.nip44Encrypt(SCHEDULE, a.assistantPubkey), listOf(listOf("p", a.assistantPubkey)))
                    },
                    // A bad signature, a bad id, a tampered date, a missing signature.
                    "bad-sig" to { forged(schedule(a), sig = schedule(b).sig) },
                    "bad-id" to { forged(schedule(a), id = "0".repeat(64)) },
                    "tampered-created_at" to { schedule(a).let { forged(it, createdAt = it.createdAt + 1) } },
                    "no-sig" to { forged(schedule(a), sig = "") },
                    // A schedule by another author: a stranger, A's Assistant, B.
                    "stranger-author" to { resigned(schedule(a), stranger) },
                    "assistant-author" to { resigned(schedule(a), a.assistant) },
                    "other-owner-author" to { resigned(schedule(a), b.keys) },
                    // No `p`, two `p`, a `p` naming another Assistant or the owner, a `p` with no value.
                    "no-p" to { schedule(a, tags = listOf(listOf("d", "observer"))) },
                    "two-p-same" to { schedule(a, tags = listOf(listOf("p", a.assistantPubkey), listOf("p", a.assistantPubkey))) },
                    "two-p" to { schedule(a, tags = listOf(listOf("p", a.assistantPubkey), listOf("p", b.assistantPubkey))) },
                    "p-other-assistant" to { schedule(a, runner = b.assistantPubkey) },
                    "p-owner" to { schedule(a, runner = a.pubkey) },
                    "empty-p" to { schedule(a, tags = listOf(listOf("p"))) },
                    // A 12000 by another Assistant, by the owner, by a stranger.
                    "12000-other-assistant" to { aiKeys(b) },
                    "12000-by-owner" to { resigned(aiKeys(a), a.keys) },
                    "12000-by-stranger" to { resigned(aiKeys(a), stranger) },
                )
            for ((name, make) in cases) {
                val e = make()
                // A valid schedule beside it, so a 12000 is refused for what it is.
                val (result, logs) = capturingLogs { runCatching { local.decrypt(a.pubkey, e, schedule = schedule(a)) } }
                assertIs<RefusedException>(result.exceptionOrNull(), name)
                assertTrue("owner=${a.pubkey}" in logs && "refused" in logs, name)
                assertFalse(SCHEDULE in logs || "sk-or-v1-very-secret" in logs, name)
            }
        }

    @Test
    fun aPWithoutAValueIsntAP() =
        runTest {
            // `["p"]` names nobody (NIP-FB): beside the Assistant's `p`, the schedule still names exactly the Assistant.
            val s = schedule(a, tags = listOf(listOf("d", "observer"), listOf("p"), listOf("p", a.assistantPubkey)))
            assertEquals(SCHEDULE, local.decrypt(a.pubkey, s))
            assertEquals(AI_KEYS, local.decrypt(a.pubkey, aiKeys(a), schedule = s))
        }

    @Test
    fun aBlankPIsAPThatIsntTheAssistant() =
        runTest {
            // `["p", ""]` counts (NIP-FB): it stops the owner's devices and nobody runs the task.
            val cases =
                mapOf(
                    "blank" to listOf(listOf("d", "observer"), listOf("p", "")),
                    "blank-then-assistant" to listOf(listOf("d", "observer"), listOf("p", ""), listOf("p", a.assistantPubkey)),
                    "assistant-then-blank" to listOf(listOf("d", "observer"), listOf("p", a.assistantPubkey), listOf("p", "")),
                    // Not the lowercase key: no runner, as Quartz reads it.
                    "uppercase" to listOf(listOf("d", "observer"), listOf("p", a.assistantPubkey.uppercase())),
                )
            for ((name, tags) in cases) {
                val s = schedule(a, tags = tags)
                assertFailsWith<RefusedException>(name) { local.decrypt(a.pubkey, s) }
                assertFailsWith<RefusedException>(name) { local.decrypt(a.pubkey, aiKeys(a), schedule = s) }
            }
        }

    @Test
    fun aPausedScheduleOpensNoAiKeys() =
        runTest {
            val cases =
                mapOf(
                    "public-paused" to
                        schedule(a, tags = listOf(listOf("d", "observer"), listOf("p", a.assistantPubkey), listOf("paused"))),
                    "private-paused" to schedule(a, private = """[["task","observer"],["paused"]]"""),
                )
            for ((name, s) in cases) {
                // The schedule itself still opens, so the worker can see it's paused.
                local.decrypt(a.pubkey, s)
                val (result, logs) = capturingLogs { runCatching { local.decrypt(a.pubkey, aiKeys(a), schedule = s) } }
                assertIs<RefusedException>(result.exceptionOrNull(), name)
                assertTrue("owner=${a.pubkey} kind=12000 outcome=refused (schedule paused)" in logs, name)
            }
        }

    @Test
    fun aScheduleTheAssistantCantReadOpensNoAiKeys() =
        runTest {
            // Sealed to someone else: the runner can't read it, so it can't run it.
            val s =
                event(
                    a.keys,
                    34817,
                    a.keys.nip44Encrypt(SCHEDULE, newSigner().pubKey),
                    listOf(listOf("d", "observer"), listOf("p", a.assistantPubkey)),
                )
            refused { local.decrypt(a.pubkey, aiKeys(a), schedule = s) }
            // Nor a private part that isn't a tag array.
            refused { local.decrypt(a.pubkey, aiKeys(a), schedule = schedule(a, private = """{"task":"observer"}""")) }
        }

    @Test
    fun aScheduleWithNoPrivateTagsOpensTheAiKeys() =
        runTest {
            val s = event(a.keys, 34817, "", listOf(listOf("d", "observer"), listOf("p", a.assistantPubkey)))
            assertEquals(AI_KEYS, local.decrypt(a.pubkey, aiKeys(a), schedule = s))
        }

    @Test
    fun aOwnerWithBsScheduleIsRefused() =
        runTest {
            // B's schedule, naming B's Assistant, sent as A's: refused. Sent as B's, it opens with B's Assistant.
            val s = schedule(b)
            refused { local.decrypt(a.pubkey, s) }
            refused { local.decrypt(a.pubkey, aiKeys(b), schedule = s) }
            assertEquals(SCHEDULE, local.decrypt(b.pubkey, s))
        }

    @Test
    fun bScheduleNamingAsAssistantIsRefusedForEitherOwner() =
        runTest {
            // Anyone can tag any Assistant: B naming A's Assistant prints nothing.
            val s =
                event(
                    b.keys,
                    34817,
                    b.keys.nip44Encrypt(SCHEDULE, a.assistantPubkey),
                    listOf(listOf("d", "observer"), listOf("p", a.assistantPubkey)),
                )
            refused { local.decrypt(a.pubkey, s) }
            refused { local.decrypt(b.pubkey, s) }
        }

    @Test
    fun aValidEventWhoseContentIsntForThePairDoesntDecrypt() =
        runTest {
            // Signed by A and naming A's Assistant, but sealed to someone else.
            val s =
                event(
                    a.keys,
                    34817,
                    a.keys.nip44Encrypt(SCHEDULE, newSigner().pubKey),
                    listOf(listOf("d", "observer"), listOf("p", a.assistantPubkey)),
                )
            assertFailsWith<UndecryptableException> { local.decrypt(a.pubkey, s) }
        }

    @Test
    fun anOwnerWithNoAssistantIsNoAssistant() =
        runTest {
            val stranger = Owner()
            assertFailsWith<NoAssistantException> { local.decrypt(stranger.pubkey, schedule(stranger)) }
            assertFailsWith<NoAssistantException> { local.sign(stranger.pubkey, site(stranger.pubkey)) }
        }

    @Test
    fun aKeyThatDoesntOpenIsAKeyFailureNeverADecrypt() =
        runTest {
            for (failure in listOf(KeyFailure.NSEC_UNREADABLE, KeyFailure.NSEC_MISMATCH)) {
                val broken = LocalAssistant(MemoryDirectory(mapOf(a.pubkey to a.assistant), mapOf(a.pubkey to failure)), { now })
                assertEquals(failure, assertFailsWith<AssistantKeyException> { broken.decrypt(a.pubkey, schedule(a)) }.failure)
                assertEquals(failure, assertFailsWith<AssistantKeyException> { broken.sign(a.pubkey, site(a.pubkey)) }.failure)
            }
        }

    @Test
    fun decryptLogsNoPlaintextOrCiphertext() =
        runTest {
            val s = schedule(a)
            val keys = aiKeys(a)
            val (_, logs) =
                capturingLogs {
                    local.decrypt(a.pubkey, s)
                    local.decrypt(a.pubkey, keys, schedule = s)
                    runCatching { local.decrypt(a.pubkey, schedule(b)) }
                }
            assertTrue("owner=${a.pubkey} kind=34817 outcome=ok" in logs)
            assertTrue("owner=${a.pubkey} kind=12000 outcome=ok" in logs)
            for (secret in listOf(SCHEDULE, AI_KEYS, "sk-or-v1-very-secret", s.content, keys.content)) {
                assertFalse(secret in logs, "a plaintext or ciphertext reached the log")
            }
        }

    // --- sign ------------------------------------------------------------------------------

    private fun siteTags(owner: String) =
        listOf(
            listOf("d", "observer"),
            listOf("p", owner),
            listOf("path", "/index.html", "ab".repeat(32)),
            listOf("title", "The Nostr Observer"),
        )

    private fun uploadTags(expiration: Long) =
        listOf(listOf("t", "upload"), listOf("x", "cd".repeat(32)), listOf("expiration", expiration.toString()))

    private fun template(
        kind: Int,
        tags: List<List<String>>,
        content: String = "",
        createdAt: Long = now,
    ) = EventTemplate<Event>(createdAt, kind, tags.map { it.toTypedArray() }.toTypedArray(), content)

    private fun site(owner: String) = template(35128, siteTags(owner))

    private fun Event.tagList() = tags.map { it.toList() }

    @Test
    fun signAnObserverSite() =
        runTest {
            val e = local.sign(a.pubkey, site(a.pubkey))
            assertTrue(e.verify())
            assertEquals(a.assistantPubkey, e.pubKey)
            assertEquals(35128, e.kind)
            assertEquals(siteTags(a.pubkey) + listOf(listOf("client", "Brainstorm")), e.tagList())
        }

    @Test
    fun signABlossomUploadAuthorization() =
        runTest {
            val tags = uploadTags(now + 300)
            val e = local.sign(a.pubkey, template(24242, tags, content = "Upload edition"))
            assertTrue(e.verify())
            assertEquals(a.assistantPubkey, e.pubKey)
            assertEquals(tags, e.tagList().take(3))
            assertEquals("Upload edition", e.content)
        }

    @Test
    fun theWorkersClientTagIsReplacedByBrainstorms() =
        runTest {
            val e = local.sign(a.pubkey, template(35128, siteTags(a.pubkey) + listOf(listOf("client", "Somebody else"))))
            assertEquals(listOf(listOf("client", "Brainstorm")), e.tagList().filter { it[0] == "client" })
        }

    @Test
    fun aSignForOneOwnerNeverProducesAnothersSignature() =
        runTest {
            for (owner in listOf(a, b)) assertEquals(owner.assistantPubkey, local.sign(owner.pubkey, site(owner.pubkey)).pubKey)
            // A's owner, B's site: refused, not signed by either.
            refused { local.sign(a.pubkey, site(b.pubkey)) }
        }

    @Test
    fun signRefusesEveryOtherKind() =
        runTest {
            for (kind in listOf(0, 1, 3, 5, 10002, 10040, 12000, 30382, 34817, 39999, 22242, 27235, 35129)) {
                assertFailsWith<RefusedException>("kind $kind") { local.sign(a.pubkey, template(kind, siteTags(a.pubkey))) }
            }
        }

    @Test
    fun signRefusesASiteThatIsntTheOwnersObserver() =
        runTest {
            val o = a.pubkey
            val t = siteTags(o)
            val cases =
                mapOf(
                    "no-d" to t.filter { it[0] != "d" },
                    "other-d" to t.map { if (it[0] == "d") listOf("d", "observer-2") else it },
                    "two-d" to t + listOf(listOf("d", "observer")),
                    "no-p" to t.filter { it[0] != "p" },
                    "other-p" to t.map { if (it[0] == "p") listOf("p", "0".repeat(64)) else it },
                    "two-p" to t + listOf(listOf("p", "0".repeat(64))),
                    "uppercase-p" to t.map { if (it[0] == "p") listOf("p", o.uppercase()) else it },
                    "empty-tag" to t + listOf(emptyList()),
                )
            for ((name, tags) in cases) assertFailsWith<RefusedException>(name) { local.sign(o, template(35128, tags)) }
        }

    @Test
    fun signRefusesAnyBlossomAuthButAShortUpload() =
        runTest {
            val soon = (now + 60).toString()
            val cases =
                mapOf(
                    "delete" to listOf(listOf("t", "delete"), listOf("expiration", soon)),
                    "list" to listOf(listOf("t", "list"), listOf("expiration", soon)),
                    "upload+delete" to listOf(listOf("t", "upload"), listOf("t", "delete"), listOf("expiration", soon)),
                    "no-t" to listOf(listOf("expiration", soon)),
                    "no-expiration" to listOf(listOf("t", "upload")),
                    "601s" to listOf(listOf("t", "upload"), listOf("expiration", (now + 601).toString())),
                    "a-day" to listOf(listOf("t", "upload"), listOf("expiration", (now + 86_400).toString())),
                    "expired" to listOf(listOf("t", "upload"), listOf("expiration", (now - 1).toString())),
                    "not-a-number" to listOf(listOf("t", "upload"), listOf("expiration", "soon")),
                    "superscript-two" to listOf(listOf("t", "upload"), listOf("expiration", "²")),
                    "superscript-digit" to listOf(listOf("t", "upload"), listOf("expiration", "¹" + soon.drop(1))),
                    "arabic-indic-digits" to
                        listOf(listOf("t", "upload"), listOf("expiration", soon.map { (0x0660 + (it - '0')).toChar() }.joinToString(""))),
                    "leading-space" to listOf(listOf("t", "upload"), listOf("expiration", " $soon")),
                    "plus-sign" to listOf(listOf("t", "upload"), listOf("expiration", "+$soon")),
                    "eleven-digits" to listOf(listOf("t", "upload"), listOf("expiration", "1".repeat(11))),
                    "two-expirations" to listOf(listOf("t", "upload"), listOf("expiration", soon), listOf("expiration", soon)),
                )
            for ((name, tags) in cases) assertFailsWith<RefusedException>(name) { local.sign(a.pubkey, template(24242, tags)) }
        }

    @Test
    fun signRefusesABlossomAuthNamingTheAssistant() =
        runTest {
            for (p in listOf(a.assistantPubkey, a.assistantPubkey.uppercase())) {
                val e =
                    assertFailsWith<RefusedException> {
                        local.sign(
                            a.pubkey,
                            template(
                                24242,
                                uploadTags(now + 300) + listOf(listOf("p", p)),
                            ),
                        )
                    }
                assertTrue("p tag" in e.reason)
            }
            // A `p` naming someone else is the worker's business.
            val e = local.sign(a.pubkey, template(24242, uploadTags(now + 300) + listOf(listOf("p", a.pubkey))))
            assertEquals(listOf("p", a.pubkey), e.tagList()[e.tags.size - 2])
        }

    @Test
    fun signRefusesAnEventDatedFarAhead() =
        runTest {
            refused { local.sign(a.pubkey, template(35128, siteTags(a.pubkey), createdAt = now + 3_600)) }
            // Ten minutes is the limit, as for an expiration.
            local.sign(a.pubkey, template(35128, siteTags(a.pubkey), createdAt = now + 600))
        }

    companion object {
        const val SCHEDULE = """[["task","observer"],["param","key","https://openrouter.ai/api/v1","a3f9"]]"""
        const val AI_KEYS = """[["key","sk-or-v1-very-secret","https://openrouter.ai/api/v1"]]"""
    }
}
