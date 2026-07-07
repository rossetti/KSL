/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ksl.service.capability.run.BundleRegistry
import ksl.service.store.ArtifactStore
import ksl.service.store.ResultStore
import org.junit.jupiter.api.DisplayName
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A completed run writes a human-readable `meta.json` into its content-hash result folder, so a user browsing
 * `KSLWork/KSLServer/runs/<hash>/` can tell what the run was without the server's tools.
 */
class McpRunMetaTest {

    private lateinit var registry: BundleRegistry
    private lateinit var tools: KslMcpTools
    private lateinit var root: Path

    @BeforeTest
    fun setUp() {
        registry = TestBundles.registry()
        root = Files.createTempDirectory("mcp-run-meta")
        tools = KslMcpTools(registry, ResultStore(root), ArtifactStore(root))
    }

    @AfterTest
    fun tearDown() {
        tools.close()
        registry.close()
    }

    @Test
    @DisplayName("a traced run writes a self-describing meta.json into its result folder")
    fun tracedRunWritesMeta() {
        val res = runBlocking {
            tools.runModel(
                buildJsonObject {
                    put("bundleId", "ksl.examples.mm1"); put("modelId", "MM1")
                    put("numberOfReplications", 1); put("lengthOfReplication", 100.0); put("tracing", true)
                },
            )
        }
        val resultId = res.structuredContent!!.jsonObject["resultId"]!!.jsonPrimitive.content
        val meta = root.resolve(resultId).resolve("meta.json")
        assertTrue(Files.exists(meta), "the run folder should carry a meta.json")
        val obj = Json.parseToJsonElement(Files.readString(meta)).jsonObject
        assertEquals("run", obj["kind"]!!.jsonPrimitive.content)
        assertTrue(obj.containsKey("model"), "meta.json should name the model; got $obj")
    }
}
