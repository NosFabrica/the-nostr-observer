package com.nosfabrica.observer.runner

import com.nosfabrica.observer.frontpage.ObserverPublisher
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nipB7Blossom.BlossomAuthorizationEvent
import com.vitorpamplona.quartz.utils.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

/**
 * BUD-01 download and BUD-02 upload, over the guarded client: a reader's Blossom server is a
 * URL they wrote, so it goes through the same SSRF checks as their AI key ([guardedClient]).
 */
class BlossomHttp(
    private val client: OkHttpClient,
) {
    /** The blob [sha256] from the first of [servers] that serves bytes with that hash. */
    suspend fun download(
        servers: List<String>,
        sha256: HexKey,
    ): ByteArray? =
        withContext(Dispatchers.IO) {
            for (server in servers) {
                val bytes =
                    try {
                        client
                            .newCall(
                                Request
                                    .Builder()
                                    .url(blobUrl(server, sha256))
                                    .get()
                                    .build(),
                            ).execute()
                            .use { response ->
                                if (response.isSuccessful) response.body.bytes() else null
                            }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG) { "Could not download $sha256 from ${hostOf(server)}: ${e.javaClass.simpleName}" }
                        null
                    } ?: continue
                // A Blossom server is untrusted: only bytes that hash to the name are the file.
                if (ObserverPublisher.sha256Hex(bytes).equals(sha256, ignoreCase = true)) return@withContext bytes
                Log.w(TAG) { "${hostOf(server)} served the wrong bytes for $sha256" }
            }
            null
        }

    /** PUT [bytes] to [server]'s `/upload` with [authHeader]; true when it stored them under [sha256]. */
    suspend fun upload(
        server: String,
        bytes: ByteArray,
        sha256: HexKey,
        contentType: String,
        authHeader: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val request =
                    Request
                        .Builder()
                        .url(server.trimEnd('/') + "/upload")
                        .put(bytes.toRequestBody(contentType.toMediaTypeOrNull()))
                        .header("Authorization", authHeader)
                        .header("X-SHA-256", sha256)
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG) { "${hostOf(server)} refused the upload of $sha256: ${response.code}" }
                        return@use false
                    }
                    val said =
                        try {
                            (Json.parseToJsonElement(response.body.string()) as? JsonObject)?.string("sha256")
                        } catch (e: IllegalArgumentException) {
                            null
                        }
                    said == null || said.equals(sha256, ignoreCase = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG) { "Upload of $sha256 to ${hostOf(server)} failed: ${e.javaClass.simpleName}" }
                false
            }
        }

    companion object {
        private const val TAG = "BlossomHttp"

        fun blobUrl(
            server: String,
            sha256: HexKey,
        ) = server.trimEnd('/') + "/" + sha256.lowercase()

        /** `Nostr <base64 of the signed event>`, standard Base64 with padding, as Quartz writes it. */
        fun authHeader(signed: Event): String =
            BlossomAuthorizationEvent.AUTH_HEADER_SCHEME + Base64.getEncoder().encodeToString(signed.toJson().encodeToByteArray())

        /** Only the host of a reader's URL goes in a log line. */
        fun hostOf(url: String): String =
            url
                .substringAfter("://", "")
                .substringBefore('/')
                .substringBefore('?')
                .substringAfterLast('@')
    }
}

/** [key]'s value when it is a JSON string. */
internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
