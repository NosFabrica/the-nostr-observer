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
    /** How long a call waits for the server's answer before the connection is given up ([SOCKET_TIMEOUT_SECONDS]). */
    private val socketTimeoutSeconds: Int = SOCKET_TIMEOUT_SECONDS,
    /** How long connecting and logging in may take, all of it ([LOGIN_TIMEOUT_SECONDS]). */
    private val loginTimeoutSeconds: Int = LOGIN_TIMEOUT_SECONDS,
    /** The server cancels a statement past this ([STATEMENT_TIMEOUT_MS]). */
    private val statementTimeoutMs: Long = STATEMENT_TIMEOUT_MS,
) {
    init {
        require(url.startsWith("jdbc:postgresql:")) { "DATABASE_URL must be a jdbc:postgresql: URL" }
    }

    /** This database with other bounds or another URL: the same role. For tests. */
    fun with(
        url: String = this.url,
        socketTimeoutSeconds: Int = this.socketTimeoutSeconds,
        loginTimeoutSeconds: Int = this.loginTimeoutSeconds,
        statementTimeoutMs: Long = this.statementTimeoutMs,
    ) = Db(url, user, password, io, socketTimeoutSeconds, loginTimeoutSeconds, statementTimeoutMs)

    /**
     * A new connection, autocommit on. The caller closes it.
     *
     * Every step is bounded (ported from assistant-runner): the TCP connect
     * ([CONNECT_TIMEOUT_SECONDS]), the login, each answer (a partition otherwise holds a query, and
     * the wake's run with it, until the kernel gives up, two hours on), and each statement on the
     * server's side.
     */
    fun connect(): Connection {
        val props = Properties()
        user?.let { props.setProperty("user", it) }
        password?.let { props.setProperty("password", it) }
        props.setProperty("tcpKeepAlive", "true")
        props.setProperty("connectTimeout", CONNECT_TIMEOUT_SECONDS.toString())
        props.setProperty("loginTimeout", loginTimeoutSeconds.toString())
        props.setProperty("socketTimeout", socketTimeoutSeconds.toString())
        props.setProperty("options", "-c statement_timeout=$statementTimeoutMs")
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

        const val CONNECT_TIMEOUT_SECONDS = 10
        const val LOGIN_TIMEOUT_SECONDS = 15
        const val SOCKET_TIMEOUT_SECONDS = 30
        const val STATEMENT_TIMEOUT_MS = 30_000L

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
