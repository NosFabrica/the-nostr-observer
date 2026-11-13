package com.nosfabrica.observer.service.state

import com.nosfabrica.observer.service.testing.TestPostgres
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A database that stops answering must not hold a wake's run: a connect, a login and a query each
 * end. A loopback TCP server that accepts and says nothing, and a proxy to the throwaway cluster
 * that can stop forwarding mid-session, as a network partition does.
 */
class DbTimeoutTest {
    private val closing = ArrayList<AutoCloseable>()

    @AfterTest
    fun close() = closing.forEach { it.close() }

    /** Accepts and never answers. */
    private fun silentServer(): Int {
        val server = ServerSocket(0, 10, InetAddress.getLoopbackAddress())
        closing += server
        thread(isDaemon = true) {
            while (!server.isClosed) {
                try {
                    closing += server.accept()
                } catch (e: Exception) {
                    return@thread
                }
            }
        }
        return server.localPort
    }

    /** Forwards to [port] until [blackhole] is set, then drops every byte both ways, without closing. */
    private class Proxy(
        port: Int,
    ) : AutoCloseable {
        val blackhole = AtomicBoolean(false)
        val server = ServerSocket(0, 10, InetAddress.getLoopbackAddress())

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val client =
                        try {
                            server.accept()
                        } catch (e: Exception) {
                            return@thread
                        }
                    val upstream = Socket(InetAddress.getLoopbackAddress(), port)
                    pump(client, upstream)
                    pump(upstream, client)
                }
            }
        }

        private fun pump(
            from: Socket,
            to: Socket,
        ) = thread(isDaemon = true) {
            val buf = ByteArray(8192)
            try {
                while (true) {
                    val n = from.getInputStream().read(buf)
                    if (n < 0) return@thread
                    if (!blackhole.get()) to.getOutputStream().write(buf, 0, n)
                }
            } catch (e: Exception) {
            }
        }

        override fun close() = server.close()
    }

    private fun portOf(db: Db) = Regex("://[^:/]+:(\\d+)/").find(db.url)!!.groupValues[1].toInt()

    @Test
    fun aServerThatNeverAnswersTheLoginIsGivenUpOn() {
        val db = Db("jdbc:postgresql://127.0.0.1:${silentServer()}/brainstorm", "u", "p", loginTimeoutSeconds = 2)
        val started = System.nanoTime()
        assertFailsWith<SQLException> { db.connect().close() }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(tookMs < 10_000, "took $tookMs ms")
    }

    @Test
    fun aQueryThatRunsTooLongIsCancelledByTheServer() {
        val db = TestPostgres.database().with(statementTimeoutMs = 500)
        val started = System.nanoTime()
        assertFailsWith<SQLException> {
            db.connect().use { c -> c.createStatement().use { it.execute("SELECT pg_sleep(20)") } }
        }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(tookMs < 10_000, "took $tookMs ms")
    }

    @Test
    fun aConnectionTheNetworkDropsMidQueryIsGivenUpOn() {
        val real = TestPostgres.database()
        val proxy = Proxy(portOf(real)).also { closing += it }
        val db = real.with(url = real.url.replace(":${portOf(real)}/", ":${proxy.server.localPort}/"), socketTimeoutSeconds = 2)
        db.connect().use { c ->
            c.createStatement().use { it.execute("SELECT 1") }
            proxy.blackhole.set(true)
            val started = System.nanoTime()
            assertFailsWith<SQLException> { c.createStatement().use { it.execute("SELECT 1") } }
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(tookMs < 10_000, "took $tookMs ms")
        }
    }

    @Test
    fun theDefaultsAreThePlans() {
        val db = TestPostgres.database()
        db.connect().use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SHOW statement_timeout").use {
                    it.next()
                    assertEquals("30s", it.getString(1))
                }
            }
        }
    }
}
