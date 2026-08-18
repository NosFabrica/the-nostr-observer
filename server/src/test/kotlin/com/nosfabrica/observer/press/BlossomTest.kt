package com.nosfabrica.observer.press

import com.nosfabrica.observer.press.publish.Blossom
import com.nosfabrica.observer.press.publish.Templates
import com.sun.net.httpserver.HttpServer
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Instant
import java.util.Base64

/**
 * The upload, against something that actually answers.
 *
 * THIS PATH HAD NEVER RUN. `Templates` was tested, `Countersign` was tested,
 * and between them sat the one piece that talks to a machine outside this
 * program — and it had no test and had never been executed against a server.
 * "Built" meant the code existed.
 *
 * So there is a Blossom server here: a real socket, a real `PUT /upload`, and
 * an authorization check strict enough to be worth passing. It verifies what
 * BUD-01 says a server verifies — the kind, that this is an upload, that the
 * `x` tag is the SHA-256 of the bytes that actually arrived, and that the
 * authorization has not expired. If our header is malformed in any of those
 * ways, this fails, which is the entire point.
 *
 * The signature itself is not checked here. That is one line in a real server
 * and it would test quartz rather than us; what is ours is the header's shape
 * and the binding between the authorization and the blob.
 */
class BlossomTest {
    private val reader = KeyPair()

    /** What arrived, so a test can assert on the request rather than the reply. */
    private class Seen {
        var authorization: String? = null
        var body: ByteArray? = null
        var contentType: String? = null
        var refusal: String? = null
    }

    private fun serve(
        seen: Seen,
        answer: (Seen) -> Pair<Int, String>,
    ): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/upload") { exchange ->
                seen.authorization = exchange.requestHeaders.getFirst("Authorization")
                seen.contentType = exchange.requestHeaders.getFirst("Content-Type")
                seen.body = exchange.requestBody.readBytes()
                val (code, text) = answer(seen)
                exchange.sendResponseHeaders(code, text.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(text.toByteArray()) }
            }
            start()
        }

    private fun url(server: HttpServer) = "http://127.0.0.1:${server.address.port}"

    /** BUD-01's checks, as a server would make them. */
    private fun accept(seen: Seen): Pair<Int, String> {
        val header = seen.authorization ?: return 401 to "no authorization".also { seen.refusal = it }
        if (!header.startsWith("Nostr ")) return 401 to "wrong scheme".also { seen.refusal = it }
        val event =
            runCatching { Event.fromJson(String(Base64.getDecoder().decode(header.removePrefix("Nostr ")))) }
                .getOrNull() ?: return 401 to "not an event".also { seen.refusal = it }

        fun tag(name: String) = event.tags.firstOrNull { it.size > 1 && it[0] == name }?.get(1)
        if (event.kind != 24242) return 401 to "wrong kind ${event.kind}".also { seen.refusal = it }
        if (tag("t") != "upload") return 401 to "not an upload".also { seen.refusal = it }
        val expires = tag("expiration")?.toLongOrNull() ?: return 401 to "no expiration".also { seen.refusal = it }
        if (expires <= Instant.now().epochSecond) return 401 to "expired".also { seen.refusal = it }
        // The binding that makes a leaked authorization useless: it names one
        // blob, and this is the blob that arrived.
        if (tag("x") != Blossom.sha256(seen.body!!)) return 401 to "x does not match the body".also { seen.refusal = it }
        return 200 to """{"sha256":"${tag("x")}","size":${seen.body!!.size},"type":"text/html"}"""
    }

    private fun authFor(blob: ByteArray): Event {
        val now = Instant.now().epochSecond
        val template = Templates.uploadAuth(Blossom.sha256(blob), blob.size.toLong(), now, now + 600)
        return NostrSignerSync(reader).sign(template.createdAt, template.kind, template.tags, template.content)
    }

    @Test
    fun `a real server accepts what we send it`() =
        runTest {
            val seen = Seen()
            val server = serve(seen, ::accept)
            try {
                val blob = "<main>today's paper</main>".toByteArray()
                val results = Blossom().upload(listOf(url(server)), blob, authFor(blob))

                assertEquals(1, results.size)
                assertTrue(results.single().ok, "server said: ${results.single().detail} / ${seen.refusal}")
                assertEquals("text/html", seen.contentType?.substringBefore(";")?.trim())
                assertTrue(seen.authorization!!.startsWith("Nostr "), seen.authorization!!)
                assertArrayEqualsBytes(blob, seen.body!!)
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `an authorization for different bytes is refused`() =
        runTest {
            // The `x` tag is the only thing between "authorize this page" and
            // "authorize whatever turns up". If our client ever sent the auth for
            // one blob with another blob's body, a correct server would refuse and
            // we would want to have known.
            val seen = Seen()
            val server = serve(seen, ::accept)
            try {
                val signed = authFor("one page".toByteArray())
                val results = Blossom().upload(listOf(url(server)), "a different page".toByteArray(), signed)
                assertFalse(results.single().ok)
                assertEquals("x does not match the body", seen.refusal)
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `every server is reported, not just the first`() =
        runTest {
            // A reader with three servers expects their paper on three servers, and
            // the one that silently failed is the one that matters the day the
            // other two go away.
            val good = Seen()
            val bad = Seen()
            val ok = serve(good, ::accept)
            val broken = serve(bad) { 507 to "Insufficient Storage" }
            try {
                val blob = "<main>today</main>".toByteArray()
                val results = Blossom().upload(listOf(url(ok), url(broken)), blob, authFor(blob))

                assertEquals(2, results.size, "order is preserved so the report lines up with the list")
                assertTrue(results[0].ok)
                assertFalse(results[1].ok)
                // The server's own words, because "it failed" does not tell a reader
                // whether they need a different server or a bigger plan.
                assertTrue(results[1].detail.contains("507"), results[1].detail)
                assertTrue(results[1].detail.contains("Insufficient Storage"), results[1].detail)
            } finally {
                ok.stop(0)
                broken.stop(0)
            }
        }

    @Test
    fun `a server that is not there is a failure, not an exception`() =
        runTest {
            // One dead host must not take the publish down with it: the others may
            // still have accepted, and the caller decides what partial success means.
            val results = Blossom().upload(listOf("http://127.0.0.1:1"), "x".toByteArray(), authFor("x".toByteArray()))
            assertFalse(results.single().ok)
            assertTrue(results.single().detail.isNotBlank())
        }

    private fun assertArrayEqualsBytes(
        expected: ByteArray,
        actual: ByteArray,
    ) = assertEquals(String(expected), String(actual))
}
