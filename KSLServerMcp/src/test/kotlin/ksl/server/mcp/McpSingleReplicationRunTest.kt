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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
import kotlin.test.assertTrue

/**
 * Boundary guard for F-2: a single-replication run has no sample dispersion, so KSL yields NaN std dev /
 * std err / half width. NaN is not a valid JSON number and fails the MCP SDK's output-schema validation at
 * the transport boundary — a failure the handler-level unit tests never exercised (they inspect the
 * CallToolResult directly, never crossing SDK validation). run_model must report those undefined statistics
 * as null, which the run output schema now permits.
 */
class McpSingleReplicationRunTest {

    private lateinit var registry: BundleRegistry
    private lateinit var tools: KslMcpTools

    @BeforeTest
    fun setUp() {
        registry = TestBundles.registry()
        val root = Files.createTempDirectory("mcp-single-rep")
        tools = KslMcpTools(registry, ResultStore(root), ArtifactStore(root))
    }

    @AfterTest
    fun tearDown() {
        tools.close()
        registry.close()
    }

    private fun structured(r: CallToolResult): JsonObject = r.structuredContent!!.jsonObject

    @Test
    @DisplayName("a single-replication run reports null (never NaN) for the undefined dispersion statistics")
    fun singleReplicationRunHasNullDispersionNotNaN() = runBlocking {
        val run = structured(tools.runModel(buildJsonObject {
            put("bundleId", "ksl.examples.mm1"); put("modelId", "MM1")
            put("numberOfReplications", 1); put("lengthOfReplication", 1000.0)
        }))
        val responses = run["responses"]!!.jsonArray.map { it.jsonObject }
        assertTrue(responses.isNotEmpty(), "a completed run should report responses")
        for (r in responses) {
            val name = r["name"]!!.jsonPrimitive.content
            // With one replication there is no sample dispersion — these must be JSON null, not NaN.
            for (field in listOf("stdDev", "stdErr", "halfWidth")) {
                assertTrue(
                    r[field] is JsonNull,
                    "response '$name' $field must be null for a single replication (not NaN); got ${r[field]}",
                )
            }
            // The point statistics remain defined and must be finite JSON numbers (NaN/±∞ are invalid).
            for (field in listOf("average", "min", "max")) {
                val v = r[field]
                if (v != null && v !is JsonNull) {
                    val d = v.jsonPrimitive.content.toDoubleOrNull()
                    assertTrue(d != null && d.isFinite(), "response '$name' $field must be a finite number; got $v")
                }
            }
        }
    }
}
