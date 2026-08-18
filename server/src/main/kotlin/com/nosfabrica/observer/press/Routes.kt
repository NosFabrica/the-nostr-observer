package com.nosfabrica.observer.press

import com.nosfabrica.observer.WINDOW_SECONDS
import com.nosfabrica.observer.nostr.Names
import com.nosfabrica.observer.nostr.Readiness
import com.nosfabrica.observer.press.auth.Sessions
import com.nosfabrica.observer.press.auth.SignIn
import com.nosfabrica.observer.press.publish.Countersign
import com.nosfabrica.observer.press.publish.Pendings
import com.nosfabrica.observer.press.publish.Templates
import com.nosfabrica.observer.press.store.Drafts
import com.vitorpamplona.quartz.nip01Core.core.Event
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private const val COOKIE = "observer_session"
private val json = Json { ignoreUnknownKeys = true }

/**
 * Who is signed in, said the way a person is said.
 *
 * No hex leaves here — the console has nothing else to show, so anything this
 * omits becomes a key on somebody's screen.
 *
 * [name] is their `npub`, and deliberately not their `kind 0` name: resolving
 * that means a relay round trip, and sign-in is not the place for one. It made
 * signing in wait on the network, and it made the access-control tests next
 * door wait on it too — which is how an access-control test stops running. The
 * name arrives with the readiness check a moment later, and the console
 * upgrades the label then.
 */
@Serializable
private data class Who(
    val name: String,
    val signer: String,
)

@Serializable
private data class Started(
    val draft: String,
)

/**
 * What the browser knows and the server does not.
 *
 * The published page carries no script, so it cannot read a viewer's clock;
 * the reader's timezone has to be baked in when the edition is written, and
 * this request is the only moment anything in the system is in a position to
 * observe it. Optional, and UTC without it.
 */
@Serializable
private data class Wanted(
    val timezone: String? = null,
)

@Serializable
private data class Status(
    val state: String,
    val progress: String,
    val summary: String? = null,
    val error: String? = null,
    val sha256: String? = null,
)

@Serializable
private data class ChainLink(
    val key: String,
    val status: String,
    val detail: String? = null,
)

@Serializable
private data class Verdict(
    val state: String,
    val explanation: String,
    val ranks: Boolean,
    val chain: List<ChainLink>,
)

/** Both chains, because they fail independently and a reader should see which. */
@Serializable
private data class Preflight(
    /** Their `kind 0` name, resolved here because this call is already on the network. */
    val name: String,
    val lens: Verdict,
    val storage: Verdict,
)

@Serializable
private data class ToSign(
    val upload: String,
    val manifest: String,
    val servers: List<String>,
    val relays: List<String>,
    val sha256: String,
)

@Serializable
private data class Signed(
    val upload: String,
    val manifest: String,
)

@Serializable
private data class PublishReport(
    val ok: Boolean,
    val day: String,
    val naddr: String,
    val uploads: List<Outcome>,
    val relays: List<Outcome>,
)

@Serializable
private data class Outcome(
    val target: String,
    val ok: Boolean,
    val detail: String,
)

@Serializable
private data class Problem(
    val error: String,
)

fun Application.routes(app: App) {
    install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            // The reader gets a sentence, the log gets the trace. An HTML error
            // page from a JSON endpoint is the version that wastes an afternoon.
            this@routes.log.error("unhandled", cause)
            call.respond(HttpStatusCode.InternalServerError, Problem(cause.message ?: "something broke"))
        }
    }

    routing {
        // The console. Three files, no build step: this page's whole job is to
        // sign in, poll a job and hand two events to a signer.
        staticResources("/", "web") { default("index.html") }

        // Sign in by signing this request.
        //
        // Identical for a browser extension and a remote signer: both produce a
        // NIP-98 event over this URL and method. The awkward part of NIP-46
        // stays in the browser where the transport is, instead of becoming a
        // second sign-in protocol here.
        post("/api/session") {
            val body = call.receiveText()
            val signer =
                runCatching { json.decodeFromString<JsonObject>(body)["signer"]?.jsonPrimitive?.content }
                    .getOrNull()
                    ?.uppercase()
            when (
                val result =
                    app.signIn.verify(
                        header = call.request.headers["Authorization"],
                        url = call.fullUrl(app),
                        method = "POST",
                        body = body.toByteArray(),
                    )
            ) {
                is SignIn.Result.No -> {
                    call.respond(HttpStatusCode.Unauthorized, Problem(result.reason))
                }

                is SignIn.Result.Ok -> {
                    val kind = if (signer == "NIP46") Sessions.Signer.NIP46 else Sessions.Signer.NIP07
                    val token = app.sessions.open(result.pubkey, kind)
                    call.response.cookies.append(sessionCookie(app, token))
                    call.respond(Who(Names.short(result.pubkey), kind.name))
                }
            }
        }

        // Sign in by connecting a remote signer.
        //
        // The reader pastes a `bunker://` address; we connect, ask the signer
        // who it speaks for, and open the session for that pubkey. There is no
        // signature to verify here because the connection IS the proof: only
        // the reader's signer holds the secret in that URI.
        post("/api/session/bunker") {
            val uri =
                runCatching { json.decodeFromString<JsonObject>(call.receiveText())["uri"]?.jsonPrimitive?.content }
                    .getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, Problem("no bunker:// address"))

            app.bunkers
                .connect(uri)
                .onSuccess { connected ->
                    val token = app.sessions.open(connected.pubkey, Sessions.Signer.NIP46)
                    app.bunkers.adopt(token, connected)
                    call.response.cookies.append(sessionCookie(app, token))
                    call.respond(Who(Names.short(connected.pubkey), Sessions.Signer.NIP46.name))
                }.onFailure {
                    call.respond(HttpStatusCode.BadGateway, Problem(it.message ?: "could not reach that signer"))
                }
        }

        get("/api/session") {
            val session = signedIn(app) ?: return@get call.respond(HttpStatusCode.Unauthorized, Problem("not signed in"))
            call.respond(Who(Names.short(session.pubkey), session.signer))
        }

        post("/api/session/end") {
            // The bunker connection first: a session that is gone can no longer
            // name the signer it left connected.
            call.request.cookies[COOKIE]?.let(app.bunkers::close)
            app.sessions.close(call.request.cookies[COOKIE])
            call.respond(HttpStatusCode.OK, Problem("signed out"))
        }

        // The readiness chain, for the panel that explains why there is no lens yet.
        get("/api/readiness") {
            val session = signedIn(app) ?: return@get call.respond(HttpStatusCode.Unauthorized, Problem("not signed in"))
            val (facts, lens) = app.press.readiness(session.pubkey, Instant.now().epochSecond - WINDOW_SECONDS)
            // Asked here, before an edition exists. Learning you have nowhere to
            // publish AFTER paying for a paper is the failure the second chain
            // is for, and that is exactly where the check used to sit.
            val store =
                app.press.storage(
                    session.pubkey,
                    facts.writeRelays.orEmpty(),
                    publishedBefore = app.published.of(session.pubkey).isNotEmpty(),
                )
            call.respond(
                Preflight(
                    // Their own profile lives on their own relays, which this
                    // call has just learned, so it costs one more filter on
                    // hosts we are already talking to.
                    name = app.press.nameOf(session.pubkey, facts.writeRelays.orEmpty()),
                    lens =
                        Verdict(
                            state = lens.state,
                            explanation = Readiness.explain(lens),
                            ranks = lens.ranks,
                            chain = lens.chain.map { ChainLink(it.key, it.status.name, it.detail) },
                        ),
                    storage =
                        Verdict(
                            state = store.state,
                            explanation = Readiness.explainStorage(store),
                            ranks = store.state == "can-publish",
                            chain = store.chain.map { ChainLink(it.key, it.status.name, it.detail) },
                        ),
                ),
            )
        }

        post("/api/editions") {
            val session = signedIn(app) ?: return@post call.respond(HttpStatusCode.Unauthorized, Problem("not signed in"))
            // A missing or unparseable body is a request with no preference,
            // not a bad request: the timezone is the only thing in it.
            val wanted = runCatching { Json.decodeFromString<Wanted>(call.receiveText()) }.getOrNull()
            call.respond(Started(app.editions.start(session.pubkey, wanted?.timezone)))
        }

        get("/api/editions/{id}") {
            val session = signedIn(app) ?: return@get call.respond(HttpStatusCode.Unauthorized, Problem("not signed in"))
            val draft = draftOf(app, session.pubkey) ?: return@get call.respond(HttpStatusCode.NotFound, Problem("no such draft"))
            call.respond(
                Status(
                    state = draft.state.name,
                    progress = draft.progress,
                    summary = draft.summary,
                    error = draft.error,
                    sha256 = draft.sha256,
                ),
            )
        }

        // The private preview.
        //
        // Served from our own origin, to the owner only, before anything is
        // signed. Generate-then-publish: nobody should have to publish a page to
        // find out what it says.
        get("/draft/{id}") {
            val session = signedIn(app) ?: return@get call.respondText("Sign in first.", status = HttpStatusCode.Unauthorized)
            val draft =
                draftOf(app, session.pubkey)
                    ?: return@get call.respondText("No such draft.", status = HttpStatusCode.NotFound)
            val html = draft.html ?: return@get call.respondText("Not finished yet.", status = HttpStatusCode.Accepted)
            // The page is sanitized, and it is still not trusted enough to run
            // beside a session cookie. These two headers are what make an
            // unexpected script tag inert rather than merely unlikely.
            call.response.headers.append("Content-Security-Policy", CSP)
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            call.respondText(html, ContentType.Text.Html)
        }

        // What the reader is about to sign, built here so it can be checked here.
        //
        // Two events, and the reader will see two prompts. The manifest carries
        // every day they have ever published, not just today: `kind 35128` is
        // replaceable, so a manifest with one path is a manifest that deleted
        // the archive.
        post("/api/editions/{id}/prepare") {
            val session = signedIn(app) ?: return@post call.respond(HttpStatusCode.Unauthorized, Problem("not signed in"))
            val draft =
                draftOf(app, session.pubkey)
                    ?: return@post call.respond(HttpStatusCode.NotFound, Problem("no such draft"))
            val html = draft.html ?: return@post call.respond(HttpStatusCode.Conflict, Problem("that edition is not finished"))
            val summary = draft.summary?.let { runCatching { json.decodeFromString<Summary>(it) }.getOrNull() }
            if (summary?.publishable == false) {
                return@post call.respond(
                    HttpStatusCode.Conflict,
                    Problem("this edition failed its own checks and is not offered for publication"),
                )
            }

            // Their relays, from their own kind 10002, asked again rather than
            // remembered: this is where the manifest is going, and a stale copy
            // publishes the paper to an address they have moved away from. One
            // fetch, not the whole readiness chain -- which is what this did,
            // spending two NIP-50 searches and four COUNTs on a shared relay to
            // read a single event.
            val writeRelays = app.press.writeRelaysOf(session.pubkey)
            val servers = app.announce.servers(session.pubkey, writeRelays)
            if (servers.isEmpty()) {
                return@post call.respond(
                    HttpStatusCode.Conflict,
                    Problem(
                        "You have not set up anywhere to store files. Your paper is published to your own " +
                            "storage, so there is nowhere to put it yet — add one in your usual Nostr app.",
                    ),
                )
            }

            val blob = html.toByteArray()
            val sha = draft.sha256 ?: return@post call.respond(HttpStatusCode.Conflict, Problem("draft has no hash"))
            val now = Instant.now().epochSecond
            val day = DAY.format(Instant.ofEpochSecond(now).atOffset(ZoneOffset.UTC))
            // The archive, from THEIR copy as well as ours.
            //
            // A kind 35128 replaces, so the manifest must carry every day the
            // reader has ever published. Rebuilding it from our index alone made
            // our database the sole record of their archive: lose it, or move
            // them to another deployment, and the next publish silently deletes
            // every earlier edition. Their own site event is the durable copy,
            // so it is merged in and ours only fills the gaps.
            val theirs =
                app.announce
                    .existing(session.pubkey, writeRelays)
                    ?.paths()
                    ?.map { it.path to it.hash }
                    .orEmpty()
            val paths =
                (listOf("/index.html" to sha, "/$day" to sha) + app.published.paths(session.pubkey) + theirs)
                    .distinctBy { it.first }

            val upload = Templates.uploadAuth(sha, blob.size.toLong(), now, now + 600)
            val manifest =
                Templates.manifest(paths, servers, app.continuities.of(session.pubkey).masthead, now)
            app.pending[draft.id] = Pendings.Pending(upload, manifest, servers, writeRelays, sha, day)
            call.respond(ToSign(upload.toJson(), manifest.toJson(), servers, writeRelays, sha))
        }

        post("/api/editions/{id}/publish") {
            val session = signedIn(app) ?: return@post call.respond(HttpStatusCode.Unauthorized, Problem("not signed in"))
            val draft =
                draftOf(app, session.pubkey)
                    ?: return@post call.respond(HttpStatusCode.NotFound, Problem("no such draft"))
            val pending =
                app.pending[draft.id]
                    ?: return@post call.respond(HttpStatusCode.Conflict, Problem("nothing prepared for this edition"))
            // Two signers, one publish. An extension signs in the page and posts
            // the results back; a remote signer is connected to THIS process, so
            // the two prompts go to the reader's phone from here. Everything
            // after this point is identical, deliberately: the checks below do
            // not care where a signature came from, and must not.
            val body = call.receiveText()
            val offered = runCatching { json.decodeFromString<Signed>(body) }.getOrNull()
            val token = call.request.cookies[COOKIE]
            val auth: Event?
            val manifest: Event?
            if (offered != null && offered.upload.isNotBlank()) {
                auth = Event.fromJsonOrNull(offered.upload)
                manifest = Event.fromJsonOrNull(offered.manifest)
            } else if (token != null && app.bunkers.has(token)) {
                val signedUpload = app.bunkers.sign(token, pending.upload)
                val signedManifest = app.bunkers.sign(token, pending.manifest)
                val failure = listOf(signedUpload, signedManifest).firstNotNullOfOrNull { it.exceptionOrNull() }
                if (failure != null) {
                    return@post call.respond(
                        HttpStatusCode.GatewayTimeout,
                        Problem(
                            "your signer did not answer: ${failure.message ?: "timed out"}" +
                                (app.bunkers.authUrl(token)?.let { " (it may be asking you to visit $it)" } ?: ""),
                        ),
                    )
                }
                auth = signedUpload.getOrNull()
                manifest = signedManifest.getOrNull()
            } else {
                return@post call.respond(HttpStatusCode.BadRequest, Problem("no signatures, and no signer connected"))
            }
            if (auth == null || manifest == null) {
                return@post call.respond(HttpStatusCode.BadRequest, Problem("that is not a signed event"))
            }
            listOf(
                Countersign.check(auth, pending.upload, session.pubkey),
                Countersign.check(manifest, pending.manifest, session.pubkey),
            ).filterIsInstance<Countersign.Result.No>().firstOrNull()?.let {
                return@post call.respond(HttpStatusCode.BadRequest, Problem("signature rejected: ${it.reason}"))
            }

            val html = draft.html ?: return@post call.respond(HttpStatusCode.Conflict, Problem("draft has no page"))
            val uploads = app.blossom.upload(pending.servers, html.toByteArray(), auth)
            if (uploads.none { it.ok }) {
                return@post call.respond(
                    HttpStatusCode.BadGateway,
                    Problem("no server accepted the upload: " + uploads.joinToString("; ") { "${it.server} ${it.detail}" }),
                )
            }

            // Only after a server actually holds the blob. A manifest pointing at
            // a hash nobody stores is a 404 with a signature on it.
            val announced = app.announce.publish(manifest, pending.relays)
            // Stored as the `a`-tag coordinate, which is what it is; shown as
            // `naddr1…`, which is what a person can paste. Neither is hex on a
            // screen.
            val naddr = "35128:${session.pubkey}:${Templates.SITE}"
            if (announced.any { it.ok }) {
                app.published.record(session.pubkey, pending.day, pending.sha, naddr, pending.servers)
            }
            app.pending.remove(draft.id)

            call.respond(
                PublishReport(
                    ok = announced.any { it.ok },
                    day = pending.day,
                    naddr = Templates.address(session.pubkey) ?: naddr,
                    uploads = uploads.map { Outcome(it.server, it.ok, it.detail) },
                    relays = announced.map { Outcome(it.relay, it.ok, it.message) },
                ),
            )
        }

        // No archive endpoint. There was one, and nothing called it: the console
        // never asked, so it was an untested route answering in a borrowed type
        // -- `Outcome` is a publish RESULT, and it was carrying archive rows with
        // the day and the address crushed into one string and `ok` hardcoded
        // true. `Published.of` still holds the data, and the day the console
        // grows an archive view, the endpoint can be written to fit it.
    }
}

private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")

private fun sessionCookie(
    app: App,
    token: String,
) = Cookie(
    name = COOKIE,
    value = token,
    httpOnly = true,
    // Defaults to demanding HTTPS; a deployment that terminates TLS elsewhere
    // has to say so out loud rather than getting it by accident.
    secure = !app.config.insecureCookies,
    path = "/",
    extensions = mapOf("SameSite" to "Lax"),
)

/** The cookie, resolved to a reader, or null. Every route that writes anything calls this first. */
private fun RoutingContext.signedIn(app: App): Sessions.Entry? = app.sessions.of(call.request.cookies[COOKIE])

// The draft named in the path, but only if this reader owns it.
//
// The pubkey is passed down into the store rather than compared here: a check
// the caller performs is a check some future route forgets to perform.
private fun RoutingContext.draftOf(
    app: App,
    pubkey: String,
): Drafts.Draft? = call.parameters["id"]?.let { app.drafts.of(it, pubkey) }

// The preview's own leash.
//
// `default-src 'none'` with images allowed anywhere is exactly the shape of the
// page: it hotlinks art from whatever host published it and does nothing else.
// No scripts, no frames, no form posts, no connections.
private const val CSP =
    "default-src 'none'; img-src https: data:; style-src 'unsafe-inline'; " +
        "font-src https:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

// Our own address, from configuration, never from the request.
//
// See Config.publicUrl: every header a client could supply here is a header the
// client chooses, and a NIP-98 signature compared against a caller-supplied URL
// checks nothing at all.
private fun ApplicationCall.fullUrl(app: App): String = app.config.publicUrl.trimEnd('/') + request.uri
