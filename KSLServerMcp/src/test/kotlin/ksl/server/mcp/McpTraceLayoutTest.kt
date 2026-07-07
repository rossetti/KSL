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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ksl.animation.AnimationLayout
import ksl.service.capability.run.BundleRegistry
import ksl.service.store.ArtifactStore
import ksl.service.store.ResultStore
import org.junit.jupiter.api.DisplayName
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The unified `auto_layout` tool: given a traced run's resultId it mines a richer layout from the
 * captured animation trace (the replay closure relocated into KSLCore) and stamps the model's
 * geometry; without a resultId it returns the model scaffold; with an untraced run it guides the
 * analyst to enable tracing.
 */
class McpTraceLayoutTest {

    private lateinit var registry: BundleRegistry
    private lateinit var tools: KslMcpTools

    @BeforeTest
    fun setUp() {
        registry = TestBundles.registry()
        val root = Files.createTempDirectory("mcp-trace-layout")
        tools = KslMcpTools(registry, ResultStore(root), ArtifactStore(root))
    }

    @AfterTest
    fun tearDown() {
        tools.close()
        registry.close()
    }

    private fun structured(r: CallToolResult) = r.structuredContent!!.jsonObject
    private fun firstText(r: CallToolResult) = (r.content.first() as TextContent).text ?: ""

    private fun runMM1(tracing: Boolean): String {
        val args = buildJsonObject {
            put("bundleId", "ksl.examples.mm1"); put("modelId", "MM1")
            put("numberOfReplications", 1); put("lengthOfReplication", 200.0)
            if (tracing) put("tracing", true)
        }
        return structured(runBlocking { tools.runModel(args) })["resultId"]!!.jsonPrimitive.content
    }

    @Test
    @DisplayName("auto_layout mines a usable layout from a traced run")
    fun minesLayoutFromTrace() {
        val resultId = runMM1(tracing = true)
        val result = tools.autoLayout(buildJsonObject {
            put("bundleId", "ksl.examples.mm1"); put("modelId", "MM1"); put("resultId", resultId)
        })
        assertFalse(result.isError ?: false, "should not error: ${firstText(result)}")
        assertTrue(structured(result)["source"]?.jsonPrimitive?.content == "trace", "should have mined the trace")

        // The mined layout parses back to a real AnimationLayout that places observed elements.
        val layout = AnimationLayout.fromJson(firstText(result))
        assertTrue(
            layout.queues.isNotEmpty() || layout.resources.isNotEmpty() || layout.movableResources.isNotEmpty(),
            "the trace-informed layout should place the observed queues/resources; got ${firstText(result)}",
        )
    }

    @Test
    @DisplayName("auto_layout without a resultId returns the model scaffold")
    fun withoutResultIdReturnsScaffold() {
        val result = tools.autoLayout(buildJsonObject { put("bundleId", "ksl.examples.mm1"); put("modelId", "MM1") })
        assertFalse(result.isError ?: false, "scaffold should not error: ${firstText(result)}")
        assertTrue(structured(result)["source"]?.jsonPrimitive?.content == "scaffold", "no resultId ⇒ the model scaffold")
        val layout = AnimationLayout.fromJson(firstText(result))
        assertTrue(
            layout.queues.isNotEmpty() || layout.resources.isNotEmpty() || layout.movableResources.isNotEmpty(),
            "the scaffold places the model's elements; got ${firstText(result)}",
        )
    }

    @Test
    @DisplayName("auto_layout with an untraced run's resultId guides to enable tracing")
    fun untracedResultIdGuides() {
        val resultId = runMM1(tracing = false)
        val result = tools.autoLayout(buildJsonObject {
            put("bundleId", "ksl.examples.mm1"); put("modelId", "MM1"); put("resultId", resultId)
        })
        assertTrue(result.isError ?: false, "should error when the named result has no trace")
        assertTrue("tracing" in firstText(result), "should guide to enable tracing; got ${firstText(result)}")
    }
}
