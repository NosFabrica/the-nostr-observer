package com.nosfabrica.observer.service

import com.nosfabrica.assistant.core.FernetKeyFile
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServiceConfigTest {
    @Test
    fun theDefaults() {
        val c = ServiceConfig.fromEnv(emptyMap())
        assertEquals(8080, c.port)
        assertEquals(ObserverRunner.PARALLELISM, c.parallelism)
        assertEquals(300, c.pendingSeconds)
        assertEquals(listOf("wss://search.brainstorm.world"), c.indexRelays)
        assertEquals(Paths.get(FernetKeyFile.DEFAULT_PATH), c.keysFile)
        assertEquals(ObserverRunner.PRINT_TIMEOUT_SECONDS + 60, c.graceSeconds)
    }

    @Test
    fun theEnvironment() {
        val c =
            ServiceConfig.fromEnv(
                mapOf(
                    "PORT" to "9090",
                    "PARALLELISM" to "2",
                    "PENDING_SECONDS" to "60",
                    "INDEX_RELAYS" to " wss://a.example , wss://b.example ,",
                    "NSEC_KEYS_FILE" to "/keys/file",
                    "GRACE_SECONDS" to "10",
                ),
            )
        assertEquals(9090, c.port)
        assertEquals(2, c.parallelism)
        assertEquals(60, c.pendingSeconds)
        assertEquals(listOf("wss://a.example", "wss://b.example"), c.indexRelays)
        assertEquals(Paths.get("/keys/file"), c.keysFile)
        assertEquals(10, c.graceSeconds)
    }

    @Test
    fun aSettingOutOfItsRangeStopsTheStartRatherThanBeingGuessed() {
        for ((name, value) in listOf(
            "PORT" to "0",
            "PORT" to "http",
            "PARALLELISM" to "0",
            "PENDING_SECONDS" to "5",
            "GRACE_SECONDS" to "-1",
        )) {
            assertFailsWith<IllegalArgumentException>("$name=$value") { ServiceConfig.fromEnv(mapOf(name to value)) }
        }
    }
}
