package com.nosfabrica.observer.service.state

import com.nosfabrica.observer.service.testing.BrainstormSchema
import com.nosfabrica.observer.service.testing.TestPostgres
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MigrationsTest {
    private fun tables(db: Db): Set<String> =
        db.connect().use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema = 'observer'").use { rs ->
                    buildSet { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }

    @Test
    fun theyApplyToAnEmptyDatabaseAndASecondRunIsANoOp() =
        runBlocking {
            val db = TestPostgres.database()
            assertEquals(Migrations.ALL.map { it.version }, Migrations.migrate(db))
            assertEquals(setOf("schema_version", "runs", "pending", "pending_files"), tables(db))
            assertEquals(emptyList<Int>(), Migrations.migrate(db))
        }

    @Test
    fun twoProcessesMigratingAtOnceApplyEachVersionOnce() =
        runBlocking {
            val db = TestPostgres.database()
            val applied = List(4) { async(Dispatchers.IO) { Migrations.migrate(db) } }.awaitAll()
            assertEquals(Migrations.ALL.map { it.version }, applied.flatten().sorted())
            val versions =
                db.connect().use { c ->
                    c.createStatement().use { st ->
                        st.executeQuery("SELECT count(*) FROM observer.schema_version").use {
                            it.next()
                            it.getInt(1)
                        }
                    }
                }
            assertEquals(Migrations.ALL.size, versions)
        }

    @Test
    fun theRoleOpsCreatesMigratesItsOwnSchema() =
        runBlocking {
            val db = TestPostgres.database()
            BrainstormSchema.create(db)
            // It has no CREATE on the database: the schema ops made for it must be used as it is.
            val role = TestPostgres.serviceRole(db)
            assertEquals(Migrations.ALL.map { it.version }, Migrations.migrate(role))
            assertTrue("runs" in tables(db))
        }
}
