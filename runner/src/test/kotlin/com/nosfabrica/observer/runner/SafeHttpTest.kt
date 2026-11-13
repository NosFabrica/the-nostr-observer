package com.nosfabrica.observer.runner

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The SSRF guard. Nothing here opens a socket: every refusal happens before a connection. */
class SafeHttpTest {
    private fun ip(text: String): InetAddress = InetAddress.getByName(text)

    @Test
    fun internalAddressesAreInternal() {
        listOf(
            "127.0.0.1",
            "127.255.0.9",
            "0.0.0.0",
            "10.1.2.3",
            "172.16.0.1",
            "172.31.255.255",
            "192.168.1.1",
            "169.254.169.254",
            "100.64.0.1",
            "192.0.0.8",
            "198.18.0.1",
            "224.0.0.1",
            "255.255.255.255",
            "::1",
            "::",
            "fe80::1",
            "fc00::1",
            "fd12:3456::1",
            "::ffff:10.0.0.1",
            "::ffff:127.0.0.1",
            "64:ff9b::a00:1",
        ).forEach { assertTrue(UrlGuard.isInternal(ip(it)), it) }
    }

    @Test
    fun publicAddressesAreNot() {
        listOf("1.1.1.1", "93.184.215.14", "172.32.0.1", "100.128.0.1", "2606:4700::1111", "::ffff:8.8.8.8").forEach {
            assertFalse(UrlGuard.isInternal(ip(it)), it)
        }
    }

    @Test
    fun aKeyUrlMustBeHttpsToAPublicName() {
        assertNull(UrlGuard.refusal("https://openrouter.ai/api/v1"))
        assertNull(UrlGuard.refusal("https://api.anthropic.com/v1/"))
        listOf(
            "http://openrouter.ai/api/v1",
            "http://localhost:11434/v1",
            "https://localhost/v1",
            "https://LOCALHOST./v1",
            "https://ollama/v1",
            "https://ai.internal/v1",
            "https://brainstorm-server.default.svc.cluster.local/v1",
            "https://printer.local/v1",
            "https://10.0.0.5/v1",
            "https://[::1]/v1",
            "https://[fd00::5]/v1",
            "https://169.254.169.254/latest/meta-data",
            "https://2130706433/",
            "https://0x7f000001/",
            "https://user:pass@openrouter.ai/v1",
            "file:///etc/passwd",
            "not a url",
        ).forEach { assertNotNull(UrlGuard.refusal(it), it) }
    }

    @Test
    fun relaysMustBeWss() {
        assertNull(UrlGuard.refusal("wss://relay.example.com", setOf("wss")))
        assertNotNull(UrlGuard.refusal("ws://relay.example.com", setOf("wss")))
        assertNotNull(UrlGuard.refusal("wss://127.0.0.1:7777", setOf("wss")))
    }

    @Test
    fun aNameThatResolvesToAnInternalAddressIsRefusedAtConnectTime() {
        val rebinding = Dns { listOf(InetAddress.getByAddress(it, byteArrayOf(10, 0, 0, 1))) }
        assertFailsWith<RefusedUrlException> { GuardedDns(rebinding).lookup("ai.example.com") }
        // One internal address among public ones is enough to refuse the name.
        val mixed =
            Dns { listOf(InetAddress.getByAddress(it, byteArrayOf(1, 1, 1, 1)), InetAddress.getByAddress(it, byteArrayOf(127, 0, 0, 1))) }
        assertFailsWith<RefusedUrlException> { GuardedDns(mixed).lookup("ai.example.com") }
        val public = Dns { listOf(InetAddress.getByAddress(it, byteArrayOf(1, 1, 1, 1))) }
        assertEquals(1, GuardedDns(public).lookup("ai.example.com").size)
    }

    @Test
    fun theGuardedClientRefusesBeforeConnectingAndFollowsNoRedirect() {
        val rebinding = Dns { listOf(InetAddress.getByAddress(it, byteArrayOf(192.toByte(), 168.toByte(), 0, 1))) }
        val client = guardedClient(OkHttpClient(), dns = GuardedDns(rebinding))
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        // The address check sees every name: no proxy resolves it for us.
        assertEquals(Proxy.NO_PROXY, client.proxy)
        for (url in listOf("https://10.0.0.5/v1/models", "http://openrouter.ai/v1/models", "https://ai.example.com/v1/models")) {
            assertFailsWith<RefusedUrlException>(url) { client.newCall(Request.Builder().url(url).build()).execute() }
        }
    }

    @Test
    fun shortAndOddIpv4LiteralsAreAddressesAndJudgedAsSuch() {
        // Java and the C resolver read all of these as 127.0.0.1 or 10.0.0.1, and OkHttp dials a
        // [0-9.]+ host without asking Dns: each must be refused from the URL alone.
        listOf("127.1", "10.1", "0x7f.1", "2130706433", "017700000001", "0177.0.0.1", "0x7f000001", "127.0.1", "[::ffff:127.0.0.1]")
            .forEach { assertNotNull(UrlGuard.hostRefusal(it), it) }
        listOf("127.1", "10.1", "0x7f.1", "2130706433", "017700000001", "[::ffff:127.0.0.1]")
            .forEach { assertNotNull(UrlGuard.refusal("https://$it/v1"), it) }
        // A literal that no resolver can read as one address isn't dialled either.
        listOf("1.2.3.4.5", "0x1ffffffff", "256.1.1.1", "08.1.1.1", "1.2.3.0x").forEach { assertNotNull(UrlGuard.hostRefusal(it), it) }
        // Public addresses written the short way are still public; names made of hex letters are names.
        assertNull(UrlGuard.hostRefusal("1.1"))
        assertNull(UrlGuard.hostRefusal("0x01010101"))
        assertNull(UrlGuard.hostRefusal("8.8.8.8"))
        assertNull(UrlGuard.hostRefusal("bad.cafe"))
        assertNull(UrlGuard.hostRefusal("dead.beef.cafe"))
    }

    @Test
    fun theSocketRefusesAnInternalAddressWhateverTheUrlSaid() {
        // Only the socket layer: no interceptor, no Dns guard. 127.0.0.1 is refused at connect,
        // before any packet, so nothing is listening and nothing is reached.
        val client = OkHttpClient.Builder().socketFactory(GuardedSocketFactory()).build()
        for (url in listOf("http://127.0.0.1:1/", "http://127.1:1/", "http://[::1]:1/")) {
            val e = assertFailsWith<IOException>(url) { client.newCall(Request.Builder().url(url).build()).execute() }
            assertTrue(
                e is RefusedUrlException || e.cause is RefusedUrlException || e.suppressed.any { it is RefusedUrlException },
                "$url: $e",
            )
        }
        // And the guarded client carries it.
        assertTrue(guardedClient(OkHttpClient()).socketFactory is GuardedSocketFactory)
    }
}
