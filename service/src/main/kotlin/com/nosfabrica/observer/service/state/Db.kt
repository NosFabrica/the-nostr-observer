package com.nosfabrica.observer.service.state

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.postgresql.Driver
import java.sql.Connection
import java.util.Properties

/**
 * brainstorm_server's Postgres, through plain JDBC: four columns of its `brainstorm_nsec`
 * (`AssistantKeys`) and this service's own `observer` schema ([Migrations]). One connection per
 * operation and no pool: a run makes a handful of calls, and a pool's idle connections would be
 * one more thing to size, health-check and leak.
 *
 * Every call runs on [io]: JDBC blocks. The password is never part of [toString].
 */
class Db(
    val url: String,
    private val user: String?,
    private val password: String?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    init {
        require(url.startsWith("jdbc:postgresql:")) { "DATABASE_URL must be a jdbc:postgresql: URL" }
    }

    /** A new connection, autocommit on. The caller closes it. */
    fun connect(): Connection {
        val props = Properties()
        user?.let { props.setProperty("user", it) }
        password?.let { props.setProperty("password", it) }
        // A dead peer is noticed by the OS, not only by the next query: the leader lock's
        // connection sits idle between polls.
        props.setProperty("tcpKeepAlive", "true")
        props.setProperty("connectTimeout", "10")
        props.setProperty("ApplicationName", "observer-service")
        // Through the driver itself, not DriverManager: a jar the service loader didn't register
        // (a shaded build, an isolated class loader) still connects.
        return DRIVER.connect(url, props) ?: throw IllegalArgumentException("DATABASE_URL is not a Postgres URL")
    }

    /** [block] on a fresh connection, on the IO dispatcher. */
    suspend fun <T> use(block: (Connection) -> T): T = withContext(io) { connect().use(block) }

    /** [block] in one transaction: committed when it returns, rolled back when it throws. */
    suspend fun <T> transaction(block: (Connection) -> T): T =
        use { c ->
            c.autoCommit = false
            try {
                block(c).also { c.commit() }
            } catch (e: Throwable) {
                c.rollback()
                throw e
            }
        }

    // Without the query: a JDBC URL may carry `?password=`.
    override fun toString() = "Db(${url.substringBefore('?')})"

    companion object {
        private val DRIVER = Driver()

        /** `DATABASE_URL` (a JDBC URL), `DATABASE_USER`, `DATABASE_PASSWORD`. */
        fun fromEnv(env: Map<String, String> = System.getenv()): Db {
            val url = env["DATABASE_URL"]?.trim()?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("DATABASE_URL is not set")
            return Db(
                url,
                env["DATABASE_USER"]?.takeIf { it.isNotEmpty() },
                env["DATABASE_PASSWORD"]?.takeIf { it.isNotEmpty() },
            )
        }
    }
}
