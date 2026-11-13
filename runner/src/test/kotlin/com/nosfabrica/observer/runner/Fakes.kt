package com.nosfabrica.observer.runner

import com.nosfabrica.observer.frontpage.AgentModel
import com.nosfabrica.observer.frontpage.PrintResult
import com.nosfabrica.observer.frontpage.ProbeVerdict
import com.nosfabrica.observer.frontpage.WriterException
import com.nosfabrica.observer.frontpage.assemble
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip51Lists.encryption.PrivateTagsInContent
import com.vitorpamplona.quartz.nipFAAgentKeys.tags.AgentKeyTag
import com.vitorpamplona.quartz.utils.EventFactory
import com.vitorpamplona.quartz.utils.Log
import com.vitorpamplona.quartz.utils.LogSink
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
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

/**
 * brainstorm_server's internal API, with its rules (contract section 2 and 4): it decrypts a
 * whole signed schedule only when its author is the owner and its one `p` is the owner's
 * Assistant, the Assistant's own kind 12000, nothing else; it signs 35128 and 24242 only, and
 * replaces the `client` tag with its own.
 */
class FakeInternalApi(
    val assistants: Map<HexKey, NostrSignerInternal>,
) : InternalApi {
    val decrypted = mutableListOf<Pair<HexKey, Int>>()
    val signedKinds = mutableListOf<Int>()

    override suspend fun assistants(after: HexKey?): AssistantPage {
        val sorted = assistants.entries.sortedBy { it.key }.filter { after == null || it.key > after }
        val page = sorted.take(1)
        return AssistantPage(page.map { AssistantPair(it.key, it.value.pubKey) }, if (sorted.size > 1) page.last().key else null)
    }

    override suspend fun decrypt(
        owner: HexKey,
        event: Event,
        schedule: Event?,
    ): String {
        val assistant = assistants[owner] ?: throw NoAssistantException(owner)
        if (!event.verify()) throw RefusedByServerException("bad signature")

        fun isOwnersSchedule(e: Event): Boolean {
            val p = e.tags.filter { it.size > 1 && it[0] == "p" }.map { it[1] }
            return e.kind == 34817 && e.verify() && e.pubKey == owner && p == listOf(assistant.pubKey)
        }
        val peer =
            when (event.kind) {
                34817 -> {
                    if (!isOwnersSchedule(event)) throw RefusedByServerException("not this owner's schedule")
                    owner
                }

                12000 -> {
                    if (event.pubKey != assistant.pubKey) throw RefusedByServerException("not the Assistant's keys")
                    // The keys open only for a print the owner asked this Assistant for (contract section 2).
                    if (schedule == null || !isOwnersSchedule(schedule)) throw RefusedByServerException("no schedule")
                    assistant.pubKey
                }

                else -> {
                    throw RefusedByServerException("kind")
                }
            }
        decrypted += owner to event.kind
        return assistant.nip44Decrypt(event.content, peer)
    }

    override suspend fun sign(
        owner: HexKey,
        template: EventTemplate<out Event>,
    ): Event {
        val assistant = assistants[owner] ?: throw NoAssistantException(owner)

        fun values(name: String) = template.tags.filter { it.isNotEmpty() && it[0] == name }.map { it.getOrElse(1) { "" } }
        when (template.kind) {
            35128 -> {
                if (values("d") != listOf("observer") || values("p") != listOf(owner)) throw RefusedByServerException("site shape")
            }

            24242 -> {
                if (values("t") != listOf("upload") ||
                    values("expiration").singleOrNull()?.toLongOrNull() == null
                ) {
                    throw RefusedByServerException("auth shape")
                }
            }

            else -> {
                throw RefusedByServerException("kind")
            }
        }
        signedKinds += template.kind
        val tags = template.tags.filter { it.isEmpty() || it[0] != "client" }.toTypedArray() + arrayOf(arrayOf("client", "Brainstorm"))
        return typed(assistant.sign(EventTemplate<Event>(template.createdAt, template.kind, tags, template.content)))
    }
}

/** Relays in memory: each answers a filter with what it holds, by Quartz's own matcher. */
class FakeRelays : RelayAccess {
    val held = HashMap<NormalizedRelayUrl, MutableList<Event>>()
    val published = mutableListOf<Pair<Event, Set<NormalizedRelayUrl>>>()
    val asked = mutableListOf<Pair<Set<NormalizedRelayUrl>, Filter>>()
    var down: Set<NormalizedRelayUrl> = emptySet()

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
        asked += relays to filter
        val up = relays - down
        return RelayRead(up.flatMap { held[it].orEmpty() }.filter { filter.match(it) }.distinctBy { it.id }, up)
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

    override suspend fun print(
        key: AgentKeyTag,
        model: String,
        owner: HexKey,
        readerName: String,
    ): PrintResult {
        calls += Triple(key, model, owner)
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

/** Everything logged, through Quartz's sink and on stdout/stderr, while [block] runs. */
suspend fun <T> capturingLogs(block: suspend () -> T): Pair<T, String> {
    val lines = StringBuilder()
    val oldSink = Log.sink
    val oldOut = System.out
    val oldErr = System.err
    val stream = ByteArrayOutputStream()
    Log.sink = LogSink { level, tag, message, throwable -> lines.append("$level [$tag] $message ${throwable?.toString().orEmpty()}\n") }
    System.setOut(PrintStream(stream, true))
    System.setErr(PrintStream(stream, true))
    try {
        val result = block()
        return result to (lines.toString() + stream.toString())
    } finally {
        Log.sink = oldSink
        System.setOut(oldOut)
        System.setErr(oldErr)
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
