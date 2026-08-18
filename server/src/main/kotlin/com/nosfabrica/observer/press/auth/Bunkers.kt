package com.nosfabrica.observer.press.auth

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.relay.client.INostrClient
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip46RemoteSigner.BunkerClientMetadata
import com.vitorpamplona.quartz.nip46RemoteSigner.NostrConnectURI
import com.vitorpamplona.quartz.nip46RemoteSigner.signer.NostrSignerRemote
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * Remote signers, held open for the length of a session.
 *
 * The NIP-46 transport runs on the SERVER, not in the page. That is a real
 * choice with a real cost, so it is worth writing down why.
 *
 * A browser implementation needs secp256k1 ECDH to speak NIP-04/44 to the
 * bunker, and WebCrypto does not do secp256k1 — it would mean vendoring a
 * crypto library into the page. Meanwhile an `asknostr` thread in the very
 * corpus this project reads describes the other half of the problem: mobile
 * browsers drop websocket subscriptions when the tab is backgrounded, which is
 * exactly when a reader is switching to their signer app to approve something.
 * A connection held by the server does not get backgrounded.
 *
 * What it costs: while a session is open, this process can ask the reader's
 * signer to sign things. The signer is still the one that decides, and every
 * request is a prompt on their device — but the honest description is that we
 * hold a connection, not that we hold nothing. That is also precisely what
 * Phase 4's scheduled editions need, which is the other reason it is here.
 */
class Bunkers(
    private val client: INostrClient,
) {
    private val open = ConcurrentHashMap<String, NostrSignerRemote>()

    private val metadata =
        BunkerClientMetadata(
            name = "The Nostr Observer",
            url = "https://github.com/NosFabrica/the-nostr-observer",
            image = "",
        )

    /** One live connection to a signer, before a session exists to file it under. */
    class Connected(
        val pubkey: String,
        internal val remote: NostrSignerRemote,
        internal val authUrl: () -> String?,
    )

    /**
     * Connect to a `bunker://` URI and find out who it speaks for.
     *
     * The client key is ephemeral and per-connection: it is the identity the
     * bunker knows us by, and it should not outlive the reason it exists. The
     * consequence is that signing out actually ends the connection rather than
     * leaving a standing authorization behind on the reader's signer.
     *
     * Connecting happens BEFORE a session exists, because the pubkey this
     * returns is what the session will be for. [adopt] files it afterwards.
     */
    suspend fun connect(
        uri: String,
        timeoutMs: Long = 60_000,
    ): Result<Connected> =
        runCatching {
            val bunker = NostrConnectURI.parseBunker(uri) ?: error("that is not a bunker:// address")
            var authUrl: String? = null
            val remote =
                NostrSignerRemote(
                    NostrSignerInternal(KeyPair()),
                    bunker.remoteSignerPubKey,
                    bunker.relays,
                    client,
                    // Ask once, up front, for everything a publish will need. A
                    // reader who approves one prompt and is then surprised by two
                    // more mid-publish is the version people abandon.
                    PERMISSIONS,
                    bunker.secret,
                    metadata,
                ) { url -> authUrl = url }

            remote.openSubscription()
            try {
                withTimeout(timeoutMs) {
                    remote.connect()
                    // getPublicKey rather than trusting the URI: the address says
                    // which SIGNER to talk to, and only the signer can say which
                    // user it will sign for.
                    val pubkey = remote.getPublicKey()
                    remote.bindUserPubkey(pubkey)
                    Connected(pubkey, remote) { authUrl }
                }
            } catch (failure: Throwable) {
                remote.closeSubscription()
                throw failure
            }
        }

    fun adopt(
        token: String,
        connected: Connected,
    ) {
        open[token] = connected.remote
        authUrls[token] = connected.authUrl
    }

    /**
     * Sign a template through the reader's remote signer.
     *
     * The result still goes through `Countersign`. A remote signer is somebody
     * else's software on somebody else's device: that it is the reader's own
     * signer makes it trusted to hold their key, not trusted to have signed the
     * bytes we sent.
     */
    suspend fun sign(
        token: String,
        template: EventTemplate<Event>,
        timeoutMs: Long = 120_000,
    ): Result<Event> =
        runCatching {
            val remote = open[token] ?: error("no signer connected for this session")
            withTimeout(timeoutMs) {
                remote.sign<Event>(template.createdAt, template.kind, template.tags, template.content)
            }
        }

    fun has(token: String) = open.containsKey(token)

    /** An `auth_url` the signer wants a human to visit, if one is outstanding. */
    fun authUrl(token: String): String? = authUrls[token]?.invoke()

    fun close(token: String) {
        open.remove(token)?.closeSubscription()
        authUrls.remove(token)
    }

    private val authUrls = ConcurrentHashMap<String, () -> String?>()

    private companion object {
        /**
         * Exactly what this app does, named up front.
         *
         * kind 27235 is the sign-in signature, 24242 authorizes one upload, and
         * 35128 is the site manifest. Asking for `sign_event` unqualified would
         * be asking for permission to post as the reader, which this service
         * never does.
         */
        const val PERMISSIONS = "sign_event:27235,sign_event:24242,sign_event:35128"
    }
}
