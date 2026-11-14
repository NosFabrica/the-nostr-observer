package com.nosfabrica.observer.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The trigger contract's vectors (copied from assistant-runner's `contract/v1/vectors/` into the
 * test resources): every vector parses as its name says, with this service's own [WakeRequest].
 */
class ContractTest {
    private val dir = File(assertNotNull(javaClass.getResource("/contract/v1/vectors/wake-observer.json")).toURI()).parentFile
    private val vectors = dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }

    @Test
    fun everyVectorIsCovered() {
        assertTrue(vectors.isNotEmpty(), "no vectors in $dir")
        vectors.forEach { assertTrue(it.name.startsWith("wake-") || it.name.startsWith("answer-"), it.name) }
    }

    @Test
    fun aWakeParsesAndIsWrittenBackTheSame() {
        for (f in vectors.filter { it.name.startsWith("wake-") && !it.name.startsWith("wake-invalid-") }) {
            val text = f.readText()
            val wake = assertIs<WakeRequest.Parsed.Valid>(WakeRequest.parse(text), f.name).request
            assertEquals(Json.parseToJsonElement(text), wake.toJson(), f.name)
        }
    }

    @Test
    fun anInvalidWakeIsRefusedForTheReasonItNames() {
        val invalid = vectors.filter { it.name.startsWith("wake-invalid-") }
        assertTrue(invalid.isNotEmpty())
        for (f in invalid) {
            val text = f.readText()
            val why =
                Json
                    .parseToJsonElement(text)
                    .jsonObject["why"]!!
                    .jsonPrimitive.content
            assertEquals(why, assertIs<WakeRequest.Parsed.Invalid>(WakeRequest.parse(text), f.name).why, f.name)
        }
    }

    @Test
    fun anAnswerParses() {
        for (f in vectors.filter { it.name.startsWith("answer-") }) {
            val text = f.readText()
            val answer = assertNotNull(SettledAnswer.parse(text), f.name)
            assertEquals(Json.parseToJsonElement(text), answer.toJson())
        }
    }

    @Test
    fun malformedBodiesAreRefused() {
        val owner = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
        listOf(
            "[]" to WakeRequest.NOT_AN_OBJECT,
            "not json" to WakeRequest.NOT_AN_OBJECT,
            """{"v":"1","task":"observer","owner":"$owner","occurrence":1}""" to WakeRequest.VERSION_RULE,
            """{"v":1,"task":"Observer","owner":"$owner","occurrence":1}""" to WakeRequest.TASK_RULE,
            """{"v":1,"task":"observer","owner":"${owner.uppercase()}","occurrence":1}""" to WakeRequest.OWNER_RULE,
            """{"v":1,"task":"observer","owner":"$owner","occurrence":1.5}""" to WakeRequest.OCCURRENCE_RULE,
            """{"v":1,"task":"observer","owner":"$owner","occurrence":"1"}""" to WakeRequest.OCCURRENCE_RULE,
            """{"v":1,"task":"observer","owner":"$owner","occurrence":-1}""" to WakeRequest.OCCURRENCE_RULE,
        ).forEach { (body, why) -> assertEquals(why, assertIs<WakeRequest.Parsed.Invalid>(WakeRequest.parse(body), body).why) }
        assertNull(SettledAnswer.parse("""{"outcome":"not an enum, maybe an echo"}"""))
        assertNull(SettledAnswer.parse("""{"outcome":1}"""))
    }
}
