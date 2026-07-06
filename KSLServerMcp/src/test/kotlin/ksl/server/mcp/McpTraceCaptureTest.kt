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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ksl.animation.TraceFileReader
import ksl.service.capability.run.BundleRegistry
import ksl.service.store.ArtifactStore
import ksl.service.store.ResultStore
import org.junit.jupiter.api.DisplayName
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers Theme E1: an opt-in animation trace is captured by a server run and registered as a
 * downloadable `.atf` artifact that parses back to events with the KSLCore reader.
 */
class McpTraceCaptureTest {

    private lateinit var registry: BundleRegistry
    private lateinit var tools: KslMcpTools

    @BeforeTest
    fun setUp() {
        registry = TestBundles.registry()
        val root = Files.createTempDirectory("mcp-trace")
        tools = KslMcpTools(registry, ResultStore(root), ArtifactStore(root))
    }

    @AfterTest
    fun tearDown() {
        tools.close()
        registry.close()
    }

    private fun structured(r: CallToolResult) = r.structuredContent!!.jsonObject
    private fun artifactNames(sc: kotlinx.serialization.json.JsonObject): List<String> =
        (sc["artifacts"] as? JsonArray)?.map { it.jsonObject["name"]!!.jsonPrimitive.content } ?: emptyList()

    @Test
    @DisplayName("run_model with tracing=true produces a downloadable .atf that parses to events")
    fun tracingProducesParseableAtf() = runBlocking {
        val sc = structured(tools.runModel(buildJsonObject {
            put("bundleId", "ksl.examples.mm1"); put("modelId", "MM1")
            put("numberOfReplications", 1); put("lengthOfReplication", 200.0)
            put("tracing", true)
        }))
        val resultId = sc["resultId"]!!.jsonPrimitive.content
        val atf = artifactNames(sc).firstOrNull { it.endsWith(".atf") }
        assertNotNull(atf, "expected a .atf artifact from a traced run; got ${sc["artifacts"]}")

        // get_artifact returns the trace file; parse it back with the KSLCore reader.
        val fetched = structured(tools.getArtifact(buildJsonObject { put("resultId", resultId); put("name", atf) }))
        val path = fetched["path"]!!.jsonPrimitive.content
        val (header, events) = TraceFileReader.readAll(Path.of(path))
        assertNotNull(header, "the .atf should carry a parseable header")
        assertTrue(events.isNotEmpty(), "the trace should contain animation events")
    }

    @Test
    @DisplayName("a run without tracing produces no .atf artifact")
    fun noTracingNoAtf() = runBlocking {
        val sc = structured(tools.runModel(buildJsonObject {
            put("bundleId", "ksl.examples.mm1"); put("modelId", "MM1")
            put("numberOfReplications", 1); put("lengthOfReplication", 200.0)
        }))
        assertTrue(artifactNames(sc).none { it.endsWith(".atf") }, "no .atf expected without tracing")
    }
}
