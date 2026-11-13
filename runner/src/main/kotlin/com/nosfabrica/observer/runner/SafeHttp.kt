package com.nosfabrica.observer.runner

import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.net.UnknownHostException
import javax.net.SocketFactory

/*
 * The worker runs inside Brainstorm's network, and every URL it dials for a reader came from
 * that reader: the AI key's endpoint (NIP-FA even allows `http` to loopback), their Blossom
 * servers, their relays. Any of them could name an internal service, by address or by a name
 * that resolves to one, so a reader could make the worker call it (SSRF).
 *
 * Four layers, because each one alone has a hole:
 * - [UrlGuard.refusal] reads the URL: the scheme, an address literal, an internal-only name.
 * - [GuardedDns] checks every address a name resolves to, at connect time, every time, so a
 *   name that passed a check and then rebinds to 10.0.0.1 is still refused.
 * - [GuardedSocketFactory] checks the address every socket connects to: OkHttp never asks
 *   [Dns] about a host that looks like an IPv4 literal (`127.1`), so this is the backstop.
 * - The client follows no redirects, so a public host can't bounce a request inward.
 *
 * The guarded client also dials directly (no proxy): through a proxy, the proxy resolves the
 * name, and the address check would never see it.
 */

/** Why a reader's URL was refused, for the log and the run's outcome. Never carries the URL's path or query. */
class RefusedUrlException(
    val reason: String,
) : IOException("refused: $reason")

object UrlGuard {
    /**
     * The refusal of a Tor onion. The worker has no Tor, and an onion name must never reach a
     * resolver: the cluster's DNS would pass `….onion` upstream, telling it which hidden service a
     * reader uses. [GuardedDns] asks [hostRefusal] before it resolves anything, so this keeps it there.
     */
    const val ONION = "an onion address (the worker has no Tor)"

    /** Whether [host] is a Tor onion name, any version, any spelling: never dialled, never resolved. */
    fun isOnion(host: String): Boolean {
        val h =
            host
                .lowercase()
                .removePrefix("[")
                .removeSuffix("]")
                .trimEnd('.')
        return h == "onion" || h.endsWith(".onion")
    }

    /** Names that only mean something inside a network. */
    private val INTERNAL_SUFFIXES =
        listOf(".localhost", ".local", ".internal", ".intranet", ".lan", ".home.arpa", ".svc", ".cluster.local", ".corp")

    /**
     * Why [url] may not be dialled, or null when it may. [schemes] are the ones this use accepts
     * (`https` for an AI key or a Blossom server, `wss` for a relay). Reads the URL only: names
     * are checked again, against what they resolve to, by [GuardedDns].
     */
    fun refusal(
        url: String,
        schemes: Set<String> = setOf("https"),
    ): String? {
        val uri =
            try {
                URI(url.trim())
            } catch (e: Exception) {
                return "not a URL"
            }
        val scheme = uri.scheme?.lowercase() ?: return "not a URL"
        // Before the scheme: an `http://….onion` node is refused as an onion, which says what to do about it.
        if (isOnion(uri.host.orEmpty())) return ONION
        if (scheme !in schemes) return "scheme $scheme is not allowed here"
        val host =
            uri.host
                ?.lowercase()
                ?.removePrefix("[")
                ?.removeSuffix("]")
                ?.trimEnd('.') ?: return "no host"
        if (uri.userInfo != null) return "credentials in the URL"
        return hostRefusal(host)
    }

    /** Why [host] (a name or an address literal) is internal, or null. */
    fun hostRefusal(host: String): String? {
        val h =
            host
                .lowercase()
                .removePrefix("[")
                .removeSuffix("]")
                .trimEnd('.')
        if (h.isEmpty()) return "no host"
        if (isOnion(h)) return ONION
        when (val literal = literal(h)) {
            is Literal.Address -> return if (isInternal(literal.address)) "an internal address" else null
            Literal.Unreadable -> return "an address no resolver reads the same way"
            Literal.NotOne -> {}
        }
        if (h == "localhost" || INTERNAL_SUFFIXES.any { h.endsWith(it) }) return "an internal name"
        // A single label ("redis", "brainstorm-server") is a name only an internal resolver answers.
        if ('.' !in h) return "an internal name"
        return null
    }

    /**
     * Whether [host] is an address and not a name: an IPv6 literal, or a host whose last label is
     * a number (WHATWG's "ends in a number": digits, or `0x` and hex digits). Such a host is read
     * the way `inet_aton` and Java read it: one to four parts, each decimal, octal (a leading 0)
     * or hex (`0x`), the last filling the bytes left. So `127.1`, `10.1`, `0x7f.1`, `2130706433`
     * and `017700000001` are all addresses. OkHttp dials a `[0-9.]+` host without asking [Dns],
     * so this is the only place before the socket that sees them.
     */
    private sealed interface Literal {
        object NotOne : Literal

        object Unreadable : Literal

        class Address(
            val address: InetAddress,
        ) : Literal
    }

    private fun literal(host: String): Literal {
        if (':' in host) {
            return try {
                Literal.Address(InetAddress.getByName(host))
            } catch (e: UnknownHostException) {
                Literal.Unreadable
            }
        }
        val parts = host.split('.')
        if (!isNumberPart(parts.last())) return Literal.NotOne
        if (parts.size > 4) return Literal.Unreadable
        val values = parts.map { numberValue(it) ?: return Literal.Unreadable }
        // Every part but the last is one byte; the last fills the bytes left.
        if (values.dropLast(1).any { it > 255 }) return Literal.Unreadable
        val lastBytes = 5 - values.size
        if (values.last() >= (1L shl (8 * lastBytes))) return Literal.Unreadable
        var n = 0L
        values.dropLast(1).forEach { n = (n shl 8) or it }
        n = (n shl (8 * lastBytes)) or values.last()
        val bytes = ByteArray(4) { i -> ((n shr (8 * (3 - i))) and 0xff).toByte() }
        return Literal.Address(InetAddress.getByAddress(bytes))
    }

    /** WHATWG's test for a label that makes the host an IPv4 address: digits, or `0x` and hex digits (none included). */
    private fun isNumberPart(label: String): Boolean =
        label.isNotEmpty() &&
            (label.all { it in '0'..'9' } || (label.startsWith("0x") && label.drop(2).all { it in '0'..'9' || it in 'a'..'f' }))

    /** One part as `inet_aton` reads it, or null when it isn't a number it reads. */
    private fun numberValue(part: String): Long? {
        val (digits, radix) =
            when {
                part.startsWith("0x") -> part.drop(2) to 16
                part.length > 1 && part.startsWith("0") -> part.drop(1) to 8
                else -> part to 10
            }
        // At most 32 bits: a longer run of digits can't be an address part (and would overflow).
        if (digits.isEmpty() || digits.length > 32) return null
        return digits.toLongOrNull(radix)?.takeIf { it <= 0xffffffffL }
    }

    /**
     * Loopback, unspecified, private (RFC 1918), link-local, shared (RFC 6598, used inside
     * clusters), benchmark, reserved, multicast, IPv6 unique-local, and IPv4 inside IPv6
     * (mapped, compatible, NAT64) judged by its IPv4.
     */
    fun isInternal(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) {
            return true
        }
        val b = address.address.map { it.toInt() and 0xff }
        if (address is Inet4Address) return isInternalV4(b)
        if (address is Inet6Address) {
            // fc00::/7 unique local; fec0::/10 is site-local (above).
            if (b[0] and 0xfe == 0xfc) return true
            val embedded =
                when {
                    // ::ffff:a.b.c.d (mapped) and ::a.b.c.d (compatible)
                    b
                        .subList(
                            0,
                            10,
                        ).all { it == 0 } && ((b[10] == 0xff && b[11] == 0xff) || (b[10] == 0 && b[11] == 0)) -> b.subList(12, 16)

                    // 64:ff9b::/96 (NAT64)
                    b[0] == 0 && b[1] == 0x64 && b[2] == 0xff && b[3] == 0x9b && b.subList(4, 12).all { it == 0 } -> b.subList(12, 16)

                    else -> null
                }
            return embedded != null && isInternalV4(embedded)
        }
        return true
    }

    private fun isInternalV4(b: List<Int>): Boolean =
        b[0] == 0 ||
            b[0] == 10 ||
            b[0] == 127 ||
            (b[0] == 100 && b[1] in 64..127) ||
            (b[0] == 169 && b[1] == 254) ||
            (b[0] == 172 && b[1] in 16..31) ||
            (b[0] == 192 && b[1] == 0 && b[2] == 0) ||
            (b[0] == 192 && b[1] == 168) ||
            (b[0] == 198 && b[1] in 18..19) ||
            b[0] >= 224
}

/**
 * Resolves names and refuses the whole answer when any address in it is internal: a name that
 * answers with one public and one private address is not trusted to be dialled at the public one.
 */
class GuardedDns(
    private val delegate: Dns = Dns.SYSTEM,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        UrlGuard.hostRefusal(hostname)?.let { throw RefusedUrlException(it) }
        val addresses = delegate.lookup(hostname)
        if (addresses.isEmpty()) throw UnknownHostException(hostname)
        if (addresses.any { UrlGuard.isInternal(it) }) throw RefusedUrlException("a name that resolves to an internal address")
        return addresses
    }
}

/**
 * The last layer: every socket the guarded client opens checks the address it is about to
 * connect to, whatever the URL said and whichever path got there (a literal OkHttp never sent to
 * [Dns], a resolver this code doesn't know of, a route OkHttp retries).
 */
class GuardedSocketFactory(
    /** How much each socket may read per window, or null for no bound ([ReadBudget]). */
    private val budget: ReadBudget? = null,
) : SocketFactory() {
    override fun createSocket(): Socket = GuardedSocket(budget)

    override fun createSocket(
        host: String,
        port: Int,
    ): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress?,
        localPort: Int,
    ): Socket = createSocket().apply { bind(InetSocketAddress(localHost, localPort)).also { connect(InetSocketAddress(host, port)) } }

    override fun createSocket(
        host: InetAddress,
        port: Int,
    ): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int,
    ): Socket = createSocket().apply { bind(InetSocketAddress(localAddress, localPort)).also { connect(InetSocketAddress(address, port)) } }
}

/** A socket that won't connect to an internal address ([UrlGuard.isInternal]), and reads within its [budget]. */
private class GuardedSocket(
    private val budget: ReadBudget?,
) : Socket() {
    // One count per socket, however many times its stream is asked for.
    private val meter = budget?.let { ReadMeter(it) }

    override fun connect(
        endpoint: SocketAddress?,
        timeout: Int,
    ) {
        val address = (endpoint as? InetSocketAddress)?.address ?: throw RefusedUrlException("an unresolved address")
        if (UrlGuard.isInternal(address)) throw RefusedUrlException("an internal address")
        super.connect(endpoint, timeout)
    }

    override fun getInputStream(): InputStream {
        val stream = super.getInputStream()
        return meter?.let { MeteredInputStream(stream, it) } ?: stream
    }
}

/**
 * At most [maxBytes] read by one socket in any [windowMs]. OkHttp has no limit on an inbound
 * WebSocket message: it buffers the whole message, however long, before it hands it on, so a
 * relay sending one endless frame would grow the worker's memory until it dies. The bytes are
 * counted below TLS, where frames can't be seen, so the bound is on the rate: a message can only
 * grow by [maxBytes] per [windowMs], while a read lasts at most [QuartzRelayAccess.FETCH_DEADLINE_MS]
 * and the relay client drops a relay that no read wants. Past the budget the socket fails, and
 * the relay with it.
 */
class ReadBudget(
    val maxBytes: Long,
    val windowMs: Long,
    val clock: () -> Long = System::currentTimeMillis,
)

private class ReadMeter(
    private val budget: ReadBudget,
) {
    private var windowStart = budget.clock()
    private var used = 0L

    @Synchronized
    fun add(bytes: Int) {
        val at = budget.clock()
        if (at - windowStart >= budget.windowMs) {
            windowStart = at
            used = 0
        }
        used += bytes
        if (used > budget.maxBytes) throw IOException("read more than ${budget.maxBytes} bytes in ${budget.windowMs} ms")
    }
}

private class MeteredInputStream(
    stream: InputStream,
    private val meter: ReadMeter,
) : FilterInputStream(stream) {
    override fun read(): Int = super.read().also { if (it >= 0) meter.add(1) }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int = super.read(b, off, len).also { if (it > 0) meter.add(it) }
}

/** Refuses a request whose URL [UrlGuard] refuses, before any connection is made. */
class UrlGuardInterceptor(
    private val schemes: Set<String> = setOf("https"),
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        // OkHttp has already turned ws/wss into http/https here.
        val url = chain.request().url
        val asWritten = if (url.isHttps) "https" else "http"
        val allowed =
            schemes
                .map {
                    if (it == "wss") {
                        "https"
                    } else if (it == "ws") {
                        "http"
                    } else {
                        it
                    }
                }.toSet()
        if (asWritten !in allowed) throw RefusedUrlException("scheme $asWritten is not allowed here")
        UrlGuard.hostRefusal(url.host)?.let { throw RefusedUrlException(it) }
        return chain.proceed(chain.request())
    }
}

/**
 * [base] made safe to point at a reader's URL: the URL check, the address check at connect time
 * ([dns]) and on every socket, bounded bodies ([BodyCapInterceptor]), no redirects, no proxy.
 */
fun guardedClient(
    base: OkHttpClient,
    schemes: Set<String> = setOf("https"),
    dns: Dns = GuardedDns(),
    readBudget: ReadBudget? = null,
): OkHttpClient =
    base
        .newBuilder()
        .addInterceptor(UrlGuardInterceptor(schemes))
        .addInterceptor(BodyCapInterceptor())
        .dns(dns)
        .socketFactory(GuardedSocketFactory(readBudget))
        .followRedirects(false)
        .followSslRedirects(false)
        .proxy(Proxy.NO_PROXY)
        .build()

/**
 * Bounds what a reader's server can make the worker hold: a successful body is cut off with an
 * [IOException] past [maxBytes] (the engine then reports the server unreachable), and an error
 * body is read to [maxErrorBytes] at most, the rest dropped. A reader's AI endpoint answering
 * with an endless stream, or one huge line, would otherwise grow the worker's memory without end.
 */
class BodyCapInterceptor(
    private val maxBytes: Long = MAX_BODY_BYTES,
    private val maxErrorBytes: Long = MAX_ERROR_BYTES,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        // A WebSocket upgrade's stream is the socket itself, never a body to read.
        if (response.code == 101) return response
        val body = response.body
        val type = body.contentType()
        if (!response.isSuccessful) {
            val kept =
                body.use {
                    val source = it.source()
                    source.request(maxErrorBytes)
                    val buffer = Buffer()
                    buffer.write(source.buffer, minOf(maxErrorBytes, source.buffer.size))
                    buffer.readByteArray()
                }
            return response.newBuilder().body(kept.toResponseBody(type)).build()
        }
        val capped =
            object : ForwardingSource(body.source()) {
                private var total = 0L

                override fun read(
                    sink: Buffer,
                    byteCount: Long,
                ): Long {
                    val n = super.read(sink, byteCount)
                    if (n > 0) {
                        total += n
                        if (total > maxBytes) throw IOException("the response is over $maxBytes bytes")
                    }
                    return n
                }
            }
        return response.newBuilder().body(capped.buffer().asResponseBody(type, -1)).build()
    }

    companion object {
        /**
         * A streamed page arrives as Server-Sent Events, a few hundred bytes of JSON around every
         * few characters of text, reasoning included: a long paper is several megabytes on the
         * wire. 32 MB is far past any real one, and bounds the text it can carry.
         */
        const val MAX_BODY_BYTES = 32L * 1024 * 1024

        /** An error body is only ever trimmed to 160 characters for the log's detail. */
        const val MAX_ERROR_BYTES = 16L * 1024
    }
}
