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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ksl.app.config.ExperimentRunOverrides
import ksl.app.config.ModelReference
import ksl.app.config.RunConfiguration
import ksl.app.config.RunConfigurationJson
import ksl.app.config.ScenarioSpec
import ksl.service.capability.run.BundleRegistry
import ksl.service.store.ArtifactStore
import ksl.service.store.ResultStore
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers Theme B0 (discoverability): orientation is reachable by *push* — a model-callable
 * `get_started` tool, server `instructions`, and in-flow `nextSteps` signposting — not only by
 * the user picking a prompt.
 */
class McpDiscoverabilityTest {

    private lateinit var registry: BundleRegistry
    private lateinit var tools: KslMcpTools

    @BeforeTest
    fun setUp() {
        registry = TestBundles.registry()
        val root = Files.createTempDirectory("mcp-discover")
        tools = KslMcpTools(registry, ResultStore(root), ArtifactStore(root))
    }

    @AfterTest
    fun tearDown() {
        tools.close()
        registry.close()
    }

    private fun firstText(r: CallToolResult): String = (r.content.first() as TextContent).text ?: ""

    // ---- Renderers (pure) ----

    @Test
    fun `serverInstructions state the scope boundary, seed example questions, and list the catalog`() {
        val text = KslMcpPrompts.serverInstructions(tools.availableBundles())
        assertTrue("do not write" in text, "should state the scope boundary; got:\n$text")
        assertTrue("get_started" in text, "should point at get_started")
        assertTrue("Example questions" in text, "should seed example questions")
        // The live catalog is present (a real bundle id from the dogfood registry).
        assertTrue(
            tools.availableBundles().any { it.bundleId in text },
            "should list at least one available bundle; got:\n$text",
        )
    }

    @Test
    fun `serverInstructions degrade gracefully with an empty catalog`() {
        val text = KslMcpPrompts.serverInstructions(emptyList())
        assertTrue("no bundles are currently loaded" in text, "empty catalog should be stated; got:\n$text")
        assertTrue("do not write" in text && "Example questions" in text, "scope + examples still present")
    }

    @Test
    fun `getStartedGuidance carries the scope boundary and example questions`() {
        val text = KslMcpPrompts.getStartedGuidance(tools.availableBundles())
        assertTrue("do not write" in text, "get_started should state the scope boundary")
        assertTrue("Example questions" in text, "get_started should seed example questions")
    }

    // ---- The get_started tool + registration ----

    @Test
    fun `get_started tool returns non-error orientation naming the catalog`() {
        val result = tools.getStarted()
        assertFalse(result.isError ?: false, "get_started should not be an error")
        val text = firstText(result)
        assertTrue("do not write" in text && "Example questions" in text, "orientation content expected")
        assertTrue(
            tools.availableBundles().any { it.bundleId in text },
            "should surface the live catalog; got:\n$text",
        )
    }

    @Test
    fun `server registers get_started as a tool (push, not only the prompt)`() {
        val server = KslMcpServer.build(tools)
        assertTrue("get_started" in server.tools.keys, "get_started should be registered as a tool; got ${server.tools.keys}")
    }

    // ---- In-flow signposting ----

    @Test
    fun `a scenario-batch summary carries next-steps signposting and the database-gate note`() = runBlocking {
        val doc = RunConfigurationJson.encode(
            RunConfiguration(
                scenarios = listOf("baseline", "alt").map {
                    ScenarioSpec(
                        name = it,
                        modelReference = ModelReference.ByProviderId("MM1"),
                        runOverrides = ExperimentRunOverrides(numberOfReplications = 3, lengthOfReplication = 500.0),
                    )
                },
            ),
        )
        val text = firstText(tools.runConfig(buildJsonObject { put("config", doc) }))
        assertTrue("Next steps" in text, "the batch summary should signpost next steps; got:\n$text")
        assertTrue("db_compare" in text && "experiment_regression" in text, "should route to comparison + regression")
        assertTrue("enableKSLDatabase" in text, "should carry the database-gate note (4a)")
    }
}
