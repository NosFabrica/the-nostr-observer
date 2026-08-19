package com.nosfabrica.observer.press

import com.nosfabrica.observer.WINDOW_SECONDS
import com.nosfabrica.observer.nostr.Names
import com.nosfabrica.observer.nostr.Readiness
import com.nosfabrica.observer.press.auth.Sessions
import com.nosfabrica.observer.press.auth.SignIn
import com.nosfabrica.observer.press.publish.Announce
import com.nosfabrica.observer.press.publish.Countersign
import com.nosfabrica.observer.press.publish.Templates
import com.vitorpamplona.quartz.nip01Core.core.Event
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
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
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

/**
 * One past edition, as the console needs it.
 *
 * [address] is the edition as an `naddr1…`, for pasting into a Nostr client —
 * and for the NIP-09 deletion that removes it.
 *
 * THERE IS NO `url` HERE, and the absence is the point. It used to carry
 * `servers.first() + "/" + hash`, assembled here — the same guess this codebase
 * had already caught itself making at upload, where BUD-02 hands back the URL
 * and `blossom.primal.net` hands back one with `.html` on the end. At upload
 * the fix was to take the descriptor's URL. There is no descriptor to take one
 * from here: a manifest names servers and a hash, and the descriptor was a
 * response to a request made on a different day, which we deliberately do not
 * store.
 *
 * So the honest options were a link that may 404 or no link, and now that
 * `/api/archive/{day}/view` reads the edition properly there is nothing for a
 * guessed one to do. `Blossom.fetch` builds the same `server + "/" + hash` to
 * FETCH with — but it checks what comes back against the signed hash, tries the
 * next server when one fails, and says which ones did. That is a guess that
 * catches itself. Handing the same string to a reader as a link is not.
 */
@Serializable
private data class Past(
    val day: String,
    val headline: String?,
    val address: String?,
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
    val lines: List<Line>,
    val summary: String? = null,
    val error: String? = null,
    val report: String? = null,
    /** The unsigned upload authorization, once there is a page to authorize. */
    val upload: String? = null,
    /** The unsigned site event for the day. */
    val manifest: String? = null,
    val servers: List<String> = emptyList(),
    /**
     * Whether the page is still here to be saved from `/api/editions/{id}/page`.
     *
     * Matters most when the state is FAILED: an edition that failed its own
     * checks was written and paid for like any other, and without this the
     * browser has no way to know there is anything left to offer.
     */
    val held: Boolean = false,
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
    /** Where the page actually is, on the first of their own servers. */
    val url: String? = null,
    val uploads: List<Outcome>,
    val relays: List<Outcome>,
)

@Serializable
private data class Outcome(
    val target: String,
    val ok: Boolean,
    val detail: String,
)

/**
 * The edition exists, and nowhere will keep it.
 *
 * A refused upload is the one failure in this whole flow that costs the reader
 * something they cannot get back: the page was written, it was paid for, and
 * it is held in this process's memory and nowhere else. A grey line saying
 * "no server accepted the upload" is a true sentence that leaves them not
 * knowing the page is about to be gone.
 *
 * So this carries three things the plain [Problem] cannot: what each server
 * actually said, whether we still hold the bytes, and for how long.
 */
@Serializable
private data class Lost(
    /** The headline sentence. Named `error` so any client that only knows [Problem] still says something true. */
    val error: String,
    val uploads: List<Outcome>,
    /** True while the page can still be saved from `/api/editions/{id}/page`. */
    val recoverable: Boolean,
    /** Roughly how long that stays true. */
    val minutes: Int,
)

@Serializable
private data class Current(
    val id: String,
    val state: String,
    val report: String? = null,
    /** Whether the page is still here to be saved. */
    val held: Boolean = false,
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
            // Their name and their servers are two different reads against the
            // same handful of hosts, and they ran one after the other: every
            // page load paid a full round trip to a relay it was already
            // waiting on. Neither needs the other's answer.
            val (name, store) =
                coroutineScope {
                    val named = async { app.press.nameOf(session.pubkey, facts.writeRelays.orEmpty()) }
                    // Asked here, before an edition exists. Learning you have
                    // nowhere to publish AFTER paying for a paper is the failure
                    // the second chain is for, and that is exactly where the
                    // check used to sit.
                    val stored =
                        async {
                            app.press.storage(
                                session.pubkey,
                                facts.writeRelays.orEmpty(),
                                // NOT ASKED, and worth saying why. This used to
                                // run `announce.editions` -- a fan-out across
                                // every one of the reader's relays, fetching
                                // every site event they have ever published --
                                // on every page load, to decide between the
                                // words "has published before" and "asked at
                                // publish" on one link of a chain inside a
                                // closed <details>. Both branches of
                                // `Readiness.storage` return the identical
                                // verdict, and the console asks `/api/archive`
                                // moments later, which runs the same query
                                // again.
                                //
                                // Null is the honest value: we did not look.
                                publishedBefore = null,
                            )
                        }
                    named.await() to stored.await()
                }
            call.respond(
                Preflight(
                    // Their own profile lives on their own relays, which this
                    // call has just learned, so it costs one more filter on
                    // hosts we are already talking to.
                    name = name,
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

        // Every edition they have published, read off their own relays.
        //
        // Nothing here comes from a table of ours. Each day is its own kind
        // 35128 under a `d` of `observer-<date>`, so this is their archive
        // whether we are running or not.
        get("/api/archive") {
            val session = signedIn(app) ?: return@get call.respond(HttpStatusCode.Unauthorized, Problem("not signed in"))
            val relays = app.press.writeRelaysOf(session.pubkey)
            call.respond(
                app.announce.editions(session.pubkey, relays).map { edition ->
                    Past(
                        day = edition.day,
                        headline = edition.headline,
                        address = Templates.address(session.pubkey, edition.day),
                    )
                },
            )
        }

        // Take one edition off the network.
        //
        // Two calls, like publishing: this one hands back the deletion request
        // to sign, and the next publishes what comes back. Built here so
        // Countersign has something to compare against -- a flow where the
        // client invents its own kind 5 and we relay it can check nothing.
        post("/api/archive/{day}/remove") {
            val session = signedIn(app) ?: return@post call.respond(HttpStatusCode.Unauthorized, Problem("not signed in"))
            val day =
                call.parameters["day"]?.takeIf { Templates.dayOf(Templates.site(it)) != null }
                    ?: return@post call.respond(HttpStatusCode.BadRequest, Problem("that is not a day"))
            val template = Templates.deletion(session.pubkey, day, Instant.now().epochSecond)
            app.removals[session.pubkey] = Removal(day, template)
            call.respond(ToSign(template.toJson(), "", emptyList(), app.press.writeRelaysOf(session.pubkey), ""))
        }

        post("/api/archive/{day}/removed") {
            val session = signedIn(app) ?: return@post call.respond(HttpStatusCode.Unauthorized, Problem("not signed in"))
            val day = call.parameters["day"].orEmpty()
            val template =
                app.removals[session.pubkey]
                    ?.takeIf { it.day == day }
                    ?.template
                    ?: return@post call.respond(HttpStatusCode.Conflict, Problem("nothing to remove"))

            val body = call.receiveText()
            val offered = runCatching { json.decodeFromString<Signed>(body) }.getOrNull()
            val token = call.request.cookies[COOKIE]
            val candidate =
                if (offered != null && offered.upload.isNotBlank()) {
                    Event.fromJsonOrNull(offered.upload)
                } else if (token != null && app.bunkers.has(token)) {
                    app.bunkers.sign(token, template).getOrNull()
                } else {
                    null
                }
            val signed = candidate ?: return@post call.respond(HttpStatusCode.BadRequest, Problem("that is not a signed event"))

            (Countersign.check(signed, template, session.pubkey) as? Countersign.Result.No)?.let {
                return@post call.respond(HttpStatusCode.BadRequest, Problem("signature rejected: ${it.reason}"))
            }

            val relays = app.press.writeRelaysOf(session.pubkey)
            val announced = app.announce.publish(signed, relays)
            app.removals.remove(session.pubkey)
            call.respond(
                PublishReport(
                    ok = announced.any { it.ok },
                    day = day,
                    naddr = Templates.address(session.pubkey, day) ?: "",
                    uploads = emptyList(),
                    relays = announced.map { Outcome(it.relay, it.ok, it.message) },
                ),
            )
        }

        post("/api/editions") {
            val session = signedIn(app) ?: return@post call.respond(HttpStatusCode.Unauthorized, Problem("not signed in"))
            // A missing or unparseable body is a request with no preference,
            // not a bad request: the timezone is the only thing in it.
            val wanted = runCatching { Json.decodeFromString<Wanted>(call.receiveText()) }.getOrNull()
            call.respond(Started(app.editions.start(session.pubkey, wanted?.timezone).id))
        }

        // Where a run is up to, and — once it is written — what to sign.
        //
        // The templates ride along with the status rather than waiting behind a
        // second "prepare" call. There is nothing left to decide between the
        // two: the page is finished, it passed its own checks, and the only
        // thing between it and the reader's servers is their signer.
        get("/api/editions/{id}") {
            val session = signedIn(app) ?: return@get call.respond(HttpStatusCode.Unauthorized, Problem("not signed in"))
            val run =
                app.runs.of(call.parameters["id"].orEmpty(), session.pubkey)
                    ?: return@get call.respond(HttpStatusCode.NotFound, Problem("no such edition"))
            call.respond(
                Status(
                    state = run.state.name,
                    lines = run.lines,
                    summary = run.summary,
                    error = run.error,
                    report = run.report,
                    upload = run.upload?.toJson(),
                    manifest = run.manifest?.toJson(),
                    servers = run.servers,
                    held = run.html != null,
                ),
            )
        }

        // The run this reader has going, if any.
        //
        // Only so that a reload can find its way back to a page we are still
        // holding. Before this, a refused upload told the reader their edition
        // was about to be lost and offered to save it — and pressing F5 threw
        // away the offer while the bytes sat here for another half hour.
        get("/api/editions/current") {
            val session = signedIn(app) ?: return@get call.respond(HttpStatusCode.Unauthorized, Problem("not signed in"))
            val run = app.runs.of(session.pubkey) ?: return@get call.respond(HttpStatusCode.NotFound, Problem("nothing running"))
            call.respond(Current(run.id, run.state.name, run.report, run.html != null))
        }

        // The page itself, while we still have it.
        //
        // The one honest answer to a refused upload. The reader has a written,
        // checked, paid-for edition that no server would keep, and it exists in
        // this process's memory until the sweep takes it; without this it is
        // simply lost, and a message saying so and offering nothing is a worse
        // message.
        //
        // Sent as an attachment with `application/octet-stream`, NOT as
        // `text/html`. An edition is model-written markup, and rendering it on
        // this origin -- the origin that holds the session cookie -- would be
        // handing it whatever the sanitizer missed. Off the machine as a file
        // it opens on `file://`, where it belongs.
        //
        // Ownership is checked by `runs.of(id, pubkey)`, which compares the
        // session rather than trusting the id; a run id in a URL is a bearer
        // token otherwise.
        get("/api/editions/{id}/page") {
            val session = signedIn(app) ?: return@get call.respond(HttpStatusCode.Unauthorized, Problem("not signed in"))
            val run =
                app.runs.of(call.parameters["id"].orEmpty(), session.pubkey)
                    ?: return@get call.respond(HttpStatusCode.NotFound, Problem("no such edition"))
            val blob =
                run.html
                    ?: return@get call.respond(
                        HttpStatusCode.Gone,
                        Problem("that page is no longer here — it was published, or it has been swept"),
                    )
            call.response.header(
                HttpHeaders.ContentDisposition,
                """attachment; filename="observer-${run.day ?: "edition"}.html"""",
            )
            call.response.header("X-Content-Type-Options", "nosniff")
            call.respondBytes(blob, ContentType.Application.OctetStream)
        }

        // The page we are holding, to be read here.
        //
        // Same bytes as `/page`, which stays exactly as it was: that one is the
        // save, this one is the read. It matters most for an edition that
        // failed its own checks, because that page will never be published and
        // so there is no copy anywhere else to link to.
        get("/api/editions/{id}/view") {
            val session = signedIn(app) ?: return@get call.respond(HttpStatusCode.Unauthorized, Problem("not signed in"))
            val run =
                app.runs.of(call.parameters["id"].orEmpty(), session.pubkey)
                    ?: return@get call.respond(HttpStatusCode.NotFound, Problem("no such edition"))
            val blob =
                run.html
                    ?: return@get call.respond(
                        HttpStatusCode.Gone,
                        Problem("that page is no longer here — it was published, or it has been swept"),
                    )
            call.respondPage(blob)
        }

        // A back issue, read here rather than downloaded from a blob store.
        //
        // We hold nothing: the manifest on the reader's relays names the hash
        // and the servers, the blob comes back from the first server that has
        // it, and it is checked against the hash the reader themselves signed
        // before a byte of it is served. A day they never published, or one
        // whose servers are all down, is a plain answer and not a blank page.
        get("/api/archive/{day}/view") {
            val session = signedIn(app) ?: return@get call.respond(HttpStatusCode.Unauthorized, Problem("not signed in"))
            val day = call.parameters["day"].orEmpty()
            val relays = app.press.writeRelaysOf(session.pubkey)
            val edition =
                app.announce
                    .editions(session.pubkey, relays)
                    .firstOrNull { it.day == day }
                    ?: return@get call.respond(HttpStatusCode.NotFound, Problem("no edition for $day in your archive"))

            val got = app.blossom.fetch(edition.servers, edition.hash)
            val blob =
                got.blob ?: return@get call.respond(
                    HttpStatusCode.BadGateway,
                    Problem(
                        "none of your media servers would give this edition back: " +
                            got.tried.joinToString("; ").ifBlank { "no servers are listed on it" },
                    ),
                )
            call.respondPage(blob)
        }

        // Upload, then announce. Nothing here decides whether to publish — that
        // was decided when the reader pressed print.
        post("/api/editions/{id}/publish") {
            val session = signedIn(app) ?: return@post call.respond(HttpStatusCode.Unauthorized, Problem("not signed in"))
            val run =
                app.runs.of(call.parameters["id"].orEmpty(), session.pubkey)
                    ?: return@post call.respond(HttpStatusCode.NotFound, Problem("no such edition"))
            if (run.state != Runs.State.SIGNING) {
                return@post call.respond(HttpStatusCode.Conflict, Problem("this edition is not waiting to be signed"))
            }
            val wantUpload = run.upload ?: return@post call.respond(HttpStatusCode.Conflict, Problem("nothing to sign"))
            val wantManifest = run.manifest ?: return@post call.respond(HttpStatusCode.Conflict, Problem("nothing to sign"))

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
                val signedUpload = app.bunkers.sign(token, wantUpload)
                val signedManifest = app.bunkers.sign(token, wantManifest)
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
                Countersign.check(auth, wantUpload, session.pubkey),
                Countersign.check(manifest, wantManifest, session.pubkey),
            ).filterIsInstance<Countersign.Result.No>().firstOrNull()?.let {
                return@post call.respond(HttpStatusCode.BadRequest, Problem("signature rejected: ${it.reason}"))
            }

            val blob = run.html ?: return@post call.respond(HttpStatusCode.Conflict, Problem("this edition is no longer held"))
            val uploads = app.blossom.upload(run.servers, blob, auth)
            if (uploads.none { it.ok }) {
                // NOT A RETRY. The authorization the reader just signed expires
                // in ten minutes and is bound to this blob and this timestamp,
                // so there is nothing to press again -- and leaving the run in
                // SIGNING means the next poll asks their signer for two more
                // signatures for a publish that cannot happen.
                //
                // Measured 2026-08-18: nostr.build and blossom.band, which
                // between them are what a lot of readers have in their kind
                // 10063, refuse `text/html` outright. That is policy rather
                // than an outage, so the reader has to change something before
                // reprinting, and needs to be told what.
                run.error =
                    "Your media servers refused this page, so it was not saved. " +
                    "Many of them take only images and video."
                run.state = Runs.State.FAILED
                val lost =
                    Lost(
                        error = run.error!!,
                        uploads = uploads.map { Outcome(it.server, it.ok, it.detail) },
                        // The bytes are still here, which is the only reason
                        // this is recoverable at all. `forget()` is not called
                        // on this path and the sweep is what ends it.
                        recoverable = run.html != null,
                        minutes = (app.runs.ttlSeconds / 60).toInt(),
                    )
                // Kept on the run as well as answered, because the offer to save
                // the page has to survive a reload. It does not survive closing
                // the tab, and nothing can make it: the reader has thirty
                // minutes and one browser.
                run.report = json.encodeToString(lost)
                return@post call.respond(HttpStatusCode.BadGateway, lost)
            }

            // Only after a server actually holds the blob. A manifest pointing at
            // a hash nobody stores is a 404 with a signature on it.
            val announced = app.announce.publish(manifest, run.relays)
            val day = run.day.orEmpty()
            val report =
                PublishReport(
                    ok = announced.any { it.ok },
                    day = day,
                    naddr = Templates.address(session.pubkey, day) ?: "",
                    // The URL the SERVER gave us, from the first one that
                    // accepted. Assembling `server + "/" + hash` is a guess,
                    // and BUD-02 has the server answer with the real one --
                    // free to be a CDN domain, a path prefix, or to carry an
                    // extension. Guessing produces a link that 404s while the
                    // upload itself was perfectly fine.
                    url = uploads.firstOrNull { it.ok && it.url != null }?.url,
                    uploads = uploads.map { Outcome(it.server, it.ok, it.detail) },
                    relays = announced.map { Outcome(it.relay, it.ok, it.message) },
                )
            run.report = json.encodeToString(report)
            if (announced.any { it.ok }) {
                run.state = Runs.State.PUBLISHED
                // It is theirs now, so our copy goes. "We keep nothing" is
                // either true at a particular line of code or it is a footer.
                run.forget()
            }
            call.respond(report)
        }
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
// The view's own leash.
//
// `default-src 'none'` with images allowed anywhere is exactly the shape of the
// page: it hotlinks art from whatever host published it and does nothing else.
// No scripts, no frames, no form posts, no connections.
//
// `sandbox` IS THE PART THAT MATTERS, and it is why serving a page from this
// origin is allowable at all. The rest of this header restricts what the page
// may LOAD; it does nothing about the page being same-origin with the console
// that holds the session cookie. As a response header `sandbox` puts the
// document in an opaque origin however it is reached -- framed or navigated to
// directly -- so `document.cookie` is empty, storage is inert and same-origin
// reads are impossible. Without it this would be model-written markup running
// alongside the session, which is the thing the download path was built to
// avoid.
//
// No `allow-scripts`, no `allow-same-origin`, and never both: together they let
// a page reach out and remove its own sandbox attribute.
private const val CSP =
    "sandbox; default-src 'none'; img-src https: data:; style-src 'unsafe-inline'; " +
        "font-src https:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

/**
 * Serve one edition to be READ, rather than downloaded.
 *
 * A Blossom server stores blobs and hands them back with whatever
 * `Content-Type` it likes, so a link straight at `server/hash` is a page on one
 * host and a save dialog on another. Resolving the manifest and serving the
 * blob as a page is what an nsite gateway does; this does it for one signed-in
 * reader's own editions and holds nothing.
 */
private suspend fun ApplicationCall.respondPage(blob: ByteArray) {
    response.header("Content-Security-Policy", CSP)
    response.header("X-Content-Type-Options", "nosniff")
    response.header("Referrer-Policy", "no-referrer")
    // Not cached by anything in between: this is one reader's paper, served
    // from an address that says nothing about whose it is.
    response.header(HttpHeaders.CacheControl, "private, no-store")
    respondBytes(blob, ContentType.Text.Html.withParameter("charset", "utf-8"))
}

// Our own address, from configuration, never from the request.
//
// See Config.publicUrl: every header a client could supply here is a header the
// client chooses, and a NIP-98 signature compared against a caller-supplied URL
// checks nothing at all.
private fun ApplicationCall.fullUrl(app: App): String = app.config.publicUrl.trimEnd('/') + request.uri
