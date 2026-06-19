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

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ksl.app.config.optimization.OptimizationInputSpec
import ksl.service.capability.run.BundleInfo
import ksl.service.capability.run.BundleRegistry
import ksl.service.capability.run.ExperimentFactorSpec
import ksl.service.config.BuildInfo
import ksl.service.store.ResultStore
import ksl.utilities.random.rvariable.ExponentialRV
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives the REST/SSE surface end to end with Ktor's in-memory test host:
 * discovery, the run lifecycle (submit → SSE events → result), and a fit — all
 * against the real MM1 example bundle.
 */
class KslRestAppTest {

    // A service over a fresh temp-dir ResultStore so the persistent ~/.ksl cache
    // never leaks a hit between test runs (which would, e.g., starve the SSE path
    // of a live job). Each test gets an empty store → deterministic cache misses.
    private fun freshService(registry: BundleRegistry) =
        KslRestService(registry, resultStore = ResultStore(Files.createTempDirectory("rest-rs")))

    @Test
    fun `health is UP and ready reflects the readiness probe`() = testApplication {
        val registry = BundleRegistry.fromClasspath()
        val service = freshService(registry)
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        application { kslRestModule(service, ready = ready::get) }
        val client = createClient { install(ClientContentNegotiation) { json() } }
        try {
            val health = client.get("/health")
            assertEquals(HttpStatusCode.OK, health.status)
            assertTrue("\"status\":\"UP\"" in health.bodyAsText().replace(" ", ""), "health should report UP")

            // Not ready yet -> 503.
            assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/ready").status)
            // Becomes ready -> 200.
            ready.set(true)
            val readyResp = client.get("/ready")
            assertEquals(HttpStatusCode.OK, readyResp.status)
            assertTrue("\"ready\":true" in readyResp.bodyAsText().replace(" ", ""))

            // /version reports the service's build version (A7). Running from
            // classes (not a packaged jar), BuildInfo.version falls back to "dev".
            val version = client.get("/version")
            assertEquals(HttpStatusCode.OK, version.status)
            val versionBody = version.bodyAsText().replace(" ", "")
            assertTrue("\"version\":\"${BuildInfo.version}\"" in versionBody, "version: $versionBody")
            assertTrue("\"service\":\"ksl-rest\"" in versionBody, "version should name the service")
        } finally {
            service.close()
            registry.close()
        }
    }

    @Test
    fun `bearer token gate rejects without a valid token and allows with it`() = testApplication {
        val registry = BundleRegistry.fromClasspath()
        val service = freshService(registry)
        val token = "lab-key-123"
        application { kslRestModule(service, ready = { true }, authToken = token) }
        val client = createClient { install(ClientContentNegotiation) { json() } }
        try {
            // Probe paths stay public even with auth on.
            assertEquals(HttpStatusCode.OK, client.get("/health").status, "/health stays public")

            // A capability route requires the token.
            assertEquals(HttpStatusCode.Unauthorized, client.get("/bundles").status, "no token -> 401")
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/bundles") { header("Authorization", "Bearer wrong") }.status,
                "wrong token -> 401",
            )
            assertEquals(
                HttpStatusCode.OK,
                client.get("/bundles") { header("Authorization", "Bearer $token") }.status,
                "correct token -> 200",
            )
        } finally {
            service.close()
            registry.close()
        }
    }

    @Test
    fun `rest surface - discovery, run lifecycle, and fit`() = testApplication {
        val registry = BundleRegistry.fromClasspath()
        val service = freshService(registry)
        application { kslRestModule(service) }
        val client = createClient { install(ClientContentNegotiation) { json() } }

        try {
            // ----- discovery -----
            val bundles: List<BundleInfo> = client.get("/bundles").body()
            assertTrue(bundles.any { it.bundleId == "ksl.examples.mm1" }, "expected MM1 bundle")

            val models: List<String> = client.get("/bundles/ksl.examples.mm1/models").body()
            assertTrue("MM1" in models)

            val descriptor = client.get("/bundles/ksl.examples.mm1/models/MM1")
            assertEquals(HttpStatusCode.OK, descriptor.status)
            assertTrue("inputSchema" in descriptor.bodyAsText())

            // ----- run lifecycle -----
            val accepted: JobAccepted = client.post("/runs") {
                contentType(ContentType.Application.Json)
                setBody(RunRequest(bundleId = "ksl.examples.mm1", modelId = "MM1", numberOfReplications = 3))
            }.body()

            // Poll the result to terminal (the run executes on the service's own
            // dispatcher).
            var resultBody: String? = null
            repeat(200) {
                val response = client.get("/runs/${accepted.jobId}/result")
                if (response.status == HttpStatusCode.OK) {
                    resultBody = response.bodyAsText()
                    return@repeat
                }
                delay(25)
            }
            assertTrue(resultBody != null, "run did not finish in time")
            assertTrue("completed" in resultBody!!, "expected a completed result: $resultBody")

            // SSE events: the journal replays from offset 0, so even now (after
            // completion) the stream yields the full event history then closes.
            val eventStream = client.get("/runs/${accepted.jobId}/events").bodyAsText()
            assertTrue("run-event" in eventStream, "expected SSE run-event frames: $eventStream")

            // ----- fit -----
            val sample = ExponentialRV(10.0).sample(200).toList()
            val fit = client.post("/fits") {
                contentType(ContentType.Application.Json)
                setBody(FitRequest(data = sample, name = "svc", kind = "CONTINUOUS"))
            }
            assertEquals(HttpStatusCode.OK, fit.status)
            assertTrue("datasetName" in fit.bodyAsText())
        } finally {
            service.close()
            registry.close()
        }
    }

    @Test
    fun `optimization runs as a job and its result is fetched via runs endpoint`() = testApplication {
        val registry = BundleRegistry.fromClasspath()
        val service = freshService(registry)
        application { kslRestModule(service) }
        val client = createClient { install(ClientContentNegotiation) { json() } }
        try {
            val descriptor = service.describe("ksl.examples.mm1", "MM1")!!
            // Pull a numeric control key and an objective response from the descriptor JSON.
            val controlKey = descriptor["inputSchema"]!!.jsonObject["properties"]!!.jsonObject.keys.first()
            val objective = descriptor["outputSchema"]!!.jsonObject["properties"]!!.jsonObject.keys.first()

            val accepted: JobAccepted = client.post("/optimizations") {
                contentType(ContentType.Application.Json)
                setBody(
                    OptimizationRequest(
                        bundleId = "ksl.examples.mm1",
                        modelId = "MM1",
                        objectiveResponse = objective,
                        inputs = listOf(OptimizationInputSpec(controlKey, 1.0, 3.0, 1.0)),
                        maxIterations = 3,
                        replicationsPerEvaluation = 2,
                    ),
                )
            }.body()

            var body: String? = null
            repeat(400) {
                val response = client.get("/runs/${accepted.jobId}/result")
                if (response.status == HttpStatusCode.OK) {
                    body = response.bodyAsText()
                    return@repeat
                }
                delay(25)
            }
            assertTrue(body != null, "optimization did not finish in time")
            assertTrue("optimization" in body!!, "expected an optimization result: $body")
        } finally {
            service.close()
            registry.close()
        }
    }

    @Test
    fun `experiment runs as a job and its batch result is fetched via runs endpoint`() = testApplication {
        val registry = BundleRegistry.fromClasspath()
        val service = freshService(registry)
        application { kslRestModule(service) }
        val client = createClient { install(ClientContentNegotiation) { json() } }
        try {
            // Find a model with >= 2 numeric controls.
            val target = registry.listBundles().firstNotNullOfOrNull { bundle ->
                bundle.modelIds.firstNotNullOfOrNull { modelId ->
                    val descriptor = runCatching { registry.describeModel(bundle.bundleId, modelId) }.getOrNull()
                    if (descriptor != null && descriptor.controls.numericControls.size >= 2) {
                        Triple(bundle.bundleId, modelId, descriptor)
                    } else {
                        null
                    }
                }
            }
            assertTrue(target != null, "expected a model with >= 2 numeric controls")
            val (bundleId, modelId, descriptor) = target
            val factors = descriptor.controls.numericControls.take(2).map { control ->
                ExperimentFactorSpec(control.keyName, control.keyName, control.value, control.value + 1.0)
            }

            val accepted: JobAccepted = client.post("/experiments") {
                contentType(ContentType.Application.Json)
                setBody(ExperimentRequest(bundleId, modelId, factors, numRepsPerDesignPoint = 2))
            }.body()

            var body: String? = null
            repeat(600) {
                val response = client.get("/runs/${accepted.jobId}/result")
                if (response.status == HttpStatusCode.OK) {
                    body = response.bodyAsText()
                    return@repeat
                }
                delay(25)
            }
            assertTrue(body != null, "experiment did not finish in time")
            assertTrue("batch" in body!!, "expected a batch result: $body")
        } finally {
            service.close()
            registry.close()
        }
    }

    @Test
    fun `POST runs applies inputs and rejects unknown input keys`() = testApplication {
        val registry = BundleRegistry.fromClasspath()
        val service = freshService(registry)
        application { kslRestModule(service) }
        val client = createClient { install(ClientContentNegotiation) { json() } }
        try {
            val controlKey = service.describe("ksl.examples.mm1", "MM1")!!["inputSchema"]!!
                .jsonObject["properties"]!!.jsonObject.keys.first()

            val accepted: JobAccepted = client.post("/runs") {
                contentType(ContentType.Application.Json)
                setBody(
                    RunRequest(
                        bundleId = "ksl.examples.mm1", modelId = "MM1",
                        numberOfReplications = 3, inputs = mapOf(controlKey to 2.0),
                    ),
                )
            }.body()
            var ok = false
            repeat(200) {
                if (client.get("/runs/${accepted.jobId}/result").status == HttpStatusCode.OK) { ok = true; return@repeat }
                delay(25)
            }
            assertTrue(ok, "run with inputs did not complete")

            val bad = client.post("/runs") {
                contentType(ContentType.Application.Json)
                setBody(RunRequest("ksl.examples.mm1", "MM1", inputs = mapOf("not.a.real.input" to 1.0)))
            }
            assertEquals(HttpStatusCode.BadRequest, bad.status)
        } finally {
            service.close()
            registry.close()
        }
    }

    @Test
    fun `POST run-configs runs an authored document and rejects an invalid one`() = testApplication {
        val registry = BundleRegistry.fromClasspath()
        val service = freshService(registry)
        application { kslRestModule(service) }
        val client = createClient { install(ClientContentNegotiation) { json() } }
        try {
            val document = ksl.app.config.RunConfiguration(
                scenarios = listOf(
                    ksl.app.config.ScenarioSpec(
                        name = "doc",
                        modelReference = ksl.app.config.ModelReference.ByProviderId("MM1"),
                        runOverrides = ksl.app.config.ExperimentRunOverrides(numberOfReplications = 3),
                    ),
                ),
                outputConfig = ksl.app.config.OutputConfig(reports = emptySet()),
            )
            val accepted: JobAccepted = client.post("/run-configs") {
                contentType(ContentType.Application.Json)
                setBody(ksl.app.config.RunConfigurationJson.encode(document))
            }.body()

            var ok = false
            repeat(200) {
                if (client.get("/runs/${accepted.jobId}/result").status == HttpStatusCode.OK) { ok = true; return@repeat }
                delay(25)
            }
            assertTrue(ok, "document run did not complete")

            // A document with an unknown model → 400 from validation.
            val bad = ksl.app.config.RunConfiguration(
                scenarios = listOf(
                    ksl.app.config.ScenarioSpec("x", ksl.app.config.ModelReference.ByProviderId("NoSuchModel")),
                ),
            )
            val badResponse = client.post("/run-configs") {
                contentType(ContentType.Application.Json)
                setBody(ksl.app.config.RunConfigurationJson.encode(bad))
            }
            assertEquals(HttpStatusCode.BadRequest, badResponse.status)
        } finally {
            service.close()
            registry.close()
        }
    }

    @Test
    fun `POST runs replicationSet selects an independent realization`() = testApplication {
        val registry = BundleRegistry.fromClasspath()
        val service = freshService(registry)
        application { kslRestModule(service) }
        val client = createClient { install(ClientContentNegotiation) { json() } }
        try {
            suspend fun submit(set: Int?): JobAccepted = client.post("/runs") {
                contentType(ContentType.Application.Json)
                setBody(RunRequest(bundleId = "ksl.examples.mm1", modelId = "MM1", numberOfReplications = 4, replicationSet = set))
            }.body()

            val canonical = submit(null)
            val set0 = submit(0)
            val set1 = submit(1)

            // set 0 == the standard run (byte-identical config → same resultId); set 1 is distinct.
            assertEquals(canonical.resultId, set0.resultId, "replicationSet=0 must equal the standard run")
            assertTrue(set1.resultId.isNotEmpty() && set1.resultId != canonical.resultId, "an independent set is a distinct result")

            // A negative replicationSet is a 400.
            val bad = client.post("/runs") {
                contentType(ContentType.Application.Json)
                setBody(RunRequest(bundleId = "ksl.examples.mm1", modelId = "MM1", replicationSet = -1))
            }
            assertEquals(HttpStatusCode.BadRequest, bad.status)
        } finally {
            service.close()
            registry.close()
        }
    }

    @Test
    fun `document endpoints accept a TOML body, not only JSON`() = testApplication {
        val registry = BundleRegistry.fromClasspath()
        val service = freshService(registry)
        application { kslRestModule(service) }
        val client = createClient { install(ClientContentNegotiation) { json() } }
        try {
            val document = ksl.app.config.RunConfiguration(
                scenarios = listOf(
                    ksl.app.config.ScenarioSpec(
                        name = "doc",
                        modelReference = ksl.app.config.ModelReference.ByProviderId("MM1"),
                        runOverrides = ksl.app.config.ExperimentRunOverrides(numberOfReplications = 3),
                    ),
                ),
                outputConfig = ksl.app.config.OutputConfig(reports = emptySet()),
            )
            // A TOML document (as a desktop app would save it) validates over the wire.
            val tomlText = ksl.app.config.RunConfigurationToml.encode(document)
            val report: ValidationReport = client.post("/validate/run") {
                contentType(ContentType.Application.Json); setBody(tomlText)
            }.body()
            assertTrue(report.valid, "the TOML document should validate: ${report.errors}")

            // And the same TOML document runs through /run-configs.
            val accepted: JobAccepted = client.post("/run-configs") {
                contentType(ContentType.Application.Json); setBody(tomlText)
            }.body()
            var ok = false
            repeat(200) {
                if (client.get("/runs/${accepted.jobId}/result").status == HttpStatusCode.OK) { ok = true; return@repeat }
                delay(25)
            }
            assertTrue(ok, "the TOML document run did not complete")
        } finally {
            service.close()
            registry.close()
        }
    }

    @Test
    fun `authoring - template, recipes, and validation`() = testApplication {
        val registry = BundleRegistry.fromClasspath()
        val service = freshService(registry)
        application { kslRestModule(service) }
        val client = createClient { install(ClientContentNegotiation) { json() } }
        try {
            // describe now exposes supportedApps.
            val describe = service.describe("ksl.examples.mm1", "MM1")!!
            assertTrue("SIMOPT" in describe["supportedApps"].toString(), "describe should list supportedApps")
            // template is a runnable RunConfiguration document.
            val template = client.get("/bundles/ksl.examples.mm1/models/MM1/template")
            assertEquals(HttpStatusCode.OK, template.status)
            val templateText = template.bodyAsText()
            assertTrue("scenarios" in templateText, "template should be a RunConfiguration document")
            // validate accepts the template and flags a bad document.
            val goodReport = client.post("/validate/run") {
                contentType(ContentType.Application.Json)
                setBody(templateText)
            }
            assertEquals(HttpStatusCode.OK, goodReport.status)
            assertTrue("\"valid\":true" in goodReport.bodyAsText().replace(" ", ""))

            val bad = ksl.app.config.RunConfigurationJson.encode(
                ksl.app.config.RunConfiguration(
                    scenarios = listOf(
                        ksl.app.config.ScenarioSpec("x", ksl.app.config.ModelReference.ByProviderId("Nope")),
                    ),
                ),
            )
            val badReport = client.post("/validate/run") {
                contentType(ContentType.Application.Json)
                setBody(bad)
            }
            assertEquals(HttpStatusCode.OK, badReport.status)
            assertTrue("\"valid\":false" in badReport.bodyAsText().replace(" ", ""))

            // recipes endpoint works (empty for MM1).
            assertEquals(HttpStatusCode.OK, client.get("/bundles/ksl.examples.mm1/models/MM1/recipes").status)
        } finally {
            service.close()
            registry.close()
        }
    }

    @Test
    fun `unknown model on POST runs is a 400`() = testApplication {
        val registry = BundleRegistry.fromClasspath()
        val service = freshService(registry)
        application { kslRestModule(service) }
        val client = createClient { install(ClientContentNegotiation) { json() } }
        try {
            val response = client.post("/runs") {
                contentType(ContentType.Application.Json)
                setBody(RunRequest(bundleId = "ksl.examples.mm1", modelId = "Nope"))
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        } finally {
            service.close()
            registry.close()
        }
    }

    @Test
    fun `experiment document flow - template, validate, submit, and projection`() = testApplication {
        val registry = BundleRegistry.fromClasspath()
        val service = freshService(registry)
        application { kslRestModule(service) }
        val client = createClient { install(ClientContentNegotiation) { json() } }
        try {
            // Find a model with >= 2 numeric controls.
            val target = registry.listBundles().firstNotNullOfOrNull { bundle ->
                bundle.modelIds.firstNotNullOfOrNull { modelId ->
                    val descriptor = runCatching { registry.describeModel(bundle.bundleId, modelId) }.getOrNull()
                    if (descriptor != null && descriptor.controls.numericControls.size >= 2) Pair(bundle.bundleId, modelId) else null
                }
            }
            assertTrue(target != null, "expected a model with >= 2 numeric controls")
            val (bundleId, modelId) = target

            // template → a runnable ExperimentConfiguration scaffold.
            val template = client.get("/bundles/$bundleId/models/$modelId/experiment-template")
            assertEquals(HttpStatusCode.OK, template.status)
            val documentText = template.bodyAsText()
            assertTrue("factors" in documentText && "designSpec" in documentText, "template should be an ExperimentConfiguration")

            // validate accepts it.
            val report = client.post("/validate/experiment") {
                contentType(ContentType.Application.Json); setBody(documentText)
            }
            assertEquals(HttpStatusCode.OK, report.status)
            assertTrue("\"valid\":true" in report.bodyAsText().replace(" ", ""))

            // submit → job-shaped; poll the result to a batch, then project a design point.
            val accepted: JobAccepted = client.post("/experiment-configs") {
                contentType(ContentType.Application.Json); setBody(documentText)
            }.body()
            var body: String? = null
            repeat(600) {
                val response = client.get("/runs/${accepted.jobId}/result")
                if (response.status == HttpStatusCode.OK) { body = response.bodyAsText(); return@repeat }
                delay(25)
            }
            assertTrue(body != null && "batch" in body!!, "expected a batch result: $body")
            val point = client.get("/results/${accepted.resultId}/design-points/0")
            assertEquals(HttpStatusCode.OK, point.status)
        } finally {
            service.close()
            registry.close()
        }
    }

    @Test
    fun `fit document flow - template, validate, submit, and projection`() = testApplication {
        val registry = BundleRegistry.fromClasspath()
        val service = freshService(registry)
        application { kslRestModule(service) }
        val client = createClient { install(ClientContentNegotiation) { json() } }
        try {
            // template is a FitConfiguration scaffold.
            val template = client.get("/fit-template?kind=CONTINUOUS")
            assertEquals(HttpStatusCode.OK, template.status)
            assertTrue("dataSource" in template.bodyAsText(), "template should be a FitConfiguration")

            // Author a real document with the canonical codec (avoids guessing the discriminator).
            val sample = ExponentialRV(10.0).sample(200)
            val config = ksl.app.dist.config.FitConfiguration(
                dataSource = ksl.app.dist.config.DataSourceReference.Inline(mapOf("svc" to sample)),
                kind = ksl.app.dist.config.DistributionKind.CONTINUOUS,
                estimatorIds = ksl.app.dist.catalog.FittingCatalog.defaultEstimatorIds(ksl.app.dist.config.DistributionKind.CONTINUOUS),
                scoringModelIds = ksl.app.dist.catalog.FittingCatalog.defaultScoringModelIds(),
            )
            val documentText = ksl.service.capability.fit.FitDocuments.encode(config)

            // validate accepts it.
            val report = client.post("/validate/fit") {
                contentType(ContentType.Application.Json); setBody(documentText)
            }
            assertEquals(HttpStatusCode.OK, report.status)
            assertTrue("\"valid\":true" in report.bodyAsText().replace(" ", ""))

            // submit returns a card with a resultId; the full ranked fits are projectable.
            val submit = client.post("/fit-configs") {
                contentType(ContentType.Application.Json); setBody(documentText)
            }
            assertEquals(HttpStatusCode.Created, submit.status)
            val card: FitCard = submit.body()
            assertTrue(card.fitCount > 0)
            val full = client.get("/results/${card.resultId}")
            assertEquals(HttpStatusCode.OK, full.status)
            assertTrue("\"fits\"" in full.bodyAsText().replace(" ", ""))

            // An identical document is served from the cache (200).
            val again = client.post("/fit-configs") {
                contentType(ContentType.Application.Json); setBody(documentText)
            }
            assertEquals(HttpStatusCode.OK, again.status)
            assertTrue(again.body<FitCard>().cached, "second identical fit should be a cache hit")

            // An invalid document (empty inline dataset) → 400.
            val badText = ksl.service.capability.fit.FitDocuments.encode(
                ksl.app.dist.config.FitConfiguration(
                    dataSource = ksl.app.dist.config.DataSourceReference.Inline(mapOf("empty" to DoubleArray(0))),
                    kind = ksl.app.dist.config.DistributionKind.CONTINUOUS,
                ),
            )
            val bad = client.post("/fit-configs") {
                contentType(ContentType.Application.Json); setBody(badText)
            }
            assertEquals(HttpStatusCode.BadRequest, bad.status)
        } finally {
            service.close()
            registry.close()
        }
    }

    @Test
    fun `preview experiment returns canonical document and design-point cost`() = testApplication {
        val registry = BundleRegistry.fromClasspath()
        val service = freshService(registry)
        application { kslRestModule(service) }
        val client = createClient { install(ClientContentNegotiation) { json() } }
        try {
            val config = ksl.app.config.experiment.ExperimentConfiguration(
                modelReference = ksl.app.config.ModelReference.ByProviderId("MM1"),
                factors = listOf(
                    ksl.app.config.experiment.FactorSpec("A", listOf(1.0, 2.0), ksl.app.config.experiment.ControlBinding.Control("A")),
                    ksl.app.config.experiment.FactorSpec("B", listOf(1.0, 2.0), ksl.app.config.experiment.ControlBinding.Control("B")),
                ),
                designSpec = ksl.app.config.experiment.DesignSpec.TwoLevelFactorial(),
                replications = ksl.app.config.experiment.ReplicationSpec.Uniform(10),
            )
            val documentText = ksl.service.capability.run.ExperimentDocuments.encode(config)
            val response = client.post("/preview/experiment") {
                contentType(ContentType.Application.Json); setBody(documentText)
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue("\"canonical\"" in body.replace(" ", ""), "canonical echo present: $body")
            val workload = Json.parseToJsonElement(body).jsonObject["workload"]!!.jsonObject
            assertEquals(4, workload["designPointCount"]!!.jsonPrimitive.content.toInt())

            // A malformed document → 400.
            val bad = client.post("/preview/experiment") {
                contentType(ContentType.Application.Json); setBody("{ not a document }")
            }
            assertEquals(HttpStatusCode.BadRequest, bad.status)
        } finally {
            service.close()
            registry.close()
        }
    }

    @Test
    fun `POST runs escalation runs only the top-up and combines on completion`() = testApplication {
        val registry = BundleRegistry.fromClasspath()
        val service = freshService(registry)
        application { kslRestModule(service) }
        val client = createClient { install(ClientContentNegotiation) { json() } }
        try {
            suspend fun submit(reps: Int): JobAccepted = client.post("/runs") {
                contentType(ContentType.Application.Json)
                setBody(RunRequest("ksl.examples.mm1", "MM1", numberOfReplications = reps))
            }.body()

            suspend fun drive(jobId: String): String {
                repeat(400) {
                    val r = client.get("/runs/$jobId/result")
                    if (r.status == HttpStatusCode.OK) return r.bodyAsText()
                    delay(25)
                }
                error("run $jobId did not finish")
            }

            // First: 3 reps async, driven to terminal so it is stored and indexed.
            val first = submit(3)
            assertTrue("completed" in drive(first.jobId))

            // Escalate to 8: the accept response plans a top-up reusing 3.
            val second = submit(8)
            assertEquals(3, second.reusedReplications, "should plan a top-up reusing the cached 3")

            // On completion the top-up combines into a full 8-replication result.
            val body = drive(second.jobId)
            assertTrue("completed" in body)
            assertTrue("\"completedReplications\":8" in body.replace(" ", ""), "expected a full 8-rep result: $body")
        } finally {
            service.close()
            registry.close()
        }
    }

    @Test
    fun `an identical run is cached and the retained result is projectable`() = testApplication {
        val registry = BundleRegistry.fromClasspath()
        val service = freshService(registry)
        application { kslRestModule(service) }
        val client = createClient { install(ClientContentNegotiation) { json() } }
        try {
            fun runRequest() = RunRequest("ksl.examples.mm1", "MM1", numberOfReplications = 3)

            // First submit is a miss (202) and runs a live job.
            val first = client.post("/runs") {
                contentType(ContentType.Application.Json); setBody(runRequest())
            }
            assertEquals(HttpStatusCode.Accepted, first.status)
            val accepted: JobAccepted = first.body()
            assertEquals(false, accepted.cached)
            assertTrue(accepted.resultId.isNotEmpty())

            // Drive to terminal (this also stores the result for caching/projection).
            repeat(200) {
                if (client.get("/runs/${accepted.jobId}/result").status == HttpStatusCode.OK) return@repeat
                delay(25)
            }
            val resultId = accepted.resultId

            // Projection over the retained result (no re-run): full, ?fields=, responses, one response.
            val full = client.get("/results/$resultId")
            assertEquals(HttpStatusCode.OK, full.status)
            assertTrue("completed" in full.bodyAsText())

            val projected = Json.parseToJsonElement(client.get("/results/$resultId?fields=type,summary").bodyAsText()).jsonObject
            assertEquals(setOf("type", "summary"), projected.keys, "?fields= must restrict to the requested top-level keys")

            val names: List<String> = client.get("/results/$resultId/responses").body()
            assertTrue(names.isNotEmpty(), "expected response names")
            assertEquals(HttpStatusCode.OK, client.get("/results/$resultId/responses/${names.first()}").status)

            // The identical request is now served from the cache: 200, cached=true, same id, no job run.
            val again = client.post("/runs") {
                contentType(ContentType.Application.Json); setBody(runRequest())
            }
            assertEquals(HttpStatusCode.OK, again.status)
            val againAccepted: JobAccepted = again.body()
            assertTrue(againAccepted.cached, "second identical run should be a cache hit")
            assertEquals(resultId, againAccepted.resultId, "same request → same result id")
        } finally {
            service.close()
            registry.close()
        }
    }
}
