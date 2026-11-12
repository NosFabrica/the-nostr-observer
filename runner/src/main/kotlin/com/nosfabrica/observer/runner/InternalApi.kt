package com.nosfabrica.observer.runner

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.OptimizedJsonMapper
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** One owner and the Assistant brainstorm_server holds for them. */
data class AssistantPair(
    val owner: HexKey,
    val assistant: HexKey,
)

/** One page of [InternalApi.assistants]; [next] is the `after` of the next page, null at the end. */
data class AssistantPage(
    val items: List<AssistantPair>,
    val next: HexKey?,
)

/**
 * brainstorm_server's `/internal/observerRunner/...`: the only way the worker decrypts or signs,
 * as an owner's Assistant. The worker holds no nsec. See runner/README.md, "The contract".
 */
interface InternalApi {
    suspend fun assistants(after: HexKey?): AssistantPage

    /**
     * The plaintext of [event]'s content, decrypted by [owner]'s Assistant. The server takes only
     * the whole signed event: the owner's schedule naming that Assistant (kind 34817), or the
     * Assistant's own AI keys (kind 12000). It never takes a bare ciphertext.
     */
    suspend fun decrypt(
        owner: HexKey,
        event: Event,
    ): String

    /**
     * [template] signed by [owner]'s Assistant: an Observer site (35128) or a Blossom upload
     * authorisation (24242). The server replaces any `client` tag, so the caller uses the event
     * returned, never the one it sent.
     */
    suspend fun sign(
        owner: HexKey,
        template: EventTemplate<out Event>,
    ): Event
}

/** Why an internal call failed. Never carries a plaintext, a ciphertext or a key. */
open class InternalApiException(
    message: String,
    val status: Int? = null,
) : IOException(message)

/** 404 with `X-Reason: no-assistant`: this owner has no Assistant (any more). */
class NoAssistantException(
    val owner: HexKey,
) : InternalApiException("owner $owner has no Assistant", 404)

/** A plain 404: the token is wrong or unset on the server, or the route isn't there. Nothing will work. */
class RunnerTokenRejectedException : InternalApiException("brainstorm_server refused the runner token (404)", 404)

/** 403: the server refused to decrypt or sign this event. */
class RefusedByServerException(
    val detail: String,
) : InternalApiException("brainstorm_server refused: $detail", 403)

/**
 * [InternalApi] over HTTP. [client] reaches brainstorm_server on the internal network, so it is
 * NOT the guarded client the reader's URLs go through.
 */
class HttpInternalApi(
    baseUrl: String,
    private val token: String,
    private val client: OkHttpClient,
) : InternalApi {
    private val base = baseUrl.trimEnd('/') + "/internal/observerRunner"
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun assistants(after: HexKey?): AssistantPage {
        val url =
            ("$base/assistants")
                .toHttpUrlOrNull()!!
                .newBuilder()
                .apply {
                    if (after != null) addQueryParameter("after", after)
                    addQueryParameter("limit", PAGE.toString())
                }.build()
        val data = call(Request.Builder().url(url).get(), owner = null) as? JsonObject ?: throw InternalApiException("assistants: no data")
        val items =
            (data["items"] as? JsonArray).orEmpty().mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                val owner = o.string("owner")?.lowercase()?.takeIf(::isHex64) ?: return@mapNotNull null
                val assistant = o.string("assistant")?.lowercase()?.takeIf(::isHex64) ?: return@mapNotNull null
                AssistantPair(owner, assistant)
            }
        return AssistantPage(items, data.string("next")?.lowercase()?.takeIf(::isHex64))
    }

    override suspend fun decrypt(
        owner: HexKey,
        event: Event,
    ): String {
        val body =
            buildJsonObject {
                put("owner", owner)
                put("event", json.parseToJsonElement(event.toJson()))
            }
        val data = call(Request.Builder().url("$base/decrypt").post(body.toString().toRequestBody(JSON)), owner) as? JsonObject
        return data?.string("plaintext") ?: throw InternalApiException("decrypt: no plaintext")
    }

    override suspend fun sign(
        owner: HexKey,
        template: EventTemplate<out Event>,
    ): Event {
        val body =
            buildJsonObject {
                put("owner", owner)
                put(
                    "event",
                    buildJsonObject {
                        put("kind", template.kind)
                        put("created_at", template.createdAt)
                        put("tags", json.parseToJsonElement(OptimizedJsonMapper.toJson(template.tags)))
                        put("content", template.content)
                    },
                )
            }
        val data =
            call(Request.Builder().url("$base/sign").post(body.toString().toRequestBody(JSON)), owner)
                ?: throw InternalApiException("sign: no event")
        val signed =
            try {
                Event.fromJson(data.toString())
            } catch (e: Exception) {
                throw InternalApiException("sign: not an event")
            }
        // What comes back is what gets published: it must be a valid event of the kind asked for.
        if (signed.kind != template.kind || !signed.verify()) throw InternalApiException("sign: the signed event doesn't verify")
        return signed
    }

    private suspend fun call(
        request: Request.Builder,
        owner: HexKey?,
    ): JsonElement? =
        withContext(Dispatchers.IO) {
            val built = request.header("Authorization", "Bearer $token").header("Accept", "application/json").build()
            client.newCall(built).execute().use { response ->
                val text = response.body.string()
                when {
                    response.code == 404 && response.header(
                        "X-Reason",
                    ) == "no-assistant" && owner != null -> throw NoAssistantException(owner)

                    response.code == 404 -> throw RunnerTokenRejectedException()

                    // The server's own words: a refusal reason, never a payload (its rules).
                    response.code == 403 -> throw RefusedByServerException(detailOf(text))

                    !response.isSuccessful -> throw InternalApiException(
                        "${built.url.encodedPath} answered ${response.code}",
                        response.code,
                    )
                }
                val envelope =
                    try {
                        json.parseToJsonElement(text) as? JsonObject
                    } catch (e: IllegalArgumentException) {
                        null
                    } ?: throw InternalApiException("${built.url.encodedPath}: not JSON")
                envelope["data"]?.takeIf { it !is JsonNull }
            }
        }

    private fun detailOf(text: String): String =
        try {
            ((json.parseToJsonElement(text) as? JsonObject)?.let { it.string("detail") ?: it.string("message") } ?: "")
                .replace(Regex("\\s+"), " ")
                .take(160)
        } catch (e: IllegalArgumentException) {
            ""
        }

    companion object {
        const val PAGE = 500
        private val JSON = "application/json".toMediaType()
    }
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun isHex64(value: String) = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
