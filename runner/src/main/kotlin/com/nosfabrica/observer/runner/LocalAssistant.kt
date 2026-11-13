package com.nosfabrica.observer.runner

import com.nosfabrica.assistant.core.AssistantDirectory
import com.nosfabrica.assistant.core.AssistantKey
import com.nosfabrica.assistant.core.AssistantPair
import com.nosfabrica.assistant.core.KeyFailure
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.utils.Log
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * What the worker does as an owner's Assistant: decrypt the two events a print needs and sign the
 * two it publishes. [LocalAssistant] does it with the Assistant's own key; tests may wrap it.
 */
interface AssistantSigner {
    /** [owner]'s Assistant, once its key is known to open. */
    suspend fun assistantOf(owner: HexKey): HexKey

    /**
     * The plaintext of [event]'s content, decrypted by [owner]'s Assistant. Only the whole signed
     * event: the owner's schedule naming that Assistant (kind 34817), or the Assistant's own AI
     * keys (kind 12000), which open only beside the owner's signed [schedule] naming that Assistant
     * and not paused. Never a bare ciphertext.
     */
    suspend fun decrypt(
        owner: HexKey,
        event: Event,
        schedule: Event? = null,
    ): String

    /**
     * [template] signed by [owner]'s Assistant: an Observer site (35128) or a Blossom upload
     * authorisation (24242), with the `client` tag replaced by Brainstorm's. The caller publishes
     * the event returned, never the one it built.
     */
    suspend fun sign(
        owner: HexKey,
        template: EventTemplate<out Event>,
    ): Event
}

/** Why the Assistant can't act for an owner. Never carries a plaintext, a ciphertext or a key. */
open class AssistantException(
    message: String,
) : Exception(message)

/** The key view has no Assistant for this owner (any more). */
class NoAssistantException(
    val owner: HexKey,
) : AssistantException("owner $owner has no Assistant")

/** The owner's Assistant key is there but doesn't open ([KeyFailure.NSEC_UNREADABLE]) or isn't the stored Assistant's ([KeyFailure.NSEC_MISMATCH]). */
class AssistantKeyException(
    val owner: HexKey,
    val failure: KeyFailure,
) : AssistantException("owner $owner: $failure")

/** The allowlist refused the event: [reason] is ours, never the event's. */
class RefusedException(
    val reason: String,
) : AssistantException("refused: $reason")

/** An event the allowlist takes, whose content doesn't open for the pair. */
class UndecryptableException : AssistantException("the content doesn't decrypt")

/**
 * The owner's Assistant, with its key from brainstorm_server's view ([AssistantDirectory], `:core`'s
 * `AssistantKeys` in production), and exactly the allowlist brainstorm_server's
 * `observer_runner_service.py` enforced when the worker asked it to decrypt and sign:
 *
 * - **decrypt** only a whole event whose id and signature verify: the owner's 34817 whose one `p`
 *   (a `p` with a value, as NIP-FB counts them) is that Assistant, lowercase, with the owner as
 *   peer; or the Assistant's own 12000, with itself as peer, and only beside such a 34817 that
 *   isn't paused, publicly or in its private tags (which must open). A schedule sent beside a
 *   34817 is ignored.
 * - **sign** only 35128 (exactly one `d` = `observer`, exactly one `p` = the owner) and 24242
 *   (exactly one `t` = `upload`, one `expiration` of ASCII digits within the next 10 minutes, no
 *   `p` naming the Assistant in any case), dated at most 10 minutes ahead, with the `client` tag
 *   replaced by `["client","Brainstorm"]`.
 *
 * What it can't tell, like the server before it, is whether a schedule is the owner's newest
 * version: [ObserverRunner] reads the newest by author, with a quorum, and again right before
 * spending.
 *
 * The key is read for each call and held only while it runs. Logs carry the owner, the kind and
 * the outcome; never a plaintext, a ciphertext or a key.
 */
class LocalAssistant(
    private val directory: AssistantDirectory,
    private val now: () -> Long = { TimeUtils.now() },
) : AssistantSigner {
    /** Every (owner, Assistant) the view holds; nothing is decrypted to list them. */
    suspend fun pairs(): List<AssistantPair> = directory.pairs()

    override suspend fun assistantOf(owner: HexKey): HexKey = signerOf(owner).pubKey

    private suspend fun signerOf(owner: HexKey): NostrSignerInternal =
        when (val key = directory.key(owner)) {
            is AssistantKey.Ready -> key.signer
            is AssistantKey.Failed -> {
                if (key.failure == KeyFailure.NO_ASSISTANT) throw NoAssistantException(owner)
                throw AssistantKeyException(owner, key.failure)
            }
        }

    override suspend fun decrypt(
        owner: HexKey,
        event: Event,
        schedule: Event?,
    ): String {
        // Logged, so only ever one of ours: the event is the caller's.
        val kind = event.kind.takeIf { it in DECRYPTABLE_KINDS } ?: throw refuseDecrypt(owner, "other", "kind")
        if (kind == AGENT_KEYS_KIND && schedule == null) throw refuseDecrypt(owner, kind, "no schedule")
        if (!event.verify()) throw refuseDecrypt(owner, kind, "signature")
        val signer = signerOf(owner)
        val assistant = signer.pubKey
        val peer =
            if (kind == SCHEDULE_KIND) {
                scheduleRefusal(event, owner, assistant)?.let { throw refuseDecrypt(owner, kind, it) }
                owner
            } else {
                if (event.pubKey != assistant) throw refuseDecrypt(owner, kind, "author")
                // The keys open only for a print the owner asked this Assistant for.
                val why =
                    when {
                        schedule!!.kind != SCHEDULE_KIND -> "schedule kind"
                        !schedule.verify() -> "schedule signature"
                        else -> scheduleRefusal(schedule, owner, assistant) ?: pausedRefusal(schedule, signer, owner)
                    }
                if (why != null) throw refuseDecrypt(owner, kind, "schedule $why")
                assistant
            }
        val plaintext =
            try {
                signer.nip44Decrypt(event.content, peer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The class isn't logged either: Quartz wraps what the cipher said.
                Log.i(TAG) { "decrypt owner=$owner kind=$kind outcome=failed" }
                throw UndecryptableException()
            }
        Log.d(TAG) { "decrypt owner=$owner kind=$kind outcome=ok" }
        return plaintext
    }

    override suspend fun sign(
        owner: HexKey,
        template: EventTemplate<out Event>,
    ): Event {
        val signer = signerOf(owner)
        val assistant = signer.pubKey
        signingRefusal(owner, assistant, template.kind, template.createdAt, template.tags, now())?.let {
            Log.i(TAG) { "sign owner=$owner kind=${template.kind.takeIf { k -> k in SIGNABLE_KINDS } ?: "other"} outcome=refused" }
            throw RefusedException(it)
        }
        val tags = template.tags.filter { it[0] != CLIENT_TAG[0] }.toTypedArray() + arrayOf(CLIENT_TAG)
        val signed = signer.sign<Event>(template.createdAt, template.kind, tags, template.content)
        // What leaves here is what gets published: it must be exactly what was asked, signed by the Assistant.
        val ok =
            signed.pubKey == assistant &&
                signed.kind == template.kind &&
                signed.content == template.content &&
                signed.tags.size == tags.size &&
                signed.tags.indices.all { signed.tags[it].contentEquals(tags[it]) } &&
                signed.verify()
        if (!ok) {
            Log.e(TAG) { "sign owner=$owner kind=${template.kind} outcome=failed its own check" }
            throw AssistantException("the signed event failed its own check")
        }
        Log.d(TAG) { "sign owner=$owner kind=${template.kind} outcome=signed" }
        return signed
    }

    private fun refuseDecrypt(
        owner: HexKey,
        kind: Any,
        why: String,
    ): RefusedException {
        Log.i(TAG) { "decrypt owner=$owner kind=$kind outcome=refused ($why)" }
        return RefusedException(why)
    }

    /**
     * Why the decrypted private tags of [schedule] (verified, naming the Assistant) ask for no print
     * now: paused, publicly or privately, or private tags this Assistant can't read (then it can't
     * run it either). Null when it may.
     */
    private suspend fun pausedRefusal(
        schedule: Event,
        signer: NostrSignerInternal,
        owner: HexKey,
    ): String? {
        if (schedule.tags.any { it.isNotEmpty() && it[0] == PAUSED }) return "paused"
        if (schedule.content.isBlank()) return null
        val private =
            try {
                Json.parseToJsonElement(signer.nip44Decrypt(schedule.content, owner)) as? JsonArray
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: return "content"
        val paused =
            private.any { tag ->
                val first = (tag as? JsonArray)?.firstOrNull() as? JsonPrimitive
                first != null && first.isString && first.content == PAUSED
            }
        return if (paused) "paused" else null
    }

    companion object {
        private const val TAG = "LocalAssistant"

        const val SCHEDULE_KIND = 34817
        const val AGENT_KEYS_KIND = 12000
        const val OBSERVER_SITE_KIND = 35128
        const val BLOSSOM_AUTH_KIND = 24242
        private val DECRYPTABLE_KINDS = setOf(SCHEDULE_KIND, AGENT_KEYS_KIND)
        private val SIGNABLE_KINDS = setOf(OBSERVER_SITE_KIND, BLOSSOM_AUTH_KIND)

        const val OBSERVER_SITE_D = "observer"
        const val BLOSSOM_UPLOAD = "upload"
        private const val PAUSED = "paused"

        /** Every event the Assistant signs carries this, in place of the worker's own. */
        val CLIENT_TAG = arrayOf("client", "Brainstorm")

        /** How far ahead a Blossom authorisation may expire, and an event be dated. */
        const val MAX_AHEAD_SECONDS = 600L

        /** An `expiration` as NIP-40 writes it: ASCII digits only (not `²`, not another script's digits, no sign or space). */
        private val SECONDS = Regex("[0-9]{1,10}")

        /** The value of every `p` that counts (NIP-FB): one with a value, a blank one included; `["p"]` names nobody. */
        private fun runnerValues(tags: Array<Array<String>>): List<String> = tags.filter { it.size > 1 && it[0] == "p" }.map { it[1] }

        /**
         * Why a verified 34817 isn't [owner]'s schedule naming [assistant], or null. The `p` must be
         * the Assistant's key exactly, lowercase, as Quartz's runner rule reads it.
         */
        fun scheduleRefusal(
            event: Event,
            owner: HexKey,
            assistant: HexKey,
        ): String? {
            if (event.pubKey != owner) return "author"
            val runners = runnerValues(event.tags)
            if (runners.size != 1) return "p count"
            if (runners[0] != assistant) return "p"
            return null
        }

        /** Each [name] tag's value ("" when it has none), in order. */
        private fun values(
            tags: Array<Array<String>>,
            name: String,
        ): List<String> = tags.filter { it[0] == name }.map { it.getOrElse(1) { "" } }

        /** Why [owner]'s Assistant ([assistant]) may not sign this event, or null when it may. */
        fun signingRefusal(
            owner: HexKey,
            assistant: HexKey,
            kind: Int,
            createdAt: Long,
            tags: Array<Array<String>>,
            now: Long,
        ): String? {
            if (kind !in SIGNABLE_KINDS) return "This kind isn't signed for the Observer runner."
            // The server's API refused an empty tag before its rules ran (a 422); here it is a refusal.
            if (tags.any { it.isEmpty() }) return "An empty tag."
            if (createdAt > now + MAX_AHEAD_SECONDS) return "created_at is more than 10 minutes ahead."
            if (kind == OBSERVER_SITE_KIND) {
                if (values(tags, "d") != listOf(OBSERVER_SITE_D)) return "An Observer site needs exactly one d tag, observer."
                if (values(tags, "p") != listOf(owner)) return "An Observer site needs exactly one p tag, the owner."
                return null
            }
            if (values(tags, "t") != listOf(BLOSSOM_UPLOAD)) return "A Blossom authorization is signed for upload only."
            if (values(tags, "p").any { it.lowercase() == assistant }) return "A Blossom authorization can't name the Assistant in a p tag."
            val expirations = values(tags, "expiration")
            if (expirations.size != 1 || !SECONDS.matches(expirations[0])) return "A Blossom authorization needs one expiration."
            val expiration = expirations[0].toLong()
            if (expiration <= now || expiration > now + MAX_AHEAD_SECONDS) {
                return "A Blossom authorization must expire within the next 10 minutes."
            }
            return null
        }
    }
}
