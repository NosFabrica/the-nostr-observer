package com.nosfabrica.observer.press.publish

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nipB7Blossom.BlossomAuthorizationEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat

/**
 * Putting the edition on the reader's own servers.
 *
 * The blob is one small HTML file with its art referenced at the URLs its
 * authors published, so an edition is tens of kilobytes rather than megabytes
 * and there is no image pipeline anywhere in this project.
 */
class Blossom(
    private val http: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .callTimeout(Duration.ofSeconds(60))
            .build(),
) {
    data class Upload(
        val server: String,
        val ok: Boolean,
        val detail: String,
    )

    /**
     * Every server in their list, and the result of each one honestly.
     *
     * Not "first success wins". A reader with three servers listed expects
     * their paper on three servers, and the one that silently failed is the one
     * that matters the day the other two go away. Partial success is still a
     * publish, so the caller decides what to do rather than being handed a
     * boolean that has thrown the detail away.
     */
    suspend fun upload(
        servers: List<String>,
        blob: ByteArray,
        auth: Event,
    ): List<Upload> =
        // At once. These are independent hosts and one of them being slow is not
        // a reason for the reader to wait on it before the next one is even
        // asked; three servers at the 60s call timeout was three minutes in
        // series. Order is preserved so the report lines up with the list.
        coroutineScope {
            servers
                .map { server -> async(Dispatchers.IO) { put(server, blob, auth) } }
                .awaitAll()
        }

    private fun put(
        server: String,
        blob: ByteArray,
        auth: Event,
    ): Upload {
        val base = server.trimEnd('/')
        val header =
            BlossomAuthorizationEvent(
                auth.id,
                auth.pubKey,
                auth.createdAt,
                auth.tags,
                auth.content,
                auth.sig,
            ).toAuthorizationHeader()

        val request =
            Request
                .Builder()
                .url("$base/upload")
                .put(blob.toRequestBody("text/html".toMediaType()))
                .header("Authorization", header)
                .build()

        return runCatching {
            http.newCall(request).execute().use { response ->
                Upload(
                    server = server,
                    ok = response.isSuccessful,
                    // A Blossom server that refuses says why in the body, and that
                    // sentence is the only thing that will tell a reader whether
                    // they need a different server or a bigger plan.
                    detail =
                        if (response.isSuccessful) {
                            "HTTP ${response.code}"
                        } else {
                            "HTTP ${response.code}: ${response.body?.string().orEmpty().take(200).ifBlank { "no reason given" }}"
                        },
                )
            }
        }.getOrElse { Upload(server, false, it.message ?: it::class.simpleName ?: "failed") }
    }

    companion object {
        fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
