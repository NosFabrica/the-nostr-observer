package com.nosfabrica.observer.press

import com.nosfabrica.observer.press.publish.Blossom
import com.nosfabrica.observer.press.publish.Templates
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nipB7Blossom.BlossomAuthorizationEvent
import com.vitorpamplona.quartz.nipB7Blossom.BlossomServerUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.time.Instant

/**
 * Will this server take our page, and under what Content-Type?
 *
 * A diagnostic, not a test. It asks one media server the one question that
 * decides whether a reader who lists it can publish at all, and it asks with a
 * real BUD-01 authorization because the answer changes after the auth check —
 * an unauthenticated probe only ever learns "401".
 *
 *     ./gradlew :server:blossomProbe -PliveArgs="<page.html> https://blossom.nostr.build"
 *
 * This exists because an edition is `text/html`, which is the one thing a media
 * host has a reason to refuse: HTML served from their own domain is script
 * running on their own domain. That is a policy question rather than a bug, and
 * a reader whose only server has that policy needs to be told so in words.
 */
fun main(args: Array<String>) {
    val page = File(args.getOrElse(0) { "edition.html" })
    val server = args.getOrElse(1) { "https://blossom.nostr.build" }.trimEnd('/')
    require(page.isFile) { "no page at ${page.path}" }
    val blob = page.readBytes()

    val keys = KeyPair()
    val signer = NostrSignerSync(keys)
    println("throwaway ${keys.pubKey.toHexKey().take(12)}… asking $server about ${page.name} (${blob.size} bytes)\n")

    val now = Instant.now().epochSecond
    val template = Templates.uploadAuth(Blossom.sha256(blob), blob.size.toLong(), now, now + 600)
    val auth: Event = signer.sign(template.createdAt, template.kind, template.tags, template.content)
    val header =
        BlossomAuthorizationEvent(auth.id, auth.pubKey, auth.createdAt, auth.tags, auth.content, auth.sig)
            .toAuthorizationHeader()

    val http = OkHttpClient()
    listOf("text/html", "application/octet-stream", "text/plain", "application/xhtml+xml", null).forEach { type ->
        val body = blob.toRequestBody(type?.toMediaType())
        val request =
            Request
                .Builder()
                .url(BlossomServerUrl.upload(server))
                .put(body)
                .header("Authorization", header)
                .build()
        val answer =
            runCatching {
                http.newCall(request).execute().use { response ->
                    val reason = response.header(BlossomServerUrl.REASON_HEADER)
                    "${response.code} ${reason ?: response.body?.string()?.take(160)}"
                }
            }.getOrElse { it.message ?: "failed" }
        println("%-24s -> %s".format(type ?: "(none)", answer))
    }
}
