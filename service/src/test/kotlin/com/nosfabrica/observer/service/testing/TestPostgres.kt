package com.nosfabrica.observer.service.testing

import com.nosfabrica.observer.service.keys.AssistantKeys
import com.nosfabrica.observer.service.state.Db
import com.nosfabrica.observer.service.state.Migrations
import org.junit.jupiter.api.Assumptions
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * A Postgres no test can mistake for a real one:
 *
 * 1. a throwaway cluster, `initdb` into a temp dir and `pg_ctl` on a free localhost port, when the
 *    binaries are there (`PG_BIN`, else the newest `/usr/lib/postgresql/<n>/bin`); run as the
 *    `postgres` user when the tests run as root, since `initdb` refuses root;
 * 2. else the server CI's `postgres` service gives, `TEST_DATABASE_URL` / `_USER` / `_PASSWORD`,
 *    a superuser on a database nothing else uses;
 * 3. else none, and every test that needs it is skipped with [unavailable] as the reason.
 *
 * Each [database] call creates a database of its own, so test classes never see each other's rows.
 */
object TestPostgres {
    private class Server(
        val host: String,
        val port: Int,
        val user: String,
        val password: String?,
    ) {
        fun url(database: String) = "jdbc:postgresql://$host:$port/$database"
    }

    private var startFailure: String? = null

    private val server: Server? by lazy { startCluster() ?: fromEnv() }

    /** Why there is no Postgres, or null when there is one. */
    val unavailable: String?
        get() =
            if (server != null) {
                null
            } else {
                "no Postgres for this test: no cluster could be started (${startFailure ?: "no binaries"}) " +
                    "and TEST_DATABASE_URL is not set"
            }

    /** A fresh, empty database as the superuser; the calling test is skipped when there is no Postgres. */
    fun database(): Db {
        val s = server
        Assumptions.assumeTrue(s != null, unavailable)
        val name =
            "t_" +
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(16)
        DriverManager.getConnection(s!!.url("postgres"), s.user, s.password).use { c ->
            c.createStatement().use { it.execute("CREATE DATABASE $name") }
        }
        return Db(s.url(name), s.user, s.password)
    }

    /**
     * A login role on [db]'s server, with the service's grants (`service/README.md`, "Deploying"):
     * SELECT on the four columns of `brainstorm_nsec` it reads, and the `observer` schema as its
     * own. Connects with [password].
     */
    fun serviceRole(
        db: Db,
        name: String =
            "r_" +
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(12),
        password: String = "test-" + UUID.randomUUID(),
    ): Db {
        db.connect().use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE ROLE $name LOGIN PASSWORD '$password'")
                st.execute("GRANT SELECT (${AssistantKeys.COLUMNS.joinToString()}) ON ${AssistantKeys.TABLE} TO $name")
                st.execute("CREATE SCHEMA IF NOT EXISTS ${Migrations.SCHEMA} AUTHORIZATION $name")
            }
        }
        return Db(db.url, name, password)
    }

    private fun fromEnv(): Server? {
        val url = System.getenv("TEST_DATABASE_URL")?.takeIf { it.isNotBlank() } ?: return null
        val m = Regex("jdbc:postgresql://([^:/]+)(?::(\\d+))?/.*").matchEntire(url) ?: return null
        return Server(
            m.groupValues[1],
            m.groupValues[2].ifEmpty { "5432" }.toInt(),
            System.getenv("TEST_DATABASE_USER") ?: "postgres",
            System.getenv("TEST_DATABASE_PASSWORD"),
        )
    }

    private fun binaries(): File? {
        System.getenv("PG_BIN")?.let { dir -> return File(dir).takeIf { File(it, "initdb").canExecute() } }
        return File("/usr/lib/postgresql")
            .listFiles()
            ?.mapNotNull { v -> v.name.toIntOrNull()?.let { it to File(v, "bin") } }
            ?.filter { File(it.second, "initdb").canExecute() }
            ?.maxByOrNull { it.first }
            ?.second
    }

    private fun startCluster(): Server? {
        val bin = binaries() ?: return null
        return try {
            val asRoot = System.getProperty("user.name") == "root"
            val dir = Files.createTempDirectory("observer-pg").toFile()
            if (asRoot) run(listOf("chown", "postgres", dir.path))
            val data = File(dir, "data")
            val port = ServerSocket(0).use { it.localPort }

            fun pg(vararg args: String) = run((if (asRoot) listOf("runuser", "-u", "postgres", "--") else emptyList()) + args.toList())
            pg(File(bin, "initdb").path, "-D", data.path, "-U", "postgres", "--auth=trust", "-E", "UTF8", "--no-sync")
            pg(
                File(bin, "pg_ctl").path,
                "-D",
                data.path,
                "-l",
                File(dir, "log").path,
                "-w",
                "-o",
                // The socket in the temp dir: /var/run/postgresql may not be writable here. No fsync: it is thrown away.
                "-c listen_addresses=localhost -p $port -k ${dir.path} -c fsync=off -c max_connections=200",
                "start",
            )
            Runtime.getRuntime().addShutdownHook(
                Thread {
                    try {
                        pg(File(bin, "pg_ctl").path, "-D", data.path, "-m", "immediate", "stop")
                    } finally {
                        dir.deleteRecursively()
                    }
                },
            )
            Server("localhost", port, "postgres", null)
        } catch (e: Exception) {
            startFailure = e.message
            null
        }
    }

    private fun run(command: List<String>) {
        val p = ProcessBuilder(command).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().decodeToString()
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            error("${command.first()} timed out")
        }
        check(p.exitValue() == 0) { "${command.joinToString(" ").take(80)} failed: ${out.takeLast(400)}" }
    }
}

/**
 * brainstorm_server's side, as much of it as the service reads: the `brainstorm_nsec` table, shaped
 * as `app/db_models`' `BrainstormNsec` (with one column the role may not read, `session_version`).
 * There is no view: the service reads the table's four columns as the server's own repo does.
 */
object BrainstormSchema {
    fun create(db: Db) {
        db.connect().use { c ->
            c.createStatement().use { st ->
                st.execute(
                    """
                    CREATE TABLE brainstorm_nsec (
                        pubkey varchar PRIMARY KEY,
                        nsec varchar NOT NULL,
                        encrypted_nsec varchar,
                        assistant_pubkey varchar,
                        nip05_local_part varchar,
                        session_version integer NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
            }
        }
    }

    fun insert(
        db: Db,
        owner: String,
        assistant: String?,
        nsec: String,
        encrypted: String?,
    ) {
        db.connect().use { c ->
            val sql = "INSERT INTO brainstorm_nsec (pubkey, nsec, encrypted_nsec, assistant_pubkey) VALUES (?, ?, ?, ?)"
            c.prepareStatement(sql).use { st ->
                st.setString(1, owner)
                st.setString(2, nsec)
                st.setString(3, encrypted)
                st.setString(4, assistant)
                st.executeUpdate()
            }
        }
    }
}
