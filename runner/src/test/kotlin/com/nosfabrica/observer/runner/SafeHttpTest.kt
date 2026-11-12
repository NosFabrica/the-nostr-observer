package com.nosfabrica.observer.runner

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
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
}
