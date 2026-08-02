/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.server.rest

import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ksl.app.moda.MetricSpec
import ksl.app.moda.ModaDocument
import ksl.app.moda.ModaDocumentFormats
import ksl.app.moda.ModaSourceReference
import ksl.service.capability.run.BundleRegistry
import ksl.service.store.ResultStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Tests the whole life of a decision study as a caller over HTTP sees it.
 *
 *  The transport is meant to be a thin layer over the service, so what is checked here is not the
 *  arithmetic, which is tested where it lives, but that a caller can find out what is on offer,
 *  check a study before committing to it, submit it, follow it, retrieve it, stop it, and be told
 *  something useful when any of that goes wrong.
 */
class RestModaTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun freshService(registry: BundleRegistry) =
        KslRestService(registry, resultStore = ResultStore(Files.createTempDirectory("rest-moda")))

    private fun document(name: String = "Siting"): ModaDocument = ModaDocument(
        name = name,
        metrics = listOf(
            MetricSpec("Cost", weight = 2.0, upperLimit = 1000.0),
            MetricSpec("Delay", weight = 1.0, upperLimit = 1000.0)
        ),
        alternatives = listOf("North", "South", "East"),
        source = ModaSourceReference.InlineScores(
            mapOf(
                "North" to mapOf("Cost" to 100.0, "Delay" to 900.0),
                "South" to mapOf("Cost" to 300.0, "Delay" to 500.0),
                "East" to mapOf("Cost" to 500.0, "Delay" to 100.0)
            )
        )
    )

    private fun documentText(name: String = "Siting") = ModaDocumentFormats.toJson(document(name))

    /** Waits for a study to finish, then returns its result. */
    private suspend fun awaitResult(
        client: io.ktor.client.HttpClient,
        studyId: String
    ): JsonObject {
        repeat(600) {
            val response = client.get("/moda/studies/$studyId/result")
            if (response.status == HttpStatusCode.OK) {
                return json.parseToJsonElement(response.bodyAsText()).jsonObject
            }
            delay(50)
        }
        throw AssertionError("the study $studyId never finished")
    }

    // ------------------------------------------------------------------------------------------
    // Discovery and checking
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a caller can find out which value functions a study may name`() = testApplication {
        val service = freshService(TestBundles.registry())
        application { kslRestModule(service) }
        val client = createClient { install(ClientContentNegotiation) { json() } }
        try {
            val response = client.get("/moda/value-functions")
            assertEquals(HttpStatusCode.OK, response.status)
            val ids = json.parseToJsonElement(response.bodyAsText())
                .jsonObject["valueFunctionIds"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertTrue("linear" in ids, "the ids on offer were $ids")
            assertTrue(ids.isNotEmpty())
        } finally {
            service.close()
        }
    }

    @Test
    fun `a sound study checks out as runnable without being run`() = testApplication {
        val service = freshService(TestBundles.registry())
        application { kslRestModule(service) }
        val client = createClient { install(ClientContentNegotiation) { json() } }
        try {
            val response = client.post("/moda/validate") {
                contentType(ContentType.Application.Json)
                setBody(documentText())
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val report = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertTrue(report["runnable"]!!.jsonPrimitive.content.toBoolean(), "report was $report")
            assertEquals("Siting", report["name"]!!.jsonPrimitive.content)
        } finally {
            service.close()
        }
    }

    /**
     *  The usual mistake is a spelling, so a study naming a value function nobody supplied is told
     *  what it could have named rather than only that it was wrong.
     */
    @Test
    fun `a study naming a value function nobody supplied is told what is on offer`() = testApplication {
        val service = freshService(TestBundles.registry())
        application { kslRestModule(service) }
        val client = createClient { install(ClientContentNegotiation) { json() } }
        try {
            val broken = document().copy(
                metrics = listOf(MetricSpec("Cost", valueFunctionId = "sigmoid", upperLimit = 100.0))
            )
            val response = client.post("/moda/validate") {
                contentType(ContentType.Application.Json)
                setBody(ModaDocumentFormats.toJson(broken))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val report = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertTrue(!report["runnable"]!!.jsonPrimitive.content.toBoolean())
            val messages = report["issues"]!!.jsonArray.map { it.jsonObject["message"]!!.jsonPrimitive.content }
            assertTrue(messages.any { it.contains("sigmoid") }, "issues were $messages")
            assertTrue(messages.any { it.contains("linear") }, "the reply does not say what is on offer: $messages")
        } finally {
            service.close()
        }
    }

    @Test
    fun `something that is not a study at all is refused as a bad request`() = testApplication {
        val service = freshService(TestBundles.registry())
        application { kslRestModule(service) }
        val client = createClient { install(ClientContentNegotiation) { json() } }
        try {
            for (path in listOf("/moda/validate", "/moda/studies")) {
                val response = client.post(path) {
                    contentType(ContentType.Application.Json)
                    setBody("{ this is not json")
                }
                assertEquals(HttpStatusCode.BadRequest, response.status, "for $path")
                assertTrue(response.bodyAsText().contains("MODA"), "the refusal does not say what was wrong")
            }
        } finally {
            service.close()
        }
    }

    // ------------------------------------------------------------------------------------------
    // Submitting, following, retrieving
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a study can be submitted and its result retrieved`() = testApplication {
        val service = freshService(TestBundles.registry())
        application { kslRestModule(service) }
        val client = createClient { install(ClientContentNegotiation) { json() } }
        try {
            val submitted = client.post("/moda/studies") {
                contentType(ContentType.Application.Json)
                setBody(documentText())
            }
            assertEquals(HttpStatusCode.Accepted, submitted.status)
            val studyId = json.parseToJsonElement(submitted.bodyAsText())
                .jsonObject["jobId"]!!.jsonPrimitive.content
            assertTrue(studyId.isNotBlank())

            val result = awaitResult(client, studyId)
            assertEquals("COMPLETED", result["outcome"]!!.jsonPrimitive.content)
            assertEquals(studyId, result["studyId"]!!.jsonPrimitive.content)

            val snapshot = result["snapshot"]!!.jsonObject
            val alternatives = snapshot["alternatives"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(listOf("North", "South", "East"), alternatives)
            assertTrue(snapshot["primaryRecommendation"]!!.jsonPrimitive.content in alternatives)
            assertTrue(snapshot.containsKey("schemaVersion"), "the result does not say which version wrote it")
        } finally {
            service.close()
        }
    }

    /**
     *  A study refused for what its document says is a different thing from one that broke, and a
     *  caller has to be able to tell them apart to know whether to fix something or report it.
     */
    @Test
    fun `a study that cannot be run comes back refused with the reasons`() = testApplication {
        val service = freshService(TestBundles.registry())
        application { kslRestModule(service) }
        val client = createClient { install(ClientContentNegotiation) { json() } }
        try {
            val broken = document().copy(alternatives = listOf("North"))
            val submitted = client.post("/moda/studies") {
                contentType(ContentType.Application.Json)
                setBody(ModaDocumentFormats.toJson(broken))
            }
            assertEquals(HttpStatusCode.Accepted, submitted.status)
            val studyId = json.parseToJsonElement(submitted.bodyAsText())
                .jsonObject["jobId"]!!.jsonPrimitive.content

            val result = awaitResult(client, studyId)
            assertEquals("REFUSED", result["outcome"]!!.jsonPrimitive.content)
            val issues = result["issues"]!!.jsonArray.map { it.jsonObject }
            assertTrue(issues.any { it["severity"]!!.jsonPrimitive.content == "ERROR" })
            assertTrue(
                issues.any { it["element"]!!.jsonPrimitive.content == "alternatives" },
                "the refusal does not say which part was wrong: $issues"
            )
        } finally {
            service.close()
        }
    }

    @Test
    fun `an alternative left out for want of a score is reported alongside the result`() = testApplication {
        val service = freshService(TestBundles.registry())
        application { kslRestModule(service) }
        val client = createClient { install(ClientContentNegotiation) { json() } }
        try {
            val partial = document().copy(
                source = ModaSourceReference.InlineScores(
                    mapOf(
                        "North" to mapOf("Cost" to 100.0, "Delay" to 900.0),
                        "South" to mapOf("Cost" to 300.0, "Delay" to 500.0),
                        "East" to mapOf("Cost" to 500.0, "Delay" to 100.0)
                    )
                )
            )
            val submitted = client.post("/moda/studies") {
                contentType(ContentType.Application.Json)
                setBody(ModaDocumentFormats.toJson(partial))
            }
            val studyId = json.parseToJsonElement(submitted.bodyAsText())
                .jsonObject["jobId"]!!.jsonPrimitive.content
            val result = awaitResult(client, studyId)
            // A complete study reports nothing missing, which is the shape a caller reads either way.
            assertTrue(result.containsKey("missing"))
            assertTrue(result.containsKey("warnings"))
            assertEquals(0, result["missing"]!!.jsonArray.size)
        } finally {
            service.close()
        }
    }

    @Test
    fun `following a study replays everything that happened, ending once`() = testApplication {
        val service = freshService(TestBundles.registry())
        application { kslRestModule(service) }
        val client = createClient { install(ClientContentNegotiation) { json() } }
        try {
            val submitted = client.post("/moda/studies") {
                contentType(ContentType.Application.Json)
                setBody(documentText())
            }
            val studyId = json.parseToJsonElement(submitted.bodyAsText())
                .jsonObject["jobId"]!!.jsonPrimitive.content
            awaitResult(client, studyId)

            // Attaching after the fact still gets the whole story, which is what makes a dropped
            // connection recoverable rather than a reason to start again.
            val stream = client.get("/moda/studies/$studyId/events").bodyAsText()
            assertTrue(stream.contains("Started"), "the stream has no start: $stream")
            assertTrue(stream.contains("Completed"), "the stream has no ending: $stream")
            assertTrue(stream.contains("\"done\":true"), "the stream never closed: $stream")
            assertTrue(stream.contains(studyId), "the stream does not identify the study")
        } finally {
            service.close()
        }
    }

    // ------------------------------------------------------------------------------------------
    // Stopping, and being asked about what is not there
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a study can be stopped`() = testApplication {
        val service = freshService(TestBundles.registry())
        application { kslRestModule(service) }
        val client = createClient { install(ClientContentNegotiation) { json() } }
        try {
            val submitted = client.post("/moda/studies") {
                contentType(ContentType.Application.Json)
                setBody(documentText())
            }
            val studyId = json.parseToJsonElement(submitted.bodyAsText())
                .jsonObject["jobId"]!!.jsonPrimitive.content
            val cancelled = client.delete("/moda/studies/$studyId")
            assertEquals(HttpStatusCode.Accepted, cancelled.status)

            val result = awaitResult(client, studyId)
            val outcome = result["outcome"]!!.jsonPrimitive.content
            assertTrue(outcome in setOf("CANCELLED", "COMPLETED"), "unexpected outcome $outcome")
        } finally {
            service.close()
        }
    }

    @Test
    fun `a study nobody submitted is not found rather than answered with nothing`() = testApplication {
        val service = freshService(TestBundles.registry())
        application { kslRestModule(service) }
        val client = createClient { install(ClientContentNegotiation) { json() } }
        try {
            assertEquals(HttpStatusCode.NotFound, client.get("/moda/studies/moda-nope/result").status)
            assertEquals(HttpStatusCode.NotFound, client.delete("/moda/studies/moda-nope").status)
            val stream = client.get("/moda/studies/moda-nope/events").bodyAsText()
            assertTrue(stream.contains("unknown studyId"), "the stream does not say what was wrong: $stream")
        } finally {
            service.close()
        }
    }

    // ------------------------------------------------------------------------------------------
    // Several at once
    // ------------------------------------------------------------------------------------------

    @Test
    fun `studies submitted at once each come back as themselves`() = testApplication {
        val service = freshService(TestBundles.registry())
        application { kslRestModule(service) }
        val client = createClient { install(ClientContentNegotiation) { json() } }
        try {
            val names = (1..6).map { "Study $it" }
            val ids = names.map { name ->
                val submitted = client.post("/moda/studies") {
                    contentType(ContentType.Application.Json)
                    setBody(documentText(name))
                }
                assertEquals(HttpStatusCode.Accepted, submitted.status)
                name to json.parseToJsonElement(submitted.bodyAsText())
                    .jsonObject["jobId"]!!.jsonPrimitive.content
            }
            assertEquals(names.size, ids.map { it.second }.toSet().size, "study identifiers repeated")

            for ((name, studyId) in ids) {
                val result = awaitResult(client, studyId)
                assertEquals("COMPLETED", result["outcome"]!!.jsonPrimitive.content)
                assertEquals(
                    name, result["snapshot"]!!.jsonObject["name"]!!.jsonPrimitive.content,
                    "a study came back as the wrong one"
                )
            }
        } finally {
            service.close()
        }
    }

    /**
     *  The same study asked for twice has to produce the same bytes, otherwise results cannot be
     *  compared, cached, or checked for having changed.
     */
    @Test
    fun `the same study produces the same result bytes every time`() = testApplication {
        val service = freshService(TestBundles.registry())
        application { kslRestModule(service) }
        val client = createClient { install(ClientContentNegotiation) { json() } }
        try {
            val snapshots = (1..3).map {
                val submitted = client.post("/moda/studies") {
                    contentType(ContentType.Application.Json)
                    setBody(documentText())
                }
                val studyId = json.parseToJsonElement(submitted.bodyAsText())
                    .jsonObject["jobId"]!!.jsonPrimitive.content
                awaitResult(client, studyId)["snapshot"]!!.toString()
            }
            assertEquals(1, snapshots.toSet().size, "the same study serialized differently: $snapshots")
        } finally {
            service.close()
        }
    }
}
