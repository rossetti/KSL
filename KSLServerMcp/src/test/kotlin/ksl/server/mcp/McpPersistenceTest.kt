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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ksl.service.capability.run.BundleRegistry
import ksl.service.store.ArtifactStore
import ksl.service.store.DocumentStore
import ksl.service.store.ResultStore
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers Theme G2 (workspace persistence & discovery): save/reload/list/delete named documents (configs
 * + layouts) so a user can restart work, and list_results so a returning user can find + fetch every
 * result the server produced.
 */
class McpPersistenceTest {

    private lateinit var registry: BundleRegistry
    private lateinit var tools: KslMcpTools

    @BeforeTest
    fun setUp() {
        registry = TestBundles.registry()
        val root = Files.createTempDirectory("mcp-persistence")
        tools = KslMcpTools(registry, ResultStore(root), ArtifactStore(root), DocumentStore(root.resolve("documents")))
    }

    @AfterTest
    fun tearDown() {
        tools.close()
        registry.close()
    }

    private fun structured(r: CallToolResult) = r.structuredContent!!.jsonObject

    @Test
    fun `save then load a layout document round-trips, plus list and delete`() {
        val content = """{"title":"My Layout","width":800.0,"height":600.0}"""
        val saved = structured(tools.saveDocument(buildJsonObject {
            put("kind", "layout"); put("name", "baseline"); put("content", content)
        }))
        assertEquals("baseline", saved["name"]!!.jsonPrimitive.content)

        val loaded = structured(tools.loadDocument(buildJsonObject { put("kind", "layout"); put("name", "baseline") }))
        assertEquals(content, loaded["content"]!!.jsonPrimitive.content, "the saved layout should reload verbatim")

        val listed = structured(tools.listDocuments(buildJsonObject { put("kind", "layout") }))["documents"]!!.jsonArray
        assertTrue(listed.any { it.jsonObject["name"]!!.jsonPrimitive.content == "baseline" }, "the layout should be listed")

        val deleted = structured(tools.deleteDocument(buildJsonObject { put("kind", "layout"); put("name", "baseline") }))
        assertEquals("true", deleted["deleted"]!!.jsonPrimitive.content)
        assertTrue(tools.loadDocument(buildJsonObject { put("kind", "layout"); put("name", "baseline") }).isError ?: false,
            "loading a deleted document should error")
    }

    @Test
    fun `list_results surfaces a produced run so it can be found later`() = runBlocking {
        val run = structured(tools.runModel(buildJsonObject {
            put("bundleId", "ksl.examples.mm1"); put("modelId", "MM1")
            put("numberOfReplications", 2); put("lengthOfReplication", 100.0)
        }))
        val resultId = run["resultId"]!!.jsonPrimitive.content

        val results = structured(tools.listResults(null))["results"]!!.jsonArray.map { it.jsonObject }
        val row = results.firstOrNull { it["resultId"]!!.jsonPrimitive.content == resultId }
        assertTrue(row != null, "the produced run should be discoverable via list_results; got $results")
        assertEquals("RUN", row["kind"]!!.jsonPrimitive.content, "a single run's kind is RUN")
    }
}
