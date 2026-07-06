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

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ksl.service.capability.run.BundleRegistry
import ksl.service.store.ArtifactStore
import ksl.service.store.ResultStore
import org.junit.jupiter.api.DisplayName
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end wiring for C3: `run_experiment` with `enableKSLDatabase = true` retains the
 * design's database under the result id, so `experiment_regression` (and the db_* tools)
 * can analyze it; without a database the tool returns graceful "enable it" guidance.
 */
class McpExperimentRegressionTest {

    private lateinit var registry: BundleRegistry
    private lateinit var tools: KslMcpTools
    private lateinit var root: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        registry = TestBundles.registry()
        root = Files.createTempDirectory("mcp-exp-reg")
        tools = KslMcpTools(registry, ResultStore(root), ArtifactStore(root))
    }

    @AfterTest
    fun tearDown() {
        tools.close()
        registry.close()
    }

    private fun structured(r: CallToolResult): JsonObject = r.structuredContent!!.jsonObject
    private fun firstText(r: CallToolResult): String = (r.content.first() as TextContent).text ?: ""

    /** A model with >= 2 numeric controls (a factorial needs >= 2 factors) and a response. */
    private data class Target(
        val bundleId: String,
        val modelId: String,
        val effects: List<String>,
        val response: String,
        val lows: List<Double>,
        val highs: List<Double>,
    )

    private fun target(): Target {
        val found = registry.listBundles().firstNotNullOfOrNull { bundle ->
            bundle.modelIds.firstNotNullOfOrNull { modelId ->
                val d = runCatching { registry.describeModel(bundle.bundleId, modelId) }.getOrNull()
                if (d != null && d.controls.numericControls.size >= 2 && d.responseNames.isNotEmpty())
                    Triple(bundle.bundleId, modelId, d) else null
            }
        }
        assertNotNull(found, "expected an example model with >= 2 numeric controls and a response")
        val (bundleId, modelId, d) = found
        val controls = d.controls.numericControls.take(2)
        return Target(
            bundleId, modelId,
            effects = controls.map { it.keyName },
            response = d.responseNames.first(),
            lows = controls.map { it.value },
            highs = controls.map { it.value + 1.0 },
        )
    }

    private suspend fun runExperiment(t: Target, enableDb: Boolean): String {
        val factors = buildJsonArray {
            t.effects.forEachIndexed { i, key ->
                add(buildJsonObject {
                    put("name", key); put("controlKey", key); put("low", t.lows[i]); put("high", t.highs[i])
                })
            }
        }
        val args = buildJsonObject {
            put("bundleId", t.bundleId); put("modelId", t.modelId); put("factors", factors)
            put("numRepsPerDesignPoint", 2)
            if (enableDb) put("enableKSLDatabase", true)
        }
        return structured(tools.runExperiment(args))["resultId"]!!.jsonPrimitive.content
    }

    private fun effectsArray(t: Target) = buildJsonArray { t.effects.forEach { add(it) } }

    @Test
    @DisplayName("enableKSLDatabase retains the experiment; experiment_regression renders an HTML report")
    fun regressionOnRetainedExperiment() = runBlocking {
        val t = target()
        val resultId = runExperiment(t, enableDb = true)

        // Retention bonus: the db_* tools now also see the experiment's database.
        val status = structured(tools.dbStatus(buildJsonObject { put("resultId", resultId) }))
        assertTrue(status["present"]!!.jsonPrimitive.content.toBoolean(), "experiment DB should be retained; got $status")

        // experiment_regression renders a downloadable HTML artifact.
        val reg = structured(tools.experimentRegression(buildJsonObject {
            put("resultId", resultId); put("responseName", t.response); put("effects", effectsArray(t))
        }))
        val artifacts = reg["artifacts"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(
            artifacts.any { it.contains("regression") && it.endsWith(".html") },
            "a regression HTML artifact was expected; got $artifacts",
        )
    }

    @Test
    @DisplayName("experiment_regression guides you to enable the database when there is none")
    fun regressionWithoutDatabaseGuides() = runBlocking {
        val t = target()
        val resultId = runExperiment(t, enableDb = false)

        val reg = tools.experimentRegression(buildJsonObject {
            put("resultId", resultId); put("responseName", t.response); put("effects", effectsArray(t))
        })
        // Graceful NoDatabase envelope + guidance to re-run with the database — not a crash.
        assertTrue(
            "present" in structured(reg) && !structured(reg)["present"]!!.jsonPrimitive.content.toBoolean(),
            "expected a no-database envelope; got ${structured(reg)}",
        )
        assertTrue(
            firstText(reg).contains("enableKSLDatabase"),
            "should guide the analyst to enable the database; got ${firstText(reg)}",
        )
    }
}
