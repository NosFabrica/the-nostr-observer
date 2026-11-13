package com.nosfabrica.observer.runner

import com.nosfabrica.assistant.core.AssistantDirectory
import com.nosfabrica.assistant.core.AssistantKey
import com.nosfabrica.assistant.core.AssistantPair
import com.nosfabrica.assistant.core.KeyFailure
import com.nosfabrica.observer.frontpage.AgentModel
import com.nosfabrica.observer.frontpage.PrintResult
import com.nosfabrica.observer.frontpage.ProbeVerdict
import com.nosfabrica.observer.frontpage.WriterException
import com.nosfabrica.observer.frontpage.assemble
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip51Lists.encryption.PrivateTagsInContent
import com.vitorpamplona.quartz.nipFAAgentKeys.tags.AgentKeyTag
import com.vitorpamplona.quartz.utils.EventFactory
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.security.MessageDigest

fun relay(url: String): NormalizedRelayUrl = RelayUrlNormalizer.normalizeOrNull(url)!!

/** Parsed again through Quartz's factory, as a relay would deliver it: the typed class. */
fun typed(event: Event): Event =
    EventFactory.create(event.id, event.pubKey, event.createdAt, event.kind, event.tags, event.content, event.sig)

suspend fun NostrSignerInternal.signed(
    kind: Int,
    tags: Array<Array<String>>,
    content: String = "",
    createdAt: Long,
): Event = typed(sign(EventTemplate<Event>(createdAt, kind, tags, content)))

fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** brainstorm_server's key view in memory: each owner's Assistant signer, or why there is none. */
class MemoryDirectory(
    private val assistants: Map<HexKey, NostrSignerInternal>,
    private val failures: Map<HexKey, KeyFailure> = emptyMap(),
) : AssistantDirectory {
    override suspend fun page(
        after: HexKey?,
        limit: Int,
    ): List<AssistantPair> =
        assistants.keys
            .sorted()
            .filter { after == null || it > after }
            .take(limit)
            .map { AssistantPair(it, assistants.getValue(it).pubKey) }

    override suspend fun key(owner: HexKey): AssistantKey =
        failures[owner]?.let { AssistantKey.Failed(owner, it) }
            ?: assistants[owner]?.let { AssistantKey.Ready(owner, it) }
            ?: AssistantKey.Failed(owner, KeyFailure.NO_ASSISTANT)
}

/**
 * The real [LocalAssistant] (its allowlist and all) over [MemoryDirectory], on the test's clock,
 * recording what it decrypted and signed.
 */
class RecordingAssistant(
    assistants: Map<HexKey, NostrSignerInternal>,
    now: () -> Long,
    failures: Map<HexKey, KeyFailure> = emptyMap(),
) : AssistantSigner {
    val local = LocalAssistant(MemoryDirectory(assistants, failures), now)
    val decrypted = mutableListOf<Pair<HexKey, Int>>()
    val signedKinds = mutableListOf<Int>()

    suspend fun pairs() = local.pairs()

    override suspend fun assistantOf(owner: HexKey): HexKey = local.assistantOf(owner)

    override suspend fun decrypt(
        owner: HexKey,
        event: Event,
        schedule: Event?,
    ): String = local.decrypt(owner, event, schedule).also { synchronized(decrypted) { decrypted += owner to event.kind } }

    override suspend fun sign(
        owner: HexKey,
        template: EventTemplate<out Event>,
    ): Event = typed(local.sign(owner, template)).also { synchronized(signedKinds) { signedKinds += template.kind } }
}

/**
 * Relays in memory: each answers a filter with what it holds, by Quartz's own matcher, newest
 * first and cut at the filter's `limit` (or at the relay's [defaultLimit] when it gives none), as a relay does.
 */
class FakeRelays : RelayAccess {
    val held = HashMap<NormalizedRelayUrl, MutableList<Event>>()
    val published = mutableListOf<Pair<Event, Set<NormalizedRelayUrl>>>()
    val asked = mutableListOf<Pair<Set<NormalizedRelayUrl>, Filter>>()
    var down: Set<NormalizedRelayUrl> = emptySet()

    /** What a relay sends for a filter with no `limit`: many relays cut there without saying so. */
    var defaultLimit: (NormalizedRelayUrl) -> Int? = { null }

    /** Relays that answer a filter with a CLOSED instead (a REQ too large for them, say). */
    var refuses: (NormalizedRelayUrl, Filter) -> Boolean = { _, _ -> false }

    fun put(
        relay: NormalizedRelayUrl,
        vararg events: Event,
    ) {
        held.getOrPut(relay) { mutableListOf() }.addAll(events)
    }

    override suspend fun fetch(
        relays: Set<NormalizedRelayUrl>,
        filter: Filter,
    ): RelayRead {
        synchronized(asked) { asked += relays to filter }
        val up = relays - down
        val refused = up.filter { refuses(it, filter) }.toSet()
        val answering = up - refused
        val events =
            answering.flatMap { relay ->
                val matching = held[relay].orEmpty().filter { filter.match(it) }.sortedByDescending { it.createdAt }
                (filter.limit ?: defaultLimit(relay))?.let { matching.take(it) } ?: matching
            }
        return RelayRead(events.distinctBy { it.id }, answering, refused)
    }

    override suspend fun publish(
        event: Event,
        relays: Set<NormalizedRelayUrl>,
    ): Int {
        published += event to relays
        val up = relays - down
        up.forEach { put(it, event) }
        return up.size
    }
}

/** Blossom servers in memory, behind an OkHttp interceptor: no socket is ever opened. */
class FakeBlossom {
    val requests = mutableListOf<String>()
    val stored = HashMap<String, ByteArray>()

    /** Every server answers 503 while this is set. */
    var down = false

    val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    val host = request.url.host
                    requests += "${request.method} $host${request.url.encodedPath}"
                    val (code, body) =
                        when {
                            down -> 503 to ByteArray(0)
                            else -> answer(request)
                        }
                    Response
                        .Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("x")
                        .body(body.toResponseBody("application/octet-stream".toMediaType()))
                        .build()
                },
            ).build()

    private fun answer(request: Request): Pair<Int, ByteArray> {
        val host = request.url.host
        return when (request.method) {
            "PUT" -> {
                val buffer = Buffer()
                request.body!!.writeTo(buffer)
                val bytes = buffer.readByteArray()
                val sha = sha256Hex(bytes)
                stored["$host/$sha"] = bytes
                200 to """{"sha256":"$sha","url":"https://$host/$sha"}""".encodeToByteArray()
            }

            else -> {
                val sha = request.url.encodedPath.trim('/')
                stored["$host/$sha"]?.let { 200 to it } ?: (404 to ByteArray(0))
            }
        }
    }

    fun hostsWritten() = requests.filter { it.startsWith("PUT ") }.map { it.removePrefix("PUT ").substringBefore('/') }.toSet()
}

/** The engine's print, faked: it records what it was asked with and returns [result]. */
class FakePrinter(
    var result: (AgentKeyTag, String) -> PrintResult = { _, _ -> printed() },
) : PaperPrinter {
    val calls = mutableListOf<Triple<AgentKeyTag, String, HexKey>>()

    /** What each print was asked with beyond its key and model: the effort and the edition's context. */
    val options = mutableListOf<PrintOptions>()

    override suspend fun print(
        key: AgentKeyTag,
        model: String,
        owner: HexKey,
        readerName: String,
        options: PrintOptions,
    ): PrintResult {
        calls += Triple(key, model, owner)
        this.options += options
        return result(key, model)
    }

    companion object {
        fun printed(): PrintResult.Printed {
            val observer = "a".repeat(64)
            val corpus = assemble(observer, "wss://search.example", 20, 0, 86_400, emptyMap(), emptyList(), emptyMap())
            val html = "<!doctype html><html><head><title>The Nostr Observer</title></head><body><h1>Twelve relays</h1></body></html>"
            return PrintResult.Printed(html, corpus, emptyList(), 2, null, 1, "Twelve relays", 1000)
        }

        fun notReady() = PrintResult.NotReady(ProbeVerdict.PROJECTION_PENDING)

        fun failing(e: WriterException): (AgentKeyTag, String) -> PrintResult = { _, _ -> throw e }
    }
}

/** A NIP-FA key list's private tags as JSON, and the kind 12000 the Assistant signs over them. */
suspend fun agentKeyList(
    assistant: NostrSignerInternal,
    keys: List<AgentKeyTag>,
    createdAt: Long,
): Event {
    val json = PrivateTagsInContent.encode(keys.map { arrayOf("key", it.key, it.url) }.toTypedArray())
    return assistant.signed(12000, emptyArray(), assistant.nip44Encrypt(json, assistant.pubKey), createdAt)
}

fun newSigner() = NostrSignerInternal(KeyPair())

/** A models list as a provider answers it, for [ObserverRunner]'s default model. */
fun listed(vararg ids: String) = ids.map { AgentModel(it) }
