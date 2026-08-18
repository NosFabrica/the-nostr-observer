package com.nosfabrica.observer.press.publish

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nipB7Blossom.BlossomAuthorizationEvent
import com.vitorpamplona.quartz.nipB7Blossom.BlossomPaymentRequired
import com.vitorpamplona.quartz.nipB7Blossom.BlossomServerUrl
import com.vitorpamplona.quartz.nipB7Blossom.BlossomUploadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
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
        /**
         * Where the server says the blob is, from its own descriptor.
         *
         * NOT `server + "/" + sha`, which is what this assembled before and is
         * a guess: BUD-02 has the server answer with the URL, and it is free to
         * serve from a CDN domain, a path prefix, or with an extension on the
         * end. Guessing produces a link that 404s for a reader while the upload
         * itself was perfectly fine.
         */
        val url: String? = null,
        /** What the server says it stored. Compared against what we sent. */
        val sha256: String? = null,
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

    /** Servers send fields we do not model, and a new one must not fail an upload. */
    private val json = Json { ignoreUnknownKeys = true }

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
                .url(BlossomServerUrl.upload(base))
                .put(blob.toRequestBody("text/html".toMediaType()))
                .header("Authorization", header)
                .build()

        return runCatching {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    // WHY IT REFUSED, from where a Blossom server actually puts
                    // it. This read the body only, and said in a comment that
                    // the server's own sentence is the thing that tells a reader
                    // whether they need a different server or a bigger plan --
                    // while a refusing server puts that sentence in the
                    // `X-Reason` HEADER and is entitled to send an empty body.
                    // The promise was in the comment and not in the code.
                    val reason = response.header(BlossomServerUrl.REASON_HEADER)?.takeIf { it.isNotBlank() }

                    // BUD-07: a paid server answers 402 with its terms in the
                    // headers. "HTTP 402" tells a reader nothing they can act
                    // on; "this server wants paying" tells them to use another.
                    if (response.code == 402) {
                        val wants = BlossomPaymentRequired.fromHeaders { response.header(it) }
                        return@use Upload(
                            server = server,
                            ok = false,
                            detail =
                                "wants payment before it will store anything" +
                                    (wants.sanitizedReason(120)?.let { ": $it" } ?: reason?.let { ": $it" } ?: ""),
                        )
                    }

                    return@use Upload(
                        server = server,
                        ok = false,
                        detail = "HTTP ${response.code}: ${reason ?: body.take(200).ifBlank { "no reason given" }}",
                    )
                }

                // The descriptor, parsed rather than assumed. A 200 with a
                // sha256 that is not the one we sent means the server stored
                // something else, and a manifest pointing at OUR hash would be
                // a signed link to a 404.
                val stored = runCatching { json.decodeFromString<BlossomUploadResult>(body) }.getOrNull()
                val said = stored?.sha256
                val expected = auth.tags.firstOrNull { it.size > 1 && it[0] == "x" }?.get(1)
                if (said != null && expected != null && !said.equals(expected, ignoreCase = true)) {
                    return@use Upload(
                        server = server,
                        ok = false,
                        detail = "stored a different blob: it says ${said.take(12)}…, we sent ${expected.take(12)}…",
                    )
                }
                Upload(
                    server = server,
                    ok = true,
                    detail = "HTTP ${response.code}",
                    url = stored?.url,
                    sha256 = stored?.sha256,
                )
            }
        }.getOrElse { Upload(server, false, it.message ?: it::class.simpleName ?: "failed") }
    }

    companion object {
        fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
