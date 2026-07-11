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

package ksl.server.mcp

import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import ksl.service.capability.run.BundleRegistry
import org.junit.jupiter.api.DisplayName
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises the MCP tool handlers against the real example bundles on the
 * classpath. The handlers are tested directly (independent of the MCP
 * transport), proving the server exposes genuine KSL bundle metadata —
 * including the catalog-led JSON Schema that makes a model an agent tool.
 */
class KslMcpToolsTest {

    private lateinit var registry: BundleRegistry
    private lateinit var tools: KslMcpTools
    private lateinit var tmpWorkspace: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        registry = TestBundles.registry()
        // An isolated settings store so workspace get/set and file output stay off the
        // real ~/.ksl and ~/Documents/KSLWork. The default workspace lives in a temp dir.
        tmpWorkspace = java.nio.file.Files.createTempDirectory("mcp-ws")
        val settings = ksl.app.settings.UserSettingsStore(
            settingsDir = java.nio.file.Files.createTempDirectory("mcp-ksl"),
            userHome = java.nio.file.Files.createTempDirectory("mcp-home"),
            defaultWorkspaceProvider = { tmpWorkspace },
        )
        // An isolated, temp-dir result store so tests don't share ~/.ksl cache state.
        tools = KslMcpTools(
            registry,
            ksl.service.store.ResultStore(java.nio.file.Files.createTempDirectory("mcp-rs")),
            settingsStore = settings,
        )
    }

    @AfterTest
    fun tearDown() {
        tools.close()
        registry.close()
    }

    private fun firstText(result: io.modelcontextprotocol.kotlin.sdk.types.CallToolResult): String =
        (result.content.first() as TextContent).text ?: ""

    /** The structured-output payload of an execution-result tool (run/fit/experiment/optimization). */
    private fun structured(result: io.modelcontextprotocol.kotlin.sdk.types.CallToolResult): JsonObject =
        result.structuredContent!!.jsonObject

    /** True when no numeric primitive anywhere in [element] is non-finite — the condition the
     *  MCP transport's strict JSON serializer requires (it rejects ±Infinity / NaN). */
    private fun isWireSafe(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.values.all(::isWireSafe)
        is JsonArray -> element.all(::isWireSafe)
        is JsonPrimitive -> element.isString || element.doubleOrNull?.isFinite() != false
    }

    @Test
    @DisplayName("run_template structuredContent carries no non-finite numbers (unbounded control bounds → null)")
    fun runTemplateStructuredContentIsWireSafe() {
        // MM1's numServers control is unbounded: @KSLControl defaults upperBound to +Infinity.
        // Left in structuredContent, that Infinity makes the MCP transport's strict JSON
        // serializer throw while writing the reply, so no response is sent and the call hangs.
        // The fix maps non-finite bounds to null; assert none survive into the structured payload.
        val template = tools.runTemplate(buildJsonObject { put("bundleId", "ksl.examples.mm1"); put("modelId", "MM1") })
        assertEquals(false, template.isError ?: false, firstText(template))
        val structuredContent = template.structuredContent!!.jsonObject
        assertTrue("document" in structuredContent, "run_template must return the parsed document in structuredContent")
        assertTrue(
            isWireSafe(structuredContent),
            "structuredContent must contain no ±Infinity/NaN (the MCP transport serializer rejects them): $structuredContent",
        )
    }

    @Test
    @DisplayName("run_template output re-ingests into run_config (sanitized null bounds decode)")
    fun runTemplateOutputReIngestsIntoRunConfig() {
        // Regression guard for the run_template -> run_config break: run_template's structuredContent
        // sanitizes an unbounded control's bounds to null; before the ControlData decode fix, feeding
        // that back failed with "invalid RunConfiguration document" (null vs non-nullable Double).
        val template = tools.runTemplate(buildJsonObject { put("bundleId", "ksl.examples.mm1"); put("modelId", "MM1") })
        assertEquals(false, template.isError ?: false, firstText(template))
        val document = template.structuredContent!!.jsonObject["document"]!!
        val validation = tools.validateRun(buildJsonObject { put("config", document) })
        assertEquals(false, validation.isError ?: false,
            "run_template's sanitized output must decode in run_config: ${firstText(validation)}")
        assertEquals(true, validation.structuredContent!!.jsonObject["valid"]?.jsonPrimitive?.booleanOrNull,
            "the re-ingested scaffold must validate: ${structured(validation)}")
    }

    // (ExperimentConfiguration shares the identical path — same ModelControlsExport/ControlData, same
    // documentResult/sanitizeNonFinite, same decode — so it is covered by the ControlData unit tests
    // and the run_template guard above. A dedicated experiment_template guard is omitted only because
    // experiment_template requires a factorial design over >= 2 numeric controls, which the MM1 test
    // model does not have; the sanitized-null decode itself is not experiment-specific.)

    @Test
    @DisplayName("optimization config with an unbounded held control decodes (latent ControlData path)")
    fun optimizationConfigWithUnboundedHeldControlDecodes() {
        // The bare optimization template emits empty model.controls, so splice in the real unbounded
        // (sanitized-to-null) held controls from run_template to exercise optimization's latent path.
        val runControls = tools.runTemplate(buildJsonObject { put("bundleId", "ksl.examples.mm1"); put("modelId", "MM1") })
            .structuredContent!!.jsonObject["document"]!!.jsonObject["scenarios"]!!
            .jsonArray[0].jsonObject["controlOverrides"]!!
        val optDoc = tools.optimizationTemplate(buildJsonObject { put("bundleId", "ksl.examples.mm1"); put("modelId", "MM1") })
            .structuredContent!!.jsonObject["document"]!!.jsonObject
        val model = JsonObject(optDoc["model"]!!.jsonObject.toMutableMap().apply { put("controls", runControls) })
        val spliced = JsonObject(optDoc.toMutableMap().apply { put("model", model) })
        val validation = tools.validateOptimization(buildJsonObject { put("config", spliced) })
        assertNotNull(validation.structuredContent,
            "an optimization config with null-bounded held controls must DECODE (not fail as 'invalid document'): ${firstText(validation)}")
    }

    @Test
    fun `list_bundles surfaces the MM1 example bundle`() {
        val result = tools.listBundles()
        assertEquals(false, result.isError ?: false)
        val text = firstText(result)
        assertTrue("ksl.examples.mm1" in text, "expected MM1 bundle in: $text")
    }

    @Test
    fun `list_models requires bundleId and returns the model ids`() {
        val missing = tools.listModels(buildJsonObject {})
        assertEquals(true, missing.isError)

        val ok = tools.listModels(buildJsonObject { put("bundleId", "ksl.examples.mm1") })
        assertEquals(false, ok.isError ?: false)
        assertTrue("MM1" in firstText(ok))
    }

    @Test
    fun `describe_model returns a catalog-led input schema`() {
        val args = buildJsonObject {
            put("bundleId", "ksl.examples.mm1")
            put("modelId", "MM1")
        }
        val result = tools.describeModel(args)
        assertEquals(false, result.isError ?: false)

        val payload = structured(result)
        assertEquals(true, payload["hasCatalog"].toString().toBoolean())
        val inputSchema = payload["inputSchema"]!!.jsonObject
        assertEquals("object", inputSchema["type"].toString().trim('"'))
        val properties = inputSchema["properties"]!!.jsonObject
        // MM1 nominates three inputs; the schema is catalog-led, so exactly those appear.
        assertEquals(3, properties.size, "expected the three nominated inputs; got ${properties.keys}")
    }

    @Test
    fun `describe_model reports a clear error for an unknown model`() {
        val args: JsonObject = buildJsonObject {
            put("bundleId", "ksl.examples.mm1")
            put("modelId", "DoesNotExist")
        }
        val result = tools.describeModel(args)
        assertEquals(true, result.isError)
        assertTrue("DoesNotExist" in firstText(result))
    }

    @Test
    fun `run_model returns structured output with a complete summary`() = runBlocking {
        val args = buildJsonObject {
            put("bundleId", "ksl.examples.mm1")
            put("modelId", "MM1")
            put("numberOfReplications", 3) // keep the test fast
        }
        val result = tools.runModel(args)
        assertEquals(false, result.isError ?: false, "run failed: ${firstText(result)}")

        // structuredContent carries the FULL result; the text is a complete summary.
        val sc = structured(result)
        assertEquals("completed", sc["type"]!!.jsonPrimitive.content)
        assertEquals(false, sc["cached"]!!.jsonPrimitive.content.toBoolean(), "first run is a cache miss")
        assertTrue(sc["responses"]!!.jsonArray.isNotEmpty(), "structuredContent carries all responses")
        // The text summary reports every response with its statistics (no take(8) truncation).
        assertTrue("Std Err" in firstText(result), "the summary includes standard error")
        val resultId = sc["resultId"]!!.jsonPrimitive.content

        // The full payload (with the run summary) is also retrievable by id — no re-run.
        // get_result projects it back through the same envelope, so structuredContent is the full result.
        val full = structured(tools.getResult(buildJsonObject { put("resultId", resultId) }))
        assertEquals(3, full["summary"]!!.jsonObject["completedReplications"]!!.jsonPrimitive.content.toInt())

        // The identical flattened run is served from the cache, same id.
        val again = structured(tools.runModel(args))
        assertEquals(true, again["cached"]!!.jsonPrimitive.content.toBoolean(), "second identical run is a cache hit")
        assertEquals(resultId, again["resultId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `run_model applies inputs and rejects unknown input keys`() = runBlocking {
        val descriptor = registry.describeModel("ksl.examples.mm1", "MM1")!!
        val controlKey = descriptor.controls.numericControls.first().keyName

        // A valid input runs cleanly.
        val ok = tools.runModel(
            buildJsonObject {
                put("bundleId", "ksl.examples.mm1")
                put("modelId", "MM1")
                put("numberOfReplications", 3)
                putJsonObject("inputs") { put(controlKey, 2.0) }
            },
        )
        assertEquals(false, ok.isError ?: false, firstText(ok))
        assertEquals("completed", structured(ok)["type"]!!.jsonPrimitive.content)

        // An unknown input key is rejected before running.
        val bad = tools.runModel(
            buildJsonObject {
                put("bundleId", "ksl.examples.mm1")
                put("modelId", "MM1")
                putJsonObject("inputs") { put("not.a.real.input", 1.0) }
            },
        )
        assertEquals(true, bad.isError)
        assertTrue("not.a.real.input" in firstText(bad))
    }

    @Test
    fun `run_model applies string and JSON control overrides end to end`() = runBlocking {
        val bundleId = "ksl.examples.controls-fixture"
        val modelId = "ControlsEcho"

        // Baseline (defaults): weights=[1.0], mode=ADD, offset=0.0 -> result = 1.0.
        val baseline = tools.runModel(buildJsonObject { put("bundleId", bundleId); put("modelId", modelId) })
        assertEquals(false, baseline.isError ?: false, firstText(baseline))
        assertEquals(1.0, echoResult(structured(baseline)), 1e-9)

        // Override each family at once: weights=[2,3] (JSON control, encoded string),
        // mode=SUB (string control), offset=2 (numeric control) -> sum([2,3]) - 2 = 3.0.
        // 3.0 is reachable only if all three bound: drop weights -> -1.0; drop mode -> 7.0;
        // drop offset -> 5.0. So the value is a joint proof that every family took effect.
        val overridden = tools.runModel(
            buildJsonObject {
                put("bundleId", bundleId)
                put("modelId", modelId)
                putJsonObject("inputs") {
                    put("echo.weights", "[2.0, 3.0]") // JSON control passed as an encoded string
                    put("echo.mode", "SUB")           // string control
                    put("echo.offset", 2.0)           // numeric control
                }
            },
        )
        assertEquals(false, overridden.isError ?: false, "string/JSON overrides must not error: ${firstText(overridden)}")
        val sc = structured(overridden)
        assertEquals("completed", sc["type"]!!.jsonPrimitive.content)
        assertEquals(3.0, echoResult(sc), 1e-9)
    }

    /** The controls-fixture's single "result" response average from a run's structured payload. */
    private fun echoResult(sc: JsonObject): Double =
        sc["responses"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["name"]!!.jsonPrimitive.content == "result" }["average"]!!
            .jsonPrimitive.doubleOrNull ?: error("no average for the 'result' response")

    @Test
    fun `cancel_run cancels a running job and is a clean no-op for an unknown job`() = runBlocking {
        // Unknown job: a clean cancelled:false, not an error (cancel is idempotent).
        val unknown = tools.cancelRun(buildJsonObject { put("jobId", "no-such-job") })
        assertEquals(false, unknown.isError ?: false, firstText(unknown))
        val u = structured(unknown)
        assertEquals("no-such-job", u["jobId"]!!.jsonPrimitive.content)
        assertEquals(false, u["cancelled"]!!.jsonPrimitive.content.toBoolean())

        // A freshly-submitted, uncached run is registered and RUNNING (submit_run does not wait);
        // an immediate cancel lands on the still-live job. The large rep count guarantees it
        // cannot finish in the microseconds before the cancel checks its status.
        val submit = structured(
            tools.submitRun(
                buildJsonObject {
                    put("bundleId", "ksl.examples.mm1")
                    put("modelId", "MM1")
                    put("numberOfReplications", 1000)
                    put("useCache", false)
                },
            ),
        )
        assertEquals("RUNNING", submit["status"]!!.jsonPrimitive.content, "an uncached submit_run returns a live job")
        val jobId = submit["jobId"]!!.jsonPrimitive.content

        val cancelled = tools.cancelRun(buildJsonObject { put("jobId", jobId); put("reason", "test") })
        assertEquals(false, cancelled.isError ?: false, firstText(cancelled))
        val c = structured(cancelled)
        assertEquals(jobId, c["jobId"]!!.jsonPrimitive.content)
        assertEquals(true, c["cancelled"]!!.jsonPrimitive.content.toBoolean(), "the just-submitted running job should cancel")
    }

    @Test
    fun `run_model enableKSLDatabase opt-in controls whether a database is produced`() = runBlocking {
        // Default (no opt-in): no database for the db_* tools to inspect.
        val off = tools.runModel(
            buildJsonObject { put("bundleId", "ksl.examples.mm1"); put("modelId", "MM1"); put("numberOfReplications", 3) },
        )
        assertEquals(false, off.isError ?: false, firstText(off))
        val offId = structured(off)["resultId"]!!.jsonPrimitive.content
        val offStatus = structured(tools.dbStatus(buildJsonObject { put("resultId", offId) }))
        assertEquals(false, offStatus["present"]!!.jsonPrimitive.content.toBoolean(), "no DB without the opt-in")

        // Opt in: a database is produced and db_status finds it.
        val on = tools.runModel(
            buildJsonObject {
                put("bundleId", "ksl.examples.mm1"); put("modelId", "MM1"); put("numberOfReplications", 3)
                put("enableKSLDatabase", true)
            },
        )
        assertEquals(false, on.isError ?: false, firstText(on))
        val onId = structured(on)["resultId"]!!.jsonPrimitive.content
        assertNotEquals(offId, onId, "the opt-in changes the config, so it keys a distinct result")
        val onStatus = structured(tools.dbStatus(buildJsonObject { put("resultId", onId) }))
        assertEquals(true, onStatus["present"]!!.jsonPrimitive.content.toBoolean(), "the opt-in produces a DB: $onStatus")
    }

    @Test
    fun `get_workspace surfaces recent workspaces and configurations`() {
        // The recents keys are always present (arrays), even before any set.
        val before = structured(tools.getWorkspace())
        assertTrue("recentWorkspaces" in before, "get_workspace should surface recentWorkspaces")
        assertTrue("recentConfigurations" in before, "get_workspace should surface recentConfigurations")

        // Setting a workspace records it in the recent-workspaces list.
        val ws = java.nio.file.Files.createTempDirectory("mcp-ws-recent")
        tools.setWorkspace(buildJsonObject { put("path", ws.toString()) })
        val recent = structured(tools.getWorkspace())["recentWorkspaces"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(
            recent.any { it.contains(ws.fileName.toString()) },
            "the set workspace should appear in recentWorkspaces: $recent",
        )
    }

    @Test
    fun `list_bundles surfaces bundle-id conflicts`() {
        val dir = java.nio.file.Files.createTempDirectory("mcp-conflict")
        // Two jars declaring the same bundleId (distinct display names → real, newest-wins conflict).
        ksl.examples.general.appsupport.ManifestBundleFixtures.assembleManifestBundle(
            dir, "a", "ksl.examples.dup", ksl.examples.general.appsupport.MM1ModelBuilder::class.java,
        ) { it.displayName = "Copy A" }
        ksl.examples.general.appsupport.ManifestBundleFixtures.assembleManifestBundle(
            dir, "b", "ksl.examples.dup", ksl.examples.general.appsupport.MM1ModelBuilder::class.java,
        ) { it.displayName = "Copy B" }

        val isolated = ksl.app.settings.UserSettingsStore(
            settingsDir = java.nio.file.Files.createTempDirectory("mcp-c-set"),
            userHome = java.nio.file.Files.createTempDirectory("mcp-c-home"),
            defaultWorkspaceProvider = { java.nio.file.Files.createTempDirectory("mcp-c-ws") },
        )
        BundleRegistry.fromDirectories(listOf(dir)).use { conflicting ->
            KslMcpTools(
                conflicting,
                ksl.service.store.ResultStore(java.nio.file.Files.createTempDirectory("mcp-c-rs")),
                settingsStore = isolated,
            ).use { t ->
                val sc = structured(t.listBundles())
                assertTrue("conflicts" in sc, "list_bundles should surface conflicts when bundleIds collide: ${sc.keys}")
                val ids = sc["conflicts"]!!.jsonArray.map { it.jsonObject["bundleId"]!!.jsonPrimitive.content }
                assertTrue("ksl.examples.dup" in ids, "expected the duplicated bundleId in conflicts: $ids")
            }
        }
    }

    @Test
    fun `run_model reports a clear error for an unknown model`() = runBlocking {
        val args = buildJsonObject {
            put("bundleId", "ksl.examples.mm1")
            put("modelId", "Nope")
        }
        val result = tools.runModel(args)
        assertEquals(true, result.isError)
    }

    @Test
    fun `experiment document flow - template, validate, and experiment_config`() = runBlocking {
        // A model with >= 2 numeric controls (a factorial needs >= 2 factors).
        val target = registry.listBundles().firstNotNullOfOrNull { bundle ->
            bundle.modelIds.firstNotNullOfOrNull { modelId ->
                val descriptor = runCatching { registry.describeModel(bundle.bundleId, modelId) }.getOrNull()
                if (descriptor != null && descriptor.controls.numericControls.size >= 2) Pair(bundle.bundleId, modelId) else null
            }
        }
        assertNotNull(target, "expected a model with >= 2 numeric controls")
        val (bundleId, modelId) = target

        // template → a runnable ExperimentConfiguration scaffold.
        val template = tools.experimentTemplate(buildJsonObject { put("bundleId", bundleId); put("modelId", modelId) })
        assertEquals(false, template.isError ?: false, firstText(template))
        val documentJson = Json.parseToJsonElement(firstText(template)).jsonObject
        assertTrue("factors" in documentJson && "designSpec" in documentJson, "template should be an ExperimentConfiguration")

        // validate accepts it (verdict in structuredContent).
        val report = structured(tools.validateExperiment(buildJsonObject { put("config", documentJson) }))
        assertEquals(true, report["valid"]!!.jsonPrimitive.content.toBoolean(), "templated document should validate: $report")

        // experiment_config runs it; structuredContent is a batch result with all design points.
        val sc = structured(tools.experimentConfig(buildJsonObject { put("config", documentJson) }))
        assertEquals("batch", sc["type"]!!.jsonPrimitive.content, "expected a batch result")
        assertTrue(sc["items"]!!.jsonArray.isNotEmpty(), "expected design points")
        val resultId = sc["resultId"]!!.jsonPrimitive.content
        assertEquals(
            false,
            tools.getDesignPoint(buildJsonObject { put("resultId", resultId); put("index", 0) }).isError ?: false,
        )
    }

    @Test
    fun `preview_experiment_config echoes the canonical document and the design-point cost`() = runBlocking {
        val config = ksl.app.config.experiment.ExperimentConfiguration(
            modelReference = ksl.app.config.ModelReference.ByProviderId("MM1"),
            factors = listOf(
                ksl.app.config.experiment.FactorSpec("A", listOf(1.0, 2.0), ksl.app.config.experiment.ControlBinding.Control("A")),
                ksl.app.config.experiment.FactorSpec("B", listOf(1.0, 2.0), ksl.app.config.experiment.ControlBinding.Control("B")),
            ),
            designSpec = ksl.app.config.experiment.DesignSpec.TwoLevelFactorial(),
            replications = ksl.app.config.experiment.ReplicationSpec.Uniform(10),
        )
        val configJson = Json.parseToJsonElement(ksl.service.capability.run.ExperimentDocuments.encode(config)).jsonObject
        val result = tools.previewExperiment(buildJsonObject { put("config", configJson) })
        assertEquals(false, result.isError ?: false, firstText(result))
        val preview = structured(result)
        assertTrue(preview["canonical"] is JsonObject, "canonical echo should be a nested document")
        val w = preview["workload"]!!.jsonObject
        assertEquals(4, w["designPointCount"]!!.jsonPrimitive.content.toInt(), "2^2 = four design points")
        assertEquals(40, w["totalReplications"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `fit_dataset returns structured output with the full ranked fits`() = runBlocking {
        val sample = ksl.utilities.random.rvariable.ExponentialRV(10.0).sample(200)
        val args = buildJsonObject {
            putJsonArray("data") { sample.forEach { add(it) } }
            put("name", "svc")
            put("kind", "CONTINUOUS")
        }
        val result = tools.fitDataset(args)
        assertEquals(false, result.isError ?: false, "fit failed: ${firstText(result)}")

        // structuredContent carries the full ranked fits; the summary lists them.
        val sc = structured(result)
        assertEquals("svc", sc["datasetName"]!!.jsonPrimitive.content)
        assertTrue(sc["fits"]!!.jsonArray.isNotEmpty(), "expected candidate fits in structuredContent")
        assertTrue("Rank" in firstText(result), "the summary includes the ranked candidates table")
        // The summary surfaces the scaled MODA score (the recommendation basis), and the full
        // MODA scoring is in structuredContent.
        assertTrue("MODA score" in firstText(result), "the summary includes the MODA score column")
        assertNotNull(sc["scoring"], "structuredContent carries the MODA scoring")
        val resultId = sc["resultId"]!!.jsonPrimitive.content
        val full = structured(tools.getResult(buildJsonObject { put("resultId", resultId) }))
        assertTrue(full["fits"]!!.jsonArray.isNotEmpty(), "full ranked fits retrievable by id")

        // get_fit_scoring returns the full scaled MODA matrix (metrics + scaled scores).
        val scoring = tools.getFitScoring(buildJsonObject { put("resultId", resultId) })
        assertEquals(false, scoring.isError ?: false, firstText(scoring))
        val scData = structured(scoring)
        assertTrue(scData["metrics"]!!.jsonArray.isNotEmpty(), "MODA metrics present")
        assertTrue(scData["values"]!!.jsonArray.isNotEmpty(), "MODA scaled (value-function) scores present")
        // The rendered matrix is the scaled value-function output, not raw metric values.
        assertTrue("Scaled scores" in firstText(scoring), "the matrix is labeled as scaled scores")

        // get_fit_report writes a self-contained HTML report. (Headless here -> the
        // tables/stats report without plots; plots are a desktop confirmation.)
        val report = tools.getFitReport(buildJsonObject { put("resultId", resultId) })
        assertEquals(false, report.isError ?: false, firstText(report))
        val rPath = structured(report)["reportPath"]!!.jsonPrimitive.content
        val rFile = java.nio.file.Path.of(rPath)
        try {
            assertTrue(java.nio.file.Files.exists(rFile), "the report file was written")
            val htmlText = java.nio.file.Files.readString(rFile)
            assertTrue(htmlText.length > 200 && "<" in htmlText, "the report is non-empty HTML")
            assertTrue("svc" in htmlText, "the report names the dataset")
        } finally {
            java.nio.file.Files.deleteIfExists(rFile)
        }
    }

    @Test
    fun `fit_dataset rejects a missing data argument`() = runBlocking {
        val result = tools.fitDataset(buildJsonObject { put("name", "x") })
        assertEquals(true, result.isError)
    }

    @Test
    fun `fit document flow - template, validate, and fit_config`() = runBlocking {
        // A template is a runnable scaffold an agent fills with data.
        val template = tools.fitTemplate(buildJsonObject { put("kind", "CONTINUOUS") })
        assertEquals(false, template.isError ?: false)
        val scaffold = Json.parseToJsonElement(firstText(template)).jsonObject
        assertTrue("dataSource" in scaffold && "estimatorIds" in scaffold, "template should be a FitConfiguration")

        // Build a filled document with the real types and the canonical codec
        // (avoids hand-guessing the polymorphic data-source discriminator).
        val sample = ksl.utilities.random.rvariable.ExponentialRV(10.0).sample(200)
        val config = ksl.app.dist.config.FitConfiguration(
            dataSource = ksl.app.dist.config.DataSourceReference.Inline(mapOf("svc" to sample)),
            kind = ksl.app.dist.config.DistributionKind.CONTINUOUS,
            estimatorIds = ksl.app.dist.catalog.FittingCatalog.defaultEstimatorIds(ksl.app.dist.config.DistributionKind.CONTINUOUS),
            scoringModelIds = ksl.app.dist.catalog.FittingCatalog.defaultScoringModelIds(),
        )
        val configJson = Json.parseToJsonElement(ksl.service.capability.fit.FitDocuments.encode(config)).jsonObject

        // validate_fit_config accepts it (verdict in structuredContent).
        val report = structured(tools.validateFit(buildJsonObject { put("config", configJson) }))
        assertEquals(true, report["valid"]!!.jsonPrimitive.content.toBoolean(), "filled document should validate: $report")

        // fit_config runs it; structuredContent carries the full ranked fits.
        val sc = structured(tools.fitConfig(buildJsonObject { put("config", configJson) }))
        assertTrue(sc["fits"]!!.jsonArray.isNotEmpty())
        val full = structured(tools.getResult(buildJsonObject { put("resultId", sc["resultId"]!!.jsonPrimitive.content) }))
        assertTrue(full["fits"]!!.jsonArray.isNotEmpty())
    }

    @Test
    fun `fit_config rejects an invalid document`() = runBlocking {
        // An inline source with an empty dataset is rejected by validation.
        val config = ksl.app.dist.config.FitConfiguration(
            dataSource = ksl.app.dist.config.DataSourceReference.Inline(mapOf("empty" to DoubleArray(0))),
            kind = ksl.app.dist.config.DistributionKind.CONTINUOUS,
        )
        val configJson = Json.parseToJsonElement(ksl.service.capability.fit.FitDocuments.encode(config)).jsonObject
        val result = tools.fitConfig(buildJsonObject { put("config", configJson) })
        assertEquals(true, result.isError)
    }

    @Test
    fun `submit_run then poll journaled events and result to terminal`() = runBlocking {
        val submit = tools.submitRun(
            buildJsonObject {
                put("bundleId", "ksl.examples.mm1")
                put("modelId", "MM1")
                put("numberOfReplications", 3)
            },
        )
        assertEquals(false, submit.isError ?: false, firstText(submit))
        val accepted = structured(submit)
        val jobId = accepted["jobId"]!!.jsonPrimitive.content
        assertEquals(false, accepted["cached"]!!.jsonPrimitive.content.toBoolean(), "first submit is a miss")
        val resultId = accepted["resultId"]!!.jsonPrimitive.content

        // Poll the journal to terminal, accumulating events. The journal retains
        // every event, so even if the run finishes before we poll, offset 0
        // replays the full history (gap #6).
        var offset = 0
        var totalEvents = 0
        var terminal = false
        repeat(200) {
            val snapshot = structured(tools.getRunEvents(buildJsonObject { put("jobId", jobId); put("fromOffset", offset) }))
            totalEvents += snapshot["events"]!!.jsonArray.size
            offset = snapshot["nextOffset"]!!.jsonPrimitive.content.toInt()
            if (snapshot["status"]!!.jsonPrimitive.content == "TERMINAL") {
                terminal = true
                return@repeat
            }
            kotlinx.coroutines.delay(25)
        }
        assertTrue(terminal, "run did not reach a terminal state in time")
        assertTrue(totalEvents > 0, "expected to observe journaled run events")

        // get_run_result now returns the structured result (store-on-completion), same resultId.
        val sc = structured(tools.getRunResult(buildJsonObject { put("jobId", jobId) }))
        assertEquals("completed", sc["type"]!!.jsonPrimitive.content)
        assertEquals(resultId, sc["resultId"]!!.jsonPrimitive.content)

        // The result is projectable by id (parity with the blocking tools).
        val full = structured(tools.getResult(buildJsonObject { put("resultId", resultId) }))
        assertEquals(3, full["summary"]!!.jsonObject["completedReplications"]!!.jsonPrimitive.content.toInt())

        // An identical submit is now a cache hit: cached=true, jobId == resultId, no live journal.
        val again = structured(
            tools.submitRun(
                buildJsonObject { put("bundleId", "ksl.examples.mm1"); put("modelId", "MM1"); put("numberOfReplications", 3) },
            ),
        )
        assertTrue(again["cached"]!!.jsonPrimitive.content.toBoolean(), "identical async submit should hit the cache")
        assertEquals(resultId, again["jobId"]!!.jsonPrimitive.content)
        // get_run_result on the cached id resolves from the store.
        val cachedSc = structured(tools.getRunResult(buildJsonObject { put("jobId", again["jobId"]!!.jsonPrimitive.content) }))
        assertEquals("completed", cachedSc["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `get_run_events and get_run_result reject unknown job ids`() = runBlocking {
        assertEquals(true, tools.getRunEvents(buildJsonObject { put("jobId", "nope") }).isError)
        assertEquals(true, tools.getRunResult(buildJsonObject { put("jobId", "nope") }).isError)
    }

    @Test
    fun `run_optimization optimizes MM1 and returns best solution plus iterations`() = runBlocking {
        val descriptor = registry.describeModel("ksl.examples.mm1", "MM1")!!
        val controlKey = descriptor.controls.numericControls.first().keyName
        val objective = descriptor.responseNames.first()
        val args = buildJsonObject {
            put("bundleId", "ksl.examples.mm1")
            put("modelId", "MM1")
            put("objectiveResponse", objective)
            putJsonArray("inputs") {
                add(
                    buildJsonObject {
                        put("name", controlKey)
                        put("lowerBound", 1.0)
                        put("upperBound", 3.0)
                        put("granularity", 1.0)
                    },
                )
            }
            put("maxIterations", 3)
            put("replicationsPerEvaluation", 2)
        }
        val result = tools.runOptimization(args)
        assertEquals(false, result.isError ?: false, "optimization failed: ${firstText(result)}")
        // structuredContent carries type + best + the full iteration trace.
        val sc = structured(result)
        assertEquals("optimization", sc["type"]!!.jsonPrimitive.content)
        assertTrue(sc["iterations"]!!.jsonArray.isNotEmpty(), "expected solver iterations")
        val resultId = sc["resultId"]!!.jsonPrimitive.content
        val full = structured(tools.getResult(buildJsonObject { put("resultId", resultId) }))
        assertTrue(full["iterations"]!!.jsonArray.isNotEmpty(), "full iteration history retrievable by id")
    }

    @Test
    fun `optimization_template scaffolds a valid, submittable optimization`() = runBlocking {
        val template = tools.optimizationTemplate(buildJsonObject { put("bundleId", "ksl.examples.mm1"); put("modelId", "MM1") })
        assertEquals(false, template.isError ?: false, firstText(template))
        val doc = Json.parseToJsonElement(firstText(template)).jsonObject
        assertTrue("model" in doc && "problem" in doc && "solver" in doc, "expected an OptimizationRunConfiguration scaffold; got ${doc.keys}")

        // The scaffold validates: structure plus the objective / decision variable resolve against the model.
        // (End-to-end optimization execution is covered by the run_optimization test; the scaffold produces
        // the identical config type, so validating it here avoids re-running a full 50-iteration solve.)
        val report = structured(tools.validateOptimization(buildJsonObject { put("config", doc) }))
        assertEquals(true, report["valid"]!!.jsonPrimitive.content.toBoolean(), "templated optimization should validate: $report")

        // An unknown model yields a clean error, not a crash.
        val bad = tools.optimizationTemplate(buildJsonObject { put("bundleId", "ksl.examples.mm1"); put("modelId", "Nope") })
        assertEquals(true, bad.isError)
        assertTrue("Nope" in firstText(bad))
    }

    @Test
    fun `run_experiment runs a factorial and returns a batch result`() = runBlocking {
        // Find a model with >= 2 numeric controls (a factorial needs >= 2 factors).
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
        assertNotNull(target, "expected an example model with >= 2 numeric controls")
        val (bundleId, modelId, descriptor) = target

        val factors = buildJsonArray {
            descriptor.controls.numericControls.take(2).forEach { control ->
                add(
                    buildJsonObject {
                        put("name", control.keyName)
                        put("controlKey", control.keyName)
                        put("low", control.value)
                        put("high", control.value + 1.0)
                    },
                )
            }
        }
        val args = buildJsonObject {
            put("bundleId", bundleId)
            put("modelId", modelId)
            put("factors", factors)
            put("numRepsPerDesignPoint", 2)
        }
        val result = tools.runExperiment(args)
        assertEquals(false, result.isError ?: false, "experiment failed: ${firstText(result)}")
        // structuredContent is a batch result with all design points.
        val sc = structured(result)
        assertEquals("batch", sc["type"]!!.jsonPrimitive.content)
        assertTrue(sc["items"]!!.jsonArray.isNotEmpty(), "expected design points")
        val resultId = sc["resultId"]!!.jsonPrimitive.content
        // get_design_point now works on a flattened experiment result (parity).
        assertEquals(
            false,
            tools.getDesignPoint(buildJsonObject { put("resultId", resultId); put("index", 0) }).isError ?: false,
        )
    }

    @Test
    fun `run_config runs an authored RunConfiguration document and validates it`() = runBlocking {
        // Build a document, serialize via the authoritative codec, hand it to the tool as JSON.
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
        val configJson = Json.parseToJsonElement(ksl.app.config.RunConfigurationJson.encode(document)).jsonObject

        val ok = tools.runConfig(buildJsonObject { put("config", configJson) })
        assertEquals(false, ok.isError ?: false, firstText(ok))
        // structuredContent carries the full result.
        val sc = structured(ok)
        assertEquals("completed", sc["type"]!!.jsonPrimitive.content)
        assertEquals(false, sc["cached"]!!.jsonPrimitive.content.toBoolean(), "first run is a cache miss")
        assertTrue(sc["responses"]!!.jsonArray.isNotEmpty(), "structuredContent carries all responses")
        val resultId = sc["resultId"]!!.jsonPrimitive.content

        // Projection over the retained result (no re-run): full payload, names, one response.
        val full = structured(tools.getResult(buildJsonObject { put("resultId", resultId) }))
        assertEquals("completed", full["type"]!!.jsonPrimitive.content)
        val names = structured(tools.listResponses(buildJsonObject { put("resultId", resultId) }))["responses"]!!
            .jsonArray.map { it.jsonPrimitive.content }
        assertTrue(names.isNotEmpty())
        assertEquals(false, tools.getResponse(buildJsonObject { put("resultId", resultId); put("name", names.first()) }).isError ?: false)

        // The identical document is served from the cache (no re-run), same result id.
        val again = structured(tools.runConfig(buildJsonObject { put("config", configJson) }))
        assertEquals(true, again["cached"]!!.jsonPrimitive.content.toBoolean(), "second identical run is a cache hit")
        assertEquals(resultId, again["resultId"]!!.jsonPrimitive.content, "same document → same result id")

        // A document referencing an unknown model is rejected by validation.
        val badDoc = ksl.app.config.RunConfiguration(
            scenarios = listOf(
                ksl.app.config.ScenarioSpec("x", ksl.app.config.ModelReference.ByProviderId("NoSuchModel")),
            ),
        )
        val badJson = Json.parseToJsonElement(ksl.app.config.RunConfigurationJson.encode(badDoc)).jsonObject
        val bad = tools.runConfig(buildJsonObject { put("config", badJson) })
        assertEquals(true, bad.isError)
    }

    @Test
    fun `authoring stack - intent menu, template, and validation`() = runBlocking {
        // Intent menu: describe_model exposes the model's task kinds.
        val describe = tools.describeModel(buildJsonObject { put("bundleId", "ksl.examples.mm1"); put("modelId", "MM1") })
        val kinds = structured(describe)["supportedApps"]!!
            .jsonArray.map { it.jsonPrimitive.content }
        assertTrue("SIMOPT" in kinds, "describe_model should list supportedApps; got $kinds")

        // Scaffold: run_template yields a document that validates as runnable.
        val template = tools.runTemplate(buildJsonObject { put("bundleId", "ksl.examples.mm1"); put("modelId", "MM1") })
        assertEquals(false, template.isError ?: false, firstText(template))
        val templateDoc = Json.parseToJsonElement(firstText(template)).jsonObject
        val validTemplate = tools.validateRun(buildJsonObject { put("config", templateDoc) })
        assertEquals(true, structured(validTemplate)["valid"]!!.jsonPrimitive.content.toBoolean())

        // Validate flags an unknown-model document without running it.
        val badDoc = Json.parseToJsonElement(
            ksl.app.config.RunConfigurationJson.encode(
                ksl.app.config.RunConfiguration(
                    scenarios = listOf(
                        ksl.app.config.ScenarioSpec("x", ksl.app.config.ModelReference.ByProviderId("Nope")),
                    ),
                ),
            ),
        ).jsonObject
        val report = structured(tools.validateRun(buildJsonObject { put("config", badDoc) }))
        assertEquals(false, report["valid"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(report["errors"]!!.jsonArray.isNotEmpty())
    }

    @Test
    fun `submit_run escalation runs only the top-up and combines on completion`() = runBlocking {
        fun submit(reps: Int) = structured(
            tools.submitRun(
                buildJsonObject { put("bundleId", "ksl.examples.mm1"); put("modelId", "MM1"); put("numberOfReplications", reps) },
            ),
        )

        suspend fun pollResult(jobId: String): JsonObject {
            repeat(400) {
                // While running, get_run_result returns a {status} marker (no `type`);
                // once complete it carries the full structured result (with `type`).
                val sc = tools.getRunResult(buildJsonObject { put("jobId", jobId) }).structuredContent?.jsonObject
                if (sc != null && sc.containsKey("type")) return sc
                kotlinx.coroutines.delay(25)
            }
            error("run $jobId did not finish in time")
        }

        // First: 3 reps async, driven to terminal so it is stored and indexed in the run family.
        val firstCard = pollResult(submit(3)["jobId"]!!.jsonPrimitive.content)
        assertEquals("completed", firstCard["type"]!!.jsonPrimitive.content)

        // Escalate to 8: the submit response plans a top-up reusing the cached 3.
        val second = submit(8)
        assertEquals(3, second["reusedReplications"]!!.jsonPrimitive.content.toInt(), "submit should plan a top-up reusing 3")

        // On completion the (5-rep) top-up is combined with the cached 3 into a full 8-rep result.
        val secondCard = pollResult(second["jobId"]!!.jsonPrimitive.content)
        assertEquals("completed", secondCard["type"]!!.jsonPrimitive.content)
        assertEquals(3, secondCard["reusedReplications"]!!.jsonPrimitive.content.toInt())
        val full = structured(tools.getResult(buildJsonObject { put("resultId", secondCard["resultId"]!!.jsonPrimitive.content) }))
        assertEquals(8, full["summary"]!!.jsonObject["completedReplications"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `run_config escalating the replication count reuses the cached shorter run`() = runBlocking {
        fun doc(reps: Int) = ksl.app.config.RunConfiguration(
            scenarios = listOf(
                ksl.app.config.ScenarioSpec(
                    name = "MM1",
                    modelReference = ksl.app.config.ModelReference.ByProviderId("MM1"),
                    runOverrides = ksl.app.config.ExperimentRunOverrides(numberOfReplications = reps),
                ),
            ),
            outputConfig = ksl.app.config.OutputConfig(reports = emptySet()),
        )
        fun configJson(reps: Int) = Json.parseToJsonElement(ksl.app.config.RunConfigurationJson.encode(doc(reps))).jsonObject

        // First run of 3 reps: a full run (nothing to reuse), recorded in the run-identity index.
        val first = structured(tools.runConfig(buildJsonObject { put("config", configJson(3)) }))
        assertEquals("completed", first["type"]!!.jsonPrimitive.content)
        assertEquals(null, first["reusedReplications"], "the first run reuses nothing")

        // Escalate to 8 reps: should reuse the cached 3 and run only the missing 5.
        val second = structured(tools.runConfig(buildJsonObject { put("config", configJson(8)) }))
        assertEquals("completed", second["type"]!!.jsonPrimitive.content)
        assertEquals(3, second["reusedReplications"]!!.jsonPrimitive.content.toInt(), "should reuse the cached 3-rep run")

        // The combined result is a full 8-replication result, projectable by id.
        val resultId = second["resultId"]!!.jsonPrimitive.content
        val full = structured(tools.getResult(buildJsonObject { put("resultId", resultId) }))
        assertEquals(8, full["summary"]!!.jsonObject["completedReplications"]!!.jsonPrimitive.content.toInt())
        assertEquals(8.0, full["responses"]!!.jsonArray.first().jsonObject["count"]!!.jsonPrimitive.content.toDouble())

        // Re-requesting 8 is now an exact cache hit (no further work).
        val again = structured(tools.runConfig(buildJsonObject { put("config", configJson(8)) }))
        assertEquals(true, again["cached"]!!.jsonPrimitive.content.toBoolean(), "identical 8-rep request is an exact hit")
        assertEquals(resultId, again["resultId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `validation and projection tools return structured output with a readable summary`() = runBlocking {
        // An invalid run document: the verdict is in structuredContent AND the text summary
        // names every error with its field path (the reporting contract).
        val badDoc = Json.parseToJsonElement(
            ksl.app.config.RunConfigurationJson.encode(
                ksl.app.config.RunConfiguration(
                    scenarios = listOf(ksl.app.config.ScenarioSpec("x", ksl.app.config.ModelReference.ByProviderId("Nope"))),
                ),
            ),
        ).jsonObject
        val invalid = tools.validateRun(buildJsonObject { put("config", badDoc) })
        assertEquals(false, structured(invalid)["valid"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(structured(invalid)["errors"]!!.jsonArray.isNotEmpty(), "errors in structuredContent")
        assertTrue("INVALID" in firstText(invalid), "the summary states the verdict: ${firstText(invalid)}")

        // get_response projects one response with its statistics in structuredContent.
        val run = tools.runModel(
            buildJsonObject { put("bundleId", "ksl.examples.mm1"); put("modelId", "MM1"); put("numberOfReplications", 3) },
        )
        val resultId = structured(run)["resultId"]!!.jsonPrimitive.content
        val names = structured(tools.listResponses(buildJsonObject { put("resultId", resultId) }))["responses"]!!
            .jsonArray.map { it.jsonPrimitive.content }
        val one = tools.getResponse(buildJsonObject { put("resultId", resultId); put("name", names.first()) })
        assertEquals(false, one.isError ?: false, firstText(one))
        assertEquals(names.first(), structured(one)["name"]!!.jsonPrimitive.content, "structuredContent is the response object")
        assertTrue("average" in firstText(one), "the summary reports the statistics")
    }

    @Test
    fun `discovery tools return typed structured output`() = runBlocking {
        // list_bundles: structuredContent {bundles:[...]} with the MM1 bundle; summary lists it.
        val bundles = tools.listBundles()
        val bundlesArr = structured(bundles)["bundles"]!!.jsonArray
        assertTrue(bundlesArr.isNotEmpty(), "structuredContent carries the bundle list")
        assertTrue(bundlesArr.any { it.jsonObject["bundleId"]?.jsonPrimitive?.content == "ksl.examples.mm1" })
        assertTrue("ksl.examples.mm1" in firstText(bundles), "the summary lists the bundle")

        // list_models: {bundleId, models:[...]}.
        val models = tools.listModels(buildJsonObject { put("bundleId", "ksl.examples.mm1") })
        val modelArr = structured(models)["models"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("MM1" in modelArr, "structuredContent lists the model ids")

        // run_template: the text is the editable document; structuredContent.document is it parsed.
        val template = tools.runTemplate(buildJsonObject { put("bundleId", "ksl.examples.mm1"); put("modelId", "MM1") })
        val doc = structured(template)["document"]!!.jsonObject
        assertTrue("scenarios" in doc, "structuredContent carries the parsed document")
        // The text content is still the raw document (so an agent can edit and resubmit it).
        assertTrue("scenarios" in Json.parseToJsonElement(firstText(template)).jsonObject)
    }

    // ---- P4: structuredContent <-> outputSchema conformance guards ----

    /** A JSON-Schema subset validator (type + required + nested properties/items) — enough to
     *  catch drift between a tool's structuredContent and its declared outputSchema. */
    private object SchemaConformance {
        fun errors(value: JsonElement, schema: JsonObject, path: String): List<String> = buildList {
            // `type` is a single string, or — for a nullable field — an array like ["number","null"].
            val types: Set<String> = when (val t = schema["type"]) {
                is JsonArray -> t.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
                is JsonPrimitive -> setOfNotNull(t.contentOrNull)
                else -> emptySet()
            }
            // A JSON null conforms iff the schema admits "null" (or declares no type at all).
            if (value is JsonNull) {
                if (types.isNotEmpty() && "null" !in types) add("$path: null not permitted (type $types)")
                return@buildList
            }
            when (types.firstOrNull { it != "null" }) {
                "object" -> {
                    val obj = value as? JsonObject ?: return listOf("$path: expected object")
                    (schema["required"] as? JsonArray)?.forEach {
                        val k = it.jsonPrimitive.content
                        if (k !in obj) add("$path.$k: required but missing")
                    }
                    (schema["properties"] as? JsonObject)?.forEach { (k, ps) ->
                        obj[k]?.let { addAll(errors(it, ps.jsonObject, "$path.$k")) }
                    }
                }
                "array" -> {
                    val arr = value as? JsonArray ?: return listOf("$path: expected array")
                    (schema["items"] as? JsonObject)?.let { items ->
                        arr.forEachIndexed { i, el -> addAll(errors(el, items, "$path[$i]")) }
                    }
                }
                "string" -> if (!(value is JsonPrimitive && value.isString)) add("$path: expected string")
                "boolean" -> if ((value as? JsonPrimitive)?.booleanOrNull == null) add("$path: expected boolean")
                "integer" -> if ((value as? JsonPrimitive)?.intOrNull == null) add("$path: expected integer")
                // Reject NaN/±Infinity: doubleOrNull parses "NaN" to a value, but it is not a valid JSON
                // number and would fail the SDK's own validation — the gap that let the n=1 NaN bug (F-2) by.
                "number" -> {
                    val d = (value as? JsonPrimitive)?.doubleOrNull
                    if (d == null || !d.isFinite()) add("$path: expected a finite number")
                }
                else -> {} // untyped fragment: accept
            }
        }

        /** The top-level ToolSchema has no `type` wrapper — its properties/required apply directly. */
        fun validate(structured: JsonObject, schema: io.modelcontextprotocol.kotlin.sdk.types.ToolSchema): List<String> =
            buildList {
                schema.required?.forEach { if (it !in structured) add("\$.$it: required but missing") }
                schema.properties?.forEach { (k, ps) ->
                    structured[k]?.let { addAll(errors(it, ps.jsonObject, "\$.$k")) }
                }
            }
    }

    @Test
    fun `every registered tool declares an outputSchema`() {
        val server = KslMcpServer.build(tools)
        val missing = server.tools.filterValues { it.tool.outputSchema == null }.keys
        assertTrue(missing.isEmpty(), "every tool should declare an outputSchema; missing on: $missing")
    }

    @Test
    fun `every result conforms to its declared outputSchema`() = runBlocking {
        val server = KslMcpServer.build(tools)
        fun check(name: String, result: io.modelcontextprotocol.kotlin.sdk.types.CallToolResult) {
            assertEquals(false, result.isError ?: false, "$name errored: ${firstText(result)}")
            val sc = result.structuredContent
            assertNotNull(sc, "$name returned no structuredContent")
            val schema = server.tools[name]!!.tool.outputSchema!!
            val errs = SchemaConformance.validate(sc.jsonObject, schema)
            assertTrue(errs.isEmpty(), "$name structuredContent violates its outputSchema: $errs")
        }

        // Discovery / schema (Group A).
        check("list_bundles", tools.listBundles())
        check("list_models", tools.listModels(buildJsonObject { put("bundleId", "ksl.examples.mm1") }))
        check("describe_model", tools.describeModel(buildJsonObject { put("bundleId", "ksl.examples.mm1"); put("modelId", "MM1") }))
        check("run_template", tools.runTemplate(buildJsonObject { put("bundleId", "ksl.examples.mm1"); put("modelId", "MM1") }))
        check("fit_template", tools.fitTemplate(buildJsonObject { put("kind", "CONTINUOUS") }))

        // Validation + preview (Groups B, C).
        val badRun = Json.parseToJsonElement(
            ksl.app.config.RunConfigurationJson.encode(
                ksl.app.config.RunConfiguration(
                    scenarios = listOf(ksl.app.config.ScenarioSpec("x", ksl.app.config.ModelReference.ByProviderId("Nope"))),
                ),
            ),
        ).jsonObject
        check("validate_run_config", tools.validateRun(buildJsonObject { put("config", badRun) }))
        val expConfig = ksl.app.config.experiment.ExperimentConfiguration(
            modelReference = ksl.app.config.ModelReference.ByProviderId("MM1"),
            factors = listOf(
                ksl.app.config.experiment.FactorSpec("A", listOf(1.0, 2.0), ksl.app.config.experiment.ControlBinding.Control("A")),
                ksl.app.config.experiment.FactorSpec("B", listOf(1.0, 2.0), ksl.app.config.experiment.ControlBinding.Control("B")),
            ),
            designSpec = ksl.app.config.experiment.DesignSpec.TwoLevelFactorial(),
            replications = ksl.app.config.experiment.ReplicationSpec.Uniform(10),
        )
        val expJson = Json.parseToJsonElement(ksl.service.capability.run.ExperimentDocuments.encode(expConfig)).jsonObject
        check("preview_experiment_config", tools.previewExperiment(buildJsonObject { put("config", expJson) }))

        // Execution result + projection (Groups D, E).
        val run = tools.runModel(buildJsonObject { put("bundleId", "ksl.examples.mm1"); put("modelId", "MM1"); put("numberOfReplications", 3) })
        check("run_model", run)
        val resultId = structured(run)["resultId"]!!.jsonPrimitive.content
        check("get_result", tools.getResult(buildJsonObject { put("resultId", resultId) }))
        val listResp = tools.listResponses(buildJsonObject { put("resultId", resultId) })
        check("list_responses", listResp)
        val name0 = structured(listResp)["responses"]!!.jsonArray.first().jsonPrimitive.content
        check("get_response", tools.getResponse(buildJsonObject { put("resultId", resultId); put("name", name0) }))

        // Job control + events (Group E).
        val submit = tools.submitRun(buildJsonObject { put("bundleId", "ksl.examples.mm1"); put("modelId", "MM1"); put("numberOfReplications", 3) })
        check("submit_run", submit)
        val jobId = structured(submit)["jobId"]!!.jsonPrimitive.content
        check("get_run_events", tools.getRunEvents(buildJsonObject { put("jobId", jobId) }))

        // Fit + its companion tools (Group D + fit follow-ups).
        val sample = ksl.utilities.random.rvariable.ExponentialRV(10.0).sample(200)
        val fit = tools.fitDataset(
            buildJsonObject { putJsonArray("data") { sample.forEach { add(it) } }; put("name", "svc"); put("kind", "CONTINUOUS") },
        )
        check("fit_dataset", fit)
        val fitId = structured(fit)["resultId"]!!.jsonPrimitive.content
        check("get_fit_scoring", tools.getFitScoring(buildJsonObject { put("resultId", fitId) }))
        check("get_fit_data_summary", tools.getFitDataSummary(buildJsonObject { put("resultId", fitId) }))
        val report = tools.getFitReport(buildJsonObject { put("resultId", fitId) })
        check("get_fit_report", report)
        java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(structured(report)["reportPath"]!!.jsonPrimitive.content))

        // Workspace (Phase A).
        check("get_workspace", tools.getWorkspace())

        // Variate generation (Phase B).
        check("list_distributions", tools.listDistributions())
        check(
            "generate_variates",
            tools.generateVariates(buildJsonObject { put("familyId", "exponential"); put("n", 5) }),
        )

        // Data summary (Phase D).
        check(
            "summarize_data",
            tools.summarizeData(buildJsonObject { putJsonArray("data") { sample.forEach { add(it) } }; put("name", "svc") }),
        )
    }

    @Test
    fun `the server registers the full tool surface`() {
        val server = KslMcpServer.build(tools)
        val toolNames = server.tools.keys
        assertNotNull(toolNames)
        assertTrue(
            toolNames.containsAll(
                setOf(
                    "list_bundles", "list_models", "describe_model",
                    "run_model", "submit_run", "get_run_events", "get_run_result", "cancel_run",
                    "run_config", "run_optimization", "run_optimization_config",
                    "run_experiment", "fit_dataset",
                    "experiment_template", "experiment_config", "validate_experiment_config",
                    "fit_template", "fit_config", "validate_fit_config",
                    "preview_run_config", "preview_optimization_config",
                    "preview_experiment_config", "preview_fit_config",
                    "run_template", "optimization_template",
                    "validate_run_config", "validate_optimization_config",
                    "get_result", "list_responses", "get_response", "get_design_point",
                    "get_fit_scoring", "get_fit_report",
                    "list_distributions", "generate_variates",
                    "summarize_data", "get_fit_data_summary",
                    "acf_analysis", "shift_analysis", "family_frequency_bootstrap",
                    "get_workspace", "set_workspace",
                ),
            ),
        )
    }

    @Test
    fun `list_distributions returns only scalar-parameter families with correct metadata`() {
        val sc = structured(tools.listDistributions())
        val dists = sc["distributions"]!!.jsonArray
        assertTrue(dists.isNotEmpty(), "expected at least one distribution family")
        // Every entry has the required fields.
        dists.forEach { d ->
            val o = d.jsonObject
            assertNotNull(o["familyId"]?.jsonPrimitive?.content, "familyId must be present")
            assertNotNull(o["displayName"]?.jsonPrimitive?.content, "displayName must be present")
            val kind = o["kind"]?.jsonPrimitive?.content
            assertTrue(kind == "CONTINUOUS" || kind == "DISCRETE", "kind must be CONTINUOUS or DISCRETE, got $kind")
            assertNotNull(o["parameters"]?.jsonObject, "parameters must be present")
        }
        // Well-known scalar families are included.
        val ids = dists.map { it.jsonObject["familyId"]!!.jsonPrimitive.content }
        assertTrue(ids.contains("exponential"), "Exponential should be available; got $ids")
        assertTrue(ids.contains("normal"), "Normal should be available; got $ids")
        // The summary distinguishes continuous and discrete counts.
        val text = firstText(tools.listDistributions())
        assertTrue("Continuous" in text && "Discrete" in text, "summary should name both kinds: $text")
    }

    @Test
    fun `generate_variates samples from a named distribution using default and custom parameters`() {
        // Default parameters — Exponential with catalog defaults.
        val defaultResult = tools.generateVariates(buildJsonObject {
            put("familyId", "exponential")
            put("n", 50)
        })
        assertEquals(false, defaultResult.isError ?: false, "generate with defaults failed: ${firstText(defaultResult)}")
        val sc = structured(defaultResult)
        assertEquals("exponential", sc["familyId"]!!.jsonPrimitive.content)
        assertEquals(50, sc["n"]!!.jsonPrimitive.content.toInt())
        val values = sc["values"]!!.jsonArray
        assertEquals(50, values.size, "expected exactly 50 values")
        assertTrue(values.all { it.jsonPrimitive.doubleOrNull != null }, "all values must be numbers")

        // Custom parameter override — Exponential with mean=5.
        val customResult = tools.generateVariates(buildJsonObject {
            put("familyId", "exponential")
            put("n", 10)
            putJsonObject("parameters") { put("mean", 5.0) }
        })
        assertEquals(false, customResult.isError ?: false, "generate with custom params failed: ${firstText(customResult)}")
        assertEquals(10, structured(customResult)["values"]!!.jsonArray.size)

        // A normal distribution with explicit mean and variance.
        val normalResult = tools.generateVariates(buildJsonObject {
            put("familyId", "normal")
            put("n", 20)
            putJsonObject("parameters") { put("mean", 100.0); put("variance", 25.0) }
        })
        assertEquals(false, normalResult.isError ?: false, firstText(normalResult))
        assertEquals(20, structured(normalResult)["values"]!!.jsonArray.size)
    }

    @Test
    fun `generate_variates writes a CSV on opt-in and auto-writes large samples under the workspace`() {
        // Small n, no output flag: inline only, no file, not truncated.
        val inline = structured(tools.generateVariates(buildJsonObject {
            put("familyId", "exponential"); put("n", 20)
        }))
        assertEquals(false, inline["truncated"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(null, inline["filePath"], "no file should be written for a small inline sample")
        assertEquals(20, inline["values"]!!.jsonArray.size)

        // Opt-in: output=true writes a CSV even for a small sample; the full values stay inline.
        val optIn = structured(tools.generateVariates(buildJsonObject {
            put("familyId", "exponential"); put("n", 15); put("name", "svc times!"); put("output", true)
        }))
        val path = optIn["filePath"]!!.jsonPrimitive.content
        assertTrue(path.startsWith(tmpWorkspace.toString()), "file should be under the workspace: $path")
        assertTrue("KSLServer" in path && "data" in path, "file should be in the app data dir: $path")
        // The user-supplied name is sanitized for the filename (unsafe chars -> '_').
        assertTrue(path.endsWith("svc_times_.csv"), "filename should be the sanitized name: $path")
        val file = java.nio.file.Path.of(path)
        try {
            assertTrue(java.nio.file.Files.exists(file), "the CSV should exist")
            assertEquals(16, java.nio.file.Files.readAllLines(file).size, "header + 15 values")
            assertEquals(false, optIn["truncated"]!!.jsonPrimitive.content.toBoolean(), "small sample is not truncated")
            assertEquals(15, optIn["values"]!!.jsonArray.size, "full sample stays inline when small")
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }

        // Large n auto-writes and truncates the inline preview to INLINE_THRESHOLD (1000).
        val large = structured(tools.generateVariates(buildJsonObject {
            put("familyId", "normal"); put("n", 5000)
        }))
        assertEquals(true, large["truncated"]!!.jsonPrimitive.content.toBoolean(), "a large sample is truncated inline")
        assertEquals(5000, large["n"]!!.jsonPrimitive.content.toInt())
        assertEquals(1000, large["values"]!!.jsonArray.size, "inline preview is capped at INLINE_THRESHOLD")
        val largeFile = java.nio.file.Path.of(large["filePath"]!!.jsonPrimitive.content)
        try {
            assertTrue(java.nio.file.Files.exists(largeFile))
            assertEquals(5001, java.nio.file.Files.readAllLines(largeFile).size, "header + 5000 values")
        } finally {
            java.nio.file.Files.deleteIfExists(largeFile)
        }
    }

    @Test
    fun `generate_variates rejects unknown families, bad parameters, and out-of-range n`() {
        // Unknown family ID.
        assertTrue(
            tools.generateVariates(buildJsonObject { put("familyId", "doesnotexist"); put("n", 5) }).isError ?: false,
            "unknown familyId should be rejected",
        )
        // Unknown parameter name.
        assertTrue(
            tools.generateVariates(buildJsonObject {
                put("familyId", "exponential"); put("n", 5)
                putJsonObject("parameters") { put("notAParam", 1.0) }
            }).isError ?: false,
            "unknown parameter should be rejected",
        )
        // n too large.
        assertTrue(
            tools.generateVariates(buildJsonObject { put("familyId", "exponential"); put("n", 10_001) }).isError ?: false,
            "n > 10000 should be rejected",
        )
        // n = 0.
        assertTrue(
            tools.generateVariates(buildJsonObject { put("familyId", "exponential"); put("n", 0) }).isError ?: false,
            "n=0 should be rejected",
        )
        // Missing familyId.
        assertTrue(tools.generateVariates(buildJsonObject { put("n", 5) }).isError ?: false)
        // Missing n.
        assertTrue(tools.generateVariates(buildJsonObject { put("familyId", "exponential") }).isError ?: false)
    }

    @Test
    fun `summarize_data computes the engine statistics and a histogram over an array`() {
        val sample = ksl.utilities.random.rvariable.ExponentialRV(10.0).sample(500)
        val result = tools.summarizeData(buildJsonObject {
            putJsonArray("data") { sample.forEach { add(it) } }
            put("name", "svc")
        })
        assertEquals(false, result.isError ?: false, firstText(result))
        val sc = structured(result)
        assertEquals("svc", sc["datasetName"]!!.jsonPrimitive.content)
        val stats = sc["dataSummary"]!!.jsonObject["statistics"]!!.jsonObject
        assertEquals(500.0, stats["count"]!!.jsonPrimitive.content.toDouble(), "count should match the sample size")
        // Exponential(mean 10) — the sample mean should be in a sane neighbourhood.
        val avg = stats["average"]!!.jsonPrimitive.content.toDouble()
        assertTrue(avg in 5.0..20.0, "average $avg should be near the true mean")
        assertNotNull(sc["dataSummary"]!!.jsonObject["positiveCount"], "sign counts are present")
        // A histogram is included by default, with bins.
        val bins = sc["histogram"]!!.jsonObject["bins"]!!.jsonArray
        assertTrue(bins.isNotEmpty(), "expected histogram bins")
        assertTrue("Histogram" in firstText(result), "the summary renders the histogram")

        // histogram=false omits the histogram.
        val noHist = structured(tools.summarizeData(buildJsonObject {
            putJsonArray("data") { sample.forEach { add(it) } }
            put("histogram", false)
        }))
        assertEquals(null, noHist["histogram"], "histogram should be omitted when histogram=false")
        assertNotNull(noHist["dataSummary"], "the data summary is still present")
    }

    @Test
    fun `acf_analysis flags serial dependence in an autocorrelated series`() {
        // A monotone ramp is almost perfectly lag-1 autocorrelated.
        val result = tools.acfAnalysis(buildJsonObject { putJsonArray("data") { for (i in 0 until 60) add(i.toDouble()) } })
        assertEquals(false, result.isError ?: false, firstText(result))
        val sc = structured(result)
        assertEquals(false, sc["independentAtLag1"]!!.jsonPrimitive.content.toBoolean(), "a ramp is strongly lag-1 dependent")
        assertTrue(sc["lag1"]!!.jsonPrimitive.content.toDouble() > 0.5, "lag-1 should be strongly positive; got ${sc["lag1"]}")
        assertTrue(sc["acf"]!!.jsonArray.isNotEmpty(), "acf per lag present")
    }

    @Test
    fun `shift_analysis recovers a positive left shift`() {
        // An exponential sample offset by +100 → the fit should recommend a left shift near 100.
        val shifted = ksl.utilities.random.rvariable.ExponentialRV(1.0).sample(300).map { it + 100.0 }
        val result = tools.shiftAnalysis(buildJsonObject { putJsonArray("data") { shifted.forEach { add(it) } } })
        assertEquals(false, result.isError ?: false, firstText(result))
        val sc = structured(result)
        assertEquals(true, sc["shiftRecommended"]!!.jsonPrimitive.content.toBoolean(), "a +100 offset should recommend a shift")
        assertTrue(sc["leftShift"]!!.jsonPrimitive.content.toDouble() > 50.0, "left shift should recover the offset; got ${sc["leftShift"]}")
    }

    @Test
    fun `family_frequency_bootstrap tallies recommended families across resamples`() {
        val sample = ksl.utilities.random.rvariable.ExponentialRV(10.0).sample(120)
        val result = tools.familyFrequencyBootstrap(buildJsonObject {
            putJsonArray("data") { sample.forEach { add(it) } }
            put("name", "svc")
            put("numSamples", 15) // small, to keep the test fast
        })
        assertEquals(false, result.isError ?: false, firstText(result))
        val sc = structured(result)
        assertEquals(15, sc["numSamples"]!!.jsonPrimitive.content.toInt())
        val cells = sc["frequency"]!!.jsonObject["cells"]!!.jsonArray
        assertTrue(cells.isNotEmpty(), "expected family tallies")
        // Each resample recommends exactly one family, so the counts sum to numSamples.
        val totalCount = cells.sumOf { it.jsonObject["count"]!!.jsonPrimitive.content.toDouble() }
        assertEquals(15.0, totalCount, 1e-6, "family counts should sum to numSamples")
    }

    @Test
    fun `summarize_data rejects empty data, non-numeric data, and a bad confidence level`() {
        assertTrue(tools.summarizeData(buildJsonObject { putJsonArray("data") {} }).isError ?: false, "empty data is rejected")
        assertTrue(tools.summarizeData(buildJsonObject { put("name", "x") }).isError ?: false, "missing data is rejected")
        assertTrue(
            tools.summarizeData(buildJsonObject {
                putJsonArray("data") { add(1.0); add(2.0) }; put("confidenceLevel", 1.5)
            }).isError ?: false,
            "a confidence level outside (0,1) is rejected",
        )
    }

    @Test
    fun `get_fit_data_summary projects the retained fit's summary without re-running`() = runBlocking {
        val sample = ksl.utilities.random.rvariable.ExponentialRV(10.0).sample(300)
        val fit = tools.fitDataset(buildJsonObject {
            putJsonArray("data") { sample.forEach { add(it) } }; put("name", "svc"); put("kind", "CONTINUOUS")
        })
        val fitId = structured(fit)["resultId"]!!.jsonPrimitive.content

        val result = tools.getFitDataSummary(buildJsonObject { put("resultId", fitId) })
        assertEquals(false, result.isError ?: false, firstText(result))
        val sc = structured(result)
        assertEquals("svc", sc["datasetName"]!!.jsonPrimitive.content)
        val stats = sc["dataSummary"]!!.jsonObject["statistics"]!!.jsonObject
        assertEquals(300.0, stats["count"]!!.jsonPrimitive.content.toDouble(), "the projected count matches the fitted sample")
        // A continuous fit carries a histogram, projected here.
        assertNotNull(sc["histogram"]?.jsonObject?.get("bins"), "continuous fit projects a histogram")

        // Errors: unknown id, and a non-fit result id.
        assertTrue(tools.getFitDataSummary(buildJsonObject { put("resultId", "nope") }).isError ?: false)
        val run = tools.runModel(buildJsonObject { put("bundleId", "ksl.examples.mm1"); put("modelId", "MM1"); put("numberOfReplications", 3) })
        val runId = structured(run)["resultId"]!!.jsonPrimitive.content
        assertTrue(
            tools.getFitDataSummary(buildJsonObject { put("resultId", runId) }).isError ?: false,
            "a non-fit result should be rejected",
        )
    }

    @Test
    fun `run_model replicationSet yields an independent realization while default reproduces`() = runBlocking {
        suspend fun run(args: JsonObject) = structured(tools.runModel(args))
        fun base() = buildJsonObject { put("bundleId", "ksl.examples.mm1"); put("modelId", "MM1"); put("numberOfReplications", 4) }

        // The canonical run (no replicationSet) and replicationSet=0 are the SAME run:
        // same cache identity, byte-identical config — set 0 must not perturb anything.
        val canonical = run(base())
        val set0 = run(buildJsonObject { base().forEach { k, v -> put(k, v) }; put("replicationSet", 0) })
        assertEquals(canonical["resultId"]!!.jsonPrimitive.content, set0["resultId"]!!.jsonPrimitive.content, "set 0 must equal the standard run")

        // replicationSet=1 is a genuinely different realization: different resultId AND different numbers.
        val set1 = run(buildJsonObject { base().forEach { k, v -> put(k, v) }; put("replicationSet", 1) })
        assertNotEquals(
            canonical["resultId"]!!.jsonPrimitive.content,
            set1["resultId"]!!.jsonPrimitive.content,
            "an independent set should be a distinct cached result",
        )
        fun firstAvg(sc: JsonObject) = sc["responses"]!!.jsonArray.first().jsonObject["average"]!!.jsonPrimitive.content.toDouble()
        assertNotEquals(firstAvg(canonical), firstAvg(set1), "an independent set should produce different numbers")

        // Re-requesting the same set reproduces (cache hit, identical numbers).
        val set1Again = run(buildJsonObject { base().forEach { k, v -> put(k, v) }; put("replicationSet", 1) })
        assertEquals(set1["resultId"]!!.jsonPrimitive.content, set1Again["resultId"]!!.jsonPrimitive.content)
        assertEquals(true, set1Again["cached"]!!.jsonPrimitive.content.toBoolean(), "an identical set re-runs from cache")

        // antithetic is accepted and runs; it is its own realization, distinct from the default.
        val anti = run(buildJsonObject { base().forEach { k, v -> put(k, v) }; put("antithetic", true) })
        assertEquals("completed", anti["type"]!!.jsonPrimitive.content)
        assertNotEquals(canonical["resultId"]!!.jsonPrimitive.content, anti["resultId"]!!.jsonPrimitive.content)

        // A negative replicationSet is rejected cleanly.
        assertTrue(
            tools.runModel(buildJsonObject { base().forEach { k, v -> put(k, v) }; put("replicationSet", -1) }).isError ?: false,
            "a negative replicationSet should be rejected",
        )
    }

    @Test
    fun `config tools accept a TOML or JSON document string as well as a JSON object`() = runBlocking {
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
        val tomlText = ksl.app.config.RunConfigurationToml.encode(document)
        val jsonText = ksl.app.config.RunConfigurationJson.encode(document)
        val jsonObject = Json.parseToJsonElement(jsonText).jsonObject

        // The same document runs whether handed in as a TOML string (e.g. a desktop-app
        // file pasted in), a JSON string, or a JSON object (the original contract).
        assertEquals("completed", structured(tools.runConfig(buildJsonObject { put("config", tomlText) }))["type"]!!.jsonPrimitive.content)
        assertEquals("completed", structured(tools.runConfig(buildJsonObject { put("config", jsonText) }))["type"]!!.jsonPrimitive.content)
        assertEquals("completed", structured(tools.runConfig(buildJsonObject { put("config", jsonObject) }))["type"]!!.jsonPrimitive.content)

        // validate_run_config likewise accepts a TOML string.
        val report = structured(tools.validateRun(buildJsonObject { put("config", tomlText) }))
        assertEquals(true, report["valid"]!!.jsonPrimitive.content.toBoolean(), "TOML document should validate: $report")

        // A malformed string is a clean error (not a crash), and a missing config is an error.
        assertTrue(tools.runConfig(buildJsonObject { put("config", "this is neither json nor toml = = =") }).isError ?: false)
        assertTrue(tools.runConfig(buildJsonObject {}).isError ?: false)
    }

    @Test
    fun `get_workspace reports the active workspace and the MCP app subdirectory`() {
        val sc = structured(tools.getWorkspace())
        // The isolated default workspace is the temp dir injected in setUp.
        assertEquals(tmpWorkspace.toString(), sc["workspace"]!!.jsonPrimitive.content)
        // The app dir is <workspace>/KSLServer (the shared MCP+REST server app folder).
        val appDir = sc["appDir"]!!.jsonPrimitive.content
        assertTrue(appDir.endsWith("KSLServer"), "appDir should be the sanitized app subdir, got $appDir")
        assertTrue(appDir.startsWith(tmpWorkspace.toString()), "appDir should be under the workspace")
        // No override set yet, so this is the default.
        assertEquals(true, sc["isDefault"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `set_workspace persists a new workspace and rejects bad paths`() {
        val newWs = java.nio.file.Files.createTempDirectory("mcp-newws")

        val sc = structured(tools.setWorkspace(buildJsonObject { put("path", newWs.toString()) }))
        assertEquals(newWs.toAbsolutePath().normalize().toString(), sc["workspace"]!!.jsonPrimitive.content)
        assertTrue(sc["appDir"]!!.jsonPrimitive.content.endsWith("KSLServer"))
        assertEquals(tmpWorkspace.toString(), sc["previous"]!!.jsonPrimitive.content, "previous should be the prior default")

        // get_workspace now reflects the override (no longer the default).
        val after = structured(tools.getWorkspace())
        assertEquals(newWs.toAbsolutePath().normalize().toString(), after["workspace"]!!.jsonPrimitive.content)
        assertEquals(false, after["isDefault"]!!.jsonPrimitive.content.toBoolean())

        // A non-existent path is an error and does NOT change the workspace.
        assertTrue(
            tools.setWorkspace(buildJsonObject { put("path", "${newWs}/does/not/exist") }).isError ?: false,
            "a non-existent path should be rejected",
        )
        // A file (not a directory) is an error.
        val aFile = java.nio.file.Files.createTempFile("mcp-notdir", ".txt")
        assertTrue(
            tools.setWorkspace(buildJsonObject { put("path", aFile.toString()) }).isError ?: false,
            "a file path should be rejected",
        )
        // Missing path argument is an error.
        assertTrue(tools.setWorkspace(buildJsonObject {}).isError ?: false)
    }

    @Test
    fun `get_fit_report writes under the workspace, not the dot-ksl settings directory`() = runBlocking {
        val sample = ksl.utilities.random.rvariable.ExponentialRV(10.0).sample(100)
        val fit = tools.fitDataset(
            buildJsonObject { putJsonArray("data") { sample.forEach { add(it) } }; put("name", "svc"); put("kind", "CONTINUOUS") },
        )
        val fitId = structured(fit)["resultId"]!!.jsonPrimitive.content
        val report = tools.getFitReport(buildJsonObject { put("resultId", fitId) })
        assertEquals(false, report.isError ?: false, firstText(report))
        val reportPath = structured(report)["reportPath"]!!.jsonPrimitive.content
        // The report lands under <workspace>/KSLServer/reports/, not ~/.ksl.
        assertTrue(reportPath.startsWith(tmpWorkspace.toString()), "report should be under the workspace, got $reportPath")
        assertTrue("KSLServer" in reportPath && "reports" in reportPath, "report should be in the app reports dir, got $reportPath")
        assertTrue("${java.io.File.separator}.ksl${java.io.File.separator}" !in reportPath, "report must NOT be under ~/.ksl")
        assertTrue(java.nio.file.Files.exists(java.nio.file.Path.of(reportPath)), "the report file should exist")
        java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(reportPath))
    }
}
