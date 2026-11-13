package com.nosfabrica.observer.service.keys

import com.nosfabrica.observer.service.testing.capturingLogs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FernetKeyFileTest {
    private val vectors = Json.parseToJsonElement(javaClass.getResource("/fernet/vectors.json")!!.readText()).jsonObject
    private val k1 = vectors["keys"]!!.jsonArray[0].jsonPrimitive.content
    private val k2 = vectors["keys"]!!.jsonArray[1].jsonPrimitive.content
    private val tokenK1 = vectors["token_k1"]!!.jsonPrimitive.content
    private val tokenK2 = vectors["token_k2"]!!.jsonPrimitive.content

    private val dir = Files.createTempDirectory("keys")
    private val path = dir.resolve("nsec_encryption_keys")

    @Test
    fun aRotationIsSeenWhenTheFileChanges() {
        Files.writeString(path, k1)
        val file = FernetKeyFile(path)
        assertNotNull(file.decrypt(tokenK1))
        assertNull(file.decrypt(tokenK2))
        // The server's rotation: [new, old], then the rows are re-encrypted with the new key.
        Files.writeString(path, "$k2,$k1")
        Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis() + 5_000))
        assertNotNull(file.decrypt(tokenK2))
        assertNotNull(file.decrypt(tokenK1))
        assertEquals(2, file.keyCount())
    }

    @Test
    fun aTokenNoKeyOpensMakesItReadTheFileOnceMoreEvenWithTheSameTime() {
        Files.writeString(path, k1)
        val time = Files.getLastModifiedTime(path)
        val file = FernetKeyFile(path)
        assertNotNull(file.decrypt(tokenK1))
        // Rewritten within the file system's time granularity: the time alone wouldn't say.
        Files.writeString(path, "$k2,$k1")
        Files.setLastModifiedTime(path, time)
        assertNotNull(file.decrypt(tokenK2))
    }

    @Test
    fun aMissingFileIsNoKeysUntilItAppears() {
        val file = FernetKeyFile(path)
        assertNull(file.decrypt(tokenK1))
        assertEquals(0, file.keyCount())
        Files.writeString(path, k1)
        assertNotNull(file.decrypt(tokenK1))
    }

    @Test
    fun aKeyThatIsNotOneIsSkippedAndNeverLogged() =
        runTest {
            val notAKey = "c2VjcmV0LWJ1dC1ub3QtYS1mZXJuZXQta2V5"
            Files.writeString(path, "$notAKey,$k1")
            val (opened, logs) = capturingLogs { FernetKeyFile(path).decrypt(tokenK1) }
            assertNotNull(opened)
            for (secret in listOf(notAKey, k1, tokenK1)) assertFalse(secret in logs, logs)
        }
}
