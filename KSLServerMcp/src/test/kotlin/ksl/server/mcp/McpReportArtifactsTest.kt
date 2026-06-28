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

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ksl.app.config.ExperimentRunOverrides
import ksl.app.config.ModelReference
import ksl.app.config.OutputConfig
import ksl.app.config.RunConfiguration
import ksl.app.config.RunConfigurationJson
import ksl.app.config.ScenarioSpec
import ksl.app.config.TraceResponseSpec
import ksl.app.config.WelchResponseSpec
import ksl.service.capability.run.BundleRegistry
import ksl.service.store.ArtifactStore
import ksl.service.store.ResultStore
import org.junit.jupiter.api.DisplayName
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end Phase B wiring over the MCP run_config tool: a capture-enabled
 * RunConfiguration auto-materializes Welch + trace report artifacts, listed by
 * get_artifacts. The lets-plot Swing frontend is excluded from this module, so
 * rendering runs headless.
 */
class McpReportArtifactsTest {

    private lateinit var registry: BundleRegistry
    private lateinit var tools: KslMcpTools

    @BeforeTest
    fun setUp() {
        registry = TestBundles.registry()
        val root = Files.createTempDirectory("mcp-report-e2e")
        tools = KslMcpTools(registry, ResultStore(root), ArtifactStore(root))
    }

    @AfterTest
    fun tearDown() {
        tools.close()
        registry.close()
    }

    private fun captureDoc(): String = RunConfigurationJson.encode(
        RunConfiguration(
            scenarios = listOf(
                ScenarioSpec(
                    name = "single",
                    modelReference = ModelReference.ByProviderId("MM1"),
                    runOverrides = ExperimentRunOverrides(numberOfReplications = 4, lengthOfReplication = 2000.0),
                ),
            ),
            outputConfig = OutputConfig(
                enableWelchAnalysis = true,
                welchResponses = listOf(WelchResponseSpec("System Time", 1.0)),
                enableResponseTrace = true,
                traceResponses = listOf(
                    TraceResponseSpec("System Time", maxReplications = 1),
                    TraceResponseSpec("Num in System", maxReplications = 1),
                ),
            ),
        ),
    )

    @Test
    @DisplayName("run_config with capture enabled auto-materializes Welch + trace artifacts")
    fun runConfigProducesReportArtifacts() = runBlocking {
        val card = tools.runConfig(buildJsonObject { put("config", captureDoc()) })
        val resultId = card.structuredContent!!.jsonObject["resultId"]!!.jsonPrimitive.content

        val artifacts = tools.getArtifacts(buildJsonObject { put("resultId", resultId) })
            .structuredContent!!.jsonObject["artifacts"]!!.jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(artifacts.contains("welch.html"), "expected welch.html; got $artifacts")
        assertTrue(artifacts.contains("trace.html"), "expected trace.html; got $artifacts")
    }
}
