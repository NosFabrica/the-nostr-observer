package com.nosfabrica.observer.runner

import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException

/*
 * The worker runs inside Brainstorm's network, and every URL it dials for a reader came from
 * that reader: the AI key's endpoint (NIP-FA even allows `http` to loopback), their Blossom
 * servers, their relays. Any of them could name an internal service, by address or by a name
 * that resolves to one, so a reader could make the worker call it (SSRF).
 *
 * Three layers, because each one alone has a hole:
 * - [UrlGuard.refusal] reads the URL: the scheme, an address literal, an internal-only name.
 * - [GuardedDns] checks every address a name resolves to, at connect time, every time, so a
 *   name that passed a check and then rebinds to 10.0.0.1 is still refused.
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
        literalAddress(h)?.let { return if (isInternal(it)) "an internal address" else null }
        if (h == "localhost" || INTERNAL_SUFFIXES.any { h.endsWith(it) }) return "an internal name"
        // A single label ("redis", "brainstorm-server") is a name only an internal resolver answers.
        if ('.' !in h) return "an internal name"
        return null
    }

    /** [host] as an address when it is an IPv4 or IPv6 literal; never a DNS lookup. */
    private fun literalAddress(host: String): InetAddress? {
        val looksV4 = host.all { it.isDigit() || it == '.' } && host.count { it == '.' } == 3
        val looksV6 = ':' in host
        // A bare decimal or hex integer ("2130706433", "0x7f000001") is an address to some resolvers.
        val looksNumeric = host.all { it.isDigit() } || host.startsWith("0x")
        if (looksNumeric) return InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        if (!looksV4 && !looksV6) return null
        return try {
            InetAddress.getByName(host)
        } catch (e: UnknownHostException) {
            InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        }
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
 * ([dns]), no redirects, no proxy.
 */
fun guardedClient(
    base: OkHttpClient,
    schemes: Set<String> = setOf("https"),
    dns: Dns = GuardedDns(),
): OkHttpClient =
    base
        .newBuilder()
        .addInterceptor(UrlGuardInterceptor(schemes))
        .dns(dns)
        .followRedirects(false)
        .followSslRedirects(false)
        .proxy(Proxy.NO_PROXY)
        .build()
