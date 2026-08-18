package com.nosfabrica.observer.press

import com.nosfabrica.observer.nostr.Names
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat

/**
 * The front door.
 *
 * Everything here is about who is allowed to ask, not about what an edition
 * says. The generation pipeline has its own tests next door and needs a relay
 * and a model; these need neither, which is the point -- an access-control test
 * that only runs when the network is up is an access-control test that stops
 * running.
 */
class RoutesTest {
    private val reader = KeyPair()

    private fun app(dir: Path) =
        App(
            Config(
                database = dir.resolve("routes.db").toString(),
                // The test client speaks http, and a Secure cookie would never
                // come back. Saying so here is the same as saying it in a
                // deployment that terminates TLS in front.
                insecureCookies = true,
                // What the test client actually calls us. Sign-in compares
                // against this and not against anything in the request.
                publicUrl = "http://localhost",
            ),
        )

    /** A NIP-98 header over exactly this request, the way the browser builds one. */
    private fun auth(
        url: String,
        method: String,
        body: String,
    ): String {
        val payload = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.toByteArray()))
        val event =
            NostrSignerSync(reader).sign<Event>(
                System.currentTimeMillis() / 1000,
                27235,
                arrayOf(arrayOf("u", url), arrayOf("method", method), arrayOf("payload", payload)),
                "",
            )
        return "Nostr " + Base64.getEncoder().encodeToString(event.toJson().toByteArray())
    }

    @Test
    fun `every route that does anything needs a session`(
        @TempDir dir: Path,
    ) = runTest {
        testApplication {
            application { routes(app(dir)) }
            // Not a sample: this is the whole list of routes that read or write
            // a reader's data, and each one has to refuse on its own.
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/session").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/readiness").status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/editions").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/editions/anything").status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/editions/anything/prepare").status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/editions/anything/publish").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/draft/anything").status)
        }
    }

    @Test
    fun `a NIP-98 signature signs the reader in`(
        @TempDir dir: Path,
    ) = runTest {
        testApplication {
            application { routes(app(dir)) }
            val body = """{"signer":"NIP07"}"""
            val response =
                client.post("/api/session") {
                    header("Authorization", auth("http://localhost/api/session", "POST", body))
                    setBody(body)
                }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            // The reader is named by npub, and the hex never leaves the server.
            // This used to assert the opposite -- that the response CONTAINED
            // the hex key -- which is the thing being fixed, so the test failed
            // the moment the fix landed. That is the only reason to trust it.
            val said = response.bodyAsText()
            assertTrue(said.contains(Names.short(reader.pubKey.toHexKey())), said)
            assertFalse(said.contains(reader.pubKey.toHexKey()), "no hex on the wire: $said")
        }
    }

    @Test
    fun `an unsigned request is not a sign-in`(
        @TempDir dir: Path,
    ) = runTest {
        testApplication {
            application { routes(app(dir)) }
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/session") { setBody("{}") }.status)
        }
    }

    @Test
    fun `a signature over a different url does not sign anyone in`(
        @TempDir dir: Path,
    ) = runTest {
        testApplication {
            application { routes(app(dir)) }
            // A NIP-98 event captured from any other service would otherwise be
            // a valid sign-in here. The `u` tag is what stops that, and it only
            // stops it if somebody compares it.
            val body = """{"signer":"NIP07"}"""
            val response =
                client.post("/api/session") {
                    header("Authorization", auth("https://someone-else.example.com/login", "POST", body))
                    setBody(body)
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `a forwarded host header cannot move the goalposts`(
        @TempDir dir: Path,
    ) = runTest {
        testApplication {
            application { routes(app(dir)) }
            // The attack: any site can ask a visitor to sign a NIP-98 event for
            // a URL that site controls. If we reconstruct our own identity from
            // request headers, evil.example.com signs the victim in HERE by
            // replaying that event with a matching X-Forwarded-Host.
            val body = """{"signer":"NIP07"}"""
            val response =
                client.post("/api/session") {
                    header("Authorization", auth("https://evil.example.com/api/session", "POST", body))
                    header("X-Forwarded-Host", "evil.example.com")
                    header("X-Forwarded-Proto", "https")
                    setBody(body)
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status, response.bodyAsText())
        }
    }

    @Test
    fun `a host header cannot move them either`(
        @TempDir dir: Path,
    ) = runTest {
        testApplication {
            application { routes(app(dir)) }
            val body = """{"signer":"NIP07"}"""
            val response =
                client.post("/api/session") {
                    header("Authorization", auth("http://evil.example.com/api/session", "POST", body))
                    header("Host", "evil.example.com")
                    setBody(body)
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status, response.bodyAsText())
        }
    }

    @Test
    fun `the console is served without a session`(
        @TempDir dir: Path,
    ) = runTest {
        testApplication {
            application { routes(app(dir)) }
            val page = client.get("/")
            assertEquals(HttpStatusCode.OK, page.status)
            assertTrue(page.bodyAsText().contains("The Nostr Observer"))
        }
    }

    @Test
    fun `a preview cannot run scripts`(
        @TempDir dir: Path,
    ) = runTest {
        testApplication {
            val instance = app(dir)
            application { routes(instance) }

            val body = """{"signer":"NIP07"}"""
            val signIn =
                client.post("/api/session") {
                    header("Authorization", auth("http://localhost/api/session", "POST", body))
                    setBody(body)
                }
            val cookie = signIn.headers["Set-Cookie"]!!.substringBefore(";")

            val id = instance.drafts.open(reader.pubKey.toHexKey())
            instance.drafts.ready(id, "<main>today</main>", "a".repeat(64), "{}", "[]")

            val preview = client.get("/draft/$id") { header("Cookie", cookie) }
            assertEquals(HttpStatusCode.OK, preview.status)
            assertTrue(preview.bodyAsText().contains("today"))

            // The page is already sanitized. This is the second lock: a script
            // that got through would still not run, and it would not run beside
            // a session cookie on our own origin.
            val csp = preview.headers["Content-Security-Policy"]!!
            assertTrue(csp.contains("default-src 'none'"))
            assertFalse(csp.contains("script-src"), "no script source may be allowed at all")
            assertEquals("nosniff", preview.headers["X-Content-Type-Options"])
        }
    }

    @Test
    fun `one reader cannot preview another reader's draft`(
        @TempDir dir: Path,
    ) = runTest {
        testApplication {
            val instance = app(dir)
            application { routes(instance) }

            val body = """{"signer":"NIP07"}"""
            val signIn =
                client.post("/api/session") {
                    header("Authorization", auth("http://localhost/api/session", "POST", body))
                    setBody(body)
                }
            val cookie = signIn.headers["Set-Cookie"]!!.substringBefore(";")

            // Somebody else's finished edition, and our reader has its id.
            val stranger = KeyPair().pubKey.toHexKey()
            val id = instance.drafts.open(stranger)
            instance.drafts.ready(id, "<main>not yours</main>", "b".repeat(64), "{}", "[]")

            assertEquals(HttpStatusCode.NotFound, client.get("/draft/$id") { header("Cookie", cookie) }.status)
            assertEquals(HttpStatusCode.NotFound, client.get("/api/editions/$id") { header("Cookie", cookie) }.status)
        }
    }
}
