package com.nosfabrica.observer.press

import com.nosfabrica.observer.nostr.Names
import com.nosfabrica.observer.press.publish.Blossom
import com.nosfabrica.observer.press.publish.Templates
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/editions/anything/publish").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/editions/anything/page").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/editions/current").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/archive").status)
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
    fun `one reader cannot reach another reader's edition`(
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

            // Somebody else's run, and our reader has its id. A run id is a
            // bearer token unless somebody compares the owner, and the page a
            // run is holding has not been published yet.
            val stranger = KeyPair().pubKey.toHexKey()
            val (theirs, _) = instance.runs.open(stranger)

            assertEquals(HttpStatusCode.NotFound, client.get("/api/editions/${theirs.id}") { header("Cookie", cookie) }.status)
            assertEquals(
                HttpStatusCode.NotFound,
                client.post("/api/editions/${theirs.id}/publish") { header("Cookie", cookie) }.status,
            )
            assertEquals(
                HttpStatusCode.NotFound,
                client.get("/api/editions/${theirs.id}/page") { header("Cookie", cookie) }.status,
            )
        }
    }

    @Test
    fun `an edition nobody would store is not lost quietly`(
        @TempDir dir: Path,
    ) = runTest {
        testApplication {
            val instance = app(dir)
            application { routes(instance) }
            val body = """{"signer":"NIP07"}"""
            val cookie =
                client
                    .post("/api/session") {
                        header("Authorization", auth("http://localhost/api/session", "POST", body))
                        setBody(body)
                    }.headers["Set-Cookie"]!!
                    .substringBefore(";")

            val blob = "<main>today's paper</main>".toByteArray()
            val sha = Blossom.sha256(blob)
            val now = System.currentTimeMillis() / 1000
            val (run, _) = instance.runs.open(reader.pubKey.toHexKey())
            run.html = blob
            run.day = "2026-08-18"
            // A server that is not there stands in for a server that says no.
            // Both are "nothing is stored", which is the only distinction this
            // path draws -- and the one that matters, because the manifest must
            // not go out either way.
            run.servers = listOf("http://127.0.0.1:1")
            run.upload = Templates.uploadAuth(sha, blob.size.toLong(), now, now + 600)
            run.manifest = Templates.manifest("2026-08-18", sha, run.servers, "The Nostr Observer", null, now)
            run.state = Runs.State.SIGNING

            fun sign(t: EventTemplate<Event>) = NostrSignerSync(reader).sign<Event>(t.createdAt, t.kind, t.tags, t.content).toJson()
            val signed = """{"upload":${Json.encodeToString(sign(run.upload!!))},"manifest":${Json.encodeToString(sign(run.manifest!!))}}"""

            val response =
                client.post("/api/editions/${run.id}/publish") {
                    header("Cookie", cookie)
                    setBody(signed)
                }
            assertEquals(HttpStatusCode.BadGateway, response.status, response.bodyAsText())
            val said = response.bodyAsText()
            // In words, and with the servers' own answers under it. "no server
            // accepted the upload" is true and tells a reader nothing about
            // whether to wait, pay, or list a different server.
            assertTrue(said.contains("refused this page"), said)
            assertTrue(said.contains("127.0.0.1"), said)
            assertTrue(said.contains("\"recoverable\":true"), said)

            // NOT left waiting to be signed. The authorization is bound to these
            // bytes and expires in ten minutes, so a second attempt asks the
            // reader's signer for two more signatures for a publish that cannot
            // happen -- which is what a poll would have done.
            assertEquals(Runs.State.FAILED, run.state)

            // And the page is still here, which is the only reason the message
            // is allowed to offer saving it.
            val page = client.get("/api/editions/${run.id}/page") { header("Cookie", cookie) }
            assertEquals(HttpStatusCode.OK, page.status)
            assertEquals(String(blob), page.bodyAsText())
            assertTrue(page.headers["Content-Disposition"]!!.contains("attachment"), page.headers.toString())
            // Never as text/html. This origin holds the session cookie, and an
            // edition is markup a model wrote.
            assertFalse(page.headers["Content-Type"]!!.contains("text/html"), page.headers.toString())

            // And a reload finds it again. Telling somebody their page is about
            // to be lost, offering to save it, and then losing the offer to F5
            // would be the same failure wearing a better message.
            val resumed = client.get("/api/editions/current") { header("Cookie", cookie) }
            assertEquals(HttpStatusCode.OK, resumed.status)
            assertTrue(resumed.bodyAsText().contains("\"held\":true"), resumed.bodyAsText())
            assertTrue(resumed.bodyAsText().contains("refused this page"), resumed.bodyAsText())
        }
    }

    @Test
    fun `a reader can only have one removal outstanding`(
        @TempDir dir: Path,
    ) = runTest {
        testApplication {
            val instance = app(dir)
            application { routes(instance) }
            val body = """{"signer":"NIP07"}"""
            val cookie =
                client
                    .post("/api/session") {
                        header("Authorization", auth("http://localhost/api/session", "POST", body))
                        setBody(body)
                    }.headers["Set-Cookie"]!!
                    .substringBefore(";")

            // Any well-formed date is accepted, and the map used to be keyed by
            // reader AND day with nothing to remove an entry that was never
            // signed. A signed-in reader could therefore mint one per day of the
            // calendar, forever, and the note beside it said a sweep would cost
            // more than the leak.
            repeat(40) { i ->
                val day = "20%02d-01-01".format(i)
                assertEquals(
                    HttpStatusCode.OK,
                    client.post("/api/archive/$day/remove") { header("Cookie", cookie) }.status,
                )
            }
            assertEquals(1, instance.removals.size, "one reader, one outstanding removal")

            // And the one that is held is the last one asked for -- asking about
            // an earlier day is answered, not silently signed.
            assertEquals(
                HttpStatusCode.Conflict,
                client.post("/api/archive/2000-01-01/removed") { header("Cookie", cookie) }.status,
            )
        }
    }

    @Test
    fun `a page we no longer hold says so rather than pretending`(
        @TempDir dir: Path,
    ) = runTest {
        testApplication {
            val instance = app(dir)
            application { routes(instance) }
            val body = """{"signer":"NIP07"}"""
            val cookie =
                client
                    .post("/api/session") {
                        header("Authorization", auth("http://localhost/api/session", "POST", body))
                        setBody(body)
                    }.headers["Set-Cookie"]!!
                    .substringBefore(";")

            // A published run has already forgotten its copy, on purpose.
            val (run, _) = instance.runs.open(reader.pubKey.toHexKey())
            run.state = Runs.State.PUBLISHED
            run.forget()

            assertEquals(
                HttpStatusCode.Gone,
                client.get("/api/editions/${run.id}/page") { header("Cookie", cookie) }.status,
            )
        }
    }

    /**
     * The edition that failed its own checks is still the reader's edition.
     *
     * This is the path that cost a real reader $1.54 and told them "3
     * violation(s): 3 quote". Two things were missing and both are asserted
     * here: the status names the passages that could not be verified, and the
     * page it wrote is still offered. Before the fix the run returned before
     * `html` was ever set, so `/page` answered 410 for the one failure where
     * the reader has nothing else to show for their money.
     */
    @Test
    fun `an edition that fails its own checks is readable and says which quotes failed`(
        @TempDir dir: Path,
    ) = runTest {
        testApplication {
            val instance = app(dir)
            application { routes(instance) }
            val body = """{"signer":"NIP07"}"""
            val cookie =
                client
                    .post("/api/session") {
                        header("Authorization", auth("http://localhost/api/session", "POST", body))
                        setBody(body)
                    }.headers["Set-Cookie"]!!
                    .substringBefore(";")

            val blob = "<main>a paper that misquotes somebody</main>".toByteArray()
            val (run, _) = instance.runs.open(reader.pubKey.toHexKey())
            run.html = blob
            run.day = "2026-08-19"
            run.summary =
                Json.encodeToString(
                    Summary(
                        events = 740,
                        voices = 261,
                        control = 400,
                        overlap = 0,
                        bytes = blob.size,
                        costUsd = 1.539405,
                        publishable = false,
                        violations =
                            listOf(
                                Unverified("QUOTE", "not found verbatim in any source event", "the sky was the colour of television"),
                            ),
                    ),
                )
            run.error = "This edition quoted something it could not find in a source event, so it was not published."
            run.state = Runs.State.FAILED

            val status = client.get("/api/editions/${run.id}") { header("Cookie", cookie) }
            assertEquals(HttpStatusCode.OK, status.status)
            val said = status.bodyAsText()
            // The text itself, not a count of how many there were. A reader who
            // can see the sentence can tell whether the writer paraphrased or
            // whether the corpus moved under it; "3 quote" tells them neither.
            assertTrue(said.contains("the sky was the colour of television"), said)
            // And the browser is told there is something left to offer.
            assertTrue(said.contains("\"held\":true"), said)

            // The page it paid for, as an attachment and never as text/html --
            // an unpublishable edition is markup a model wrote that failed its
            // own checks, which is the last thing to render on the origin
            // holding the session cookie.
            val page = client.get("/api/editions/${run.id}/page") { header("Cookie", cookie) }
            assertEquals(HttpStatusCode.OK, page.status)
            assertEquals(String(blob), page.bodyAsText())
            assertTrue(page.headers["Content-Disposition"]!!.contains("attachment"), page.headers.toString())
        }
    }
}
