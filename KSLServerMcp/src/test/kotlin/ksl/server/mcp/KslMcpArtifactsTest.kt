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
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tests for the Phase A MCP artifact tools (`get_artifacts` / `get_artifact`). */
class KslMcpArtifactsTest {

    private lateinit var registry: BundleRegistry
    private lateinit var tools: KslMcpTools
    private lateinit var root: Path

    @BeforeTest
    fun setUp() {
        registry = TestBundles.registry()
        root = Files.createTempDirectory("mcp-artifacts")
        tools = KslMcpTools(registry, ResultStore(root), ArtifactStore(root))
    }

    @AfterTest
    fun tearDown() {
        tools.close()
        registry.close()
    }

    private fun structured(r: CallToolResult) = r.structuredContent!!.jsonObject
    private fun firstText(r: CallToolResult) = (r.content.first() as TextContent).text ?: ""

    private fun seed(resultId: String, name: String, body: String) {
        val dir = root.resolve(resultId).resolve("artifacts")
        val target = dir.resolve(name)
        target.parent.createDirectories()
        target.writeText(body)
    }

    @Test
    @DisplayName("get_artifacts lists every artifact with name + media type")
    fun getArtifactsLists() {
        seed("res1", "welch.html", "<html>w</html>")
        seed("res1", "plots/welch.png", "PNG")

        val out = structured(tools.getArtifacts(buildJsonObject { put("resultId", "res1") }))
        val names = out["artifacts"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(names.containsAll(listOf("welch.html", "plots/welch.png")), "lists both artifacts; got $names")
        val htmlType = out["artifacts"]!!.jsonArray
            .first { it.jsonObject["name"]!!.jsonPrimitive.content == "welch.html" }
            .jsonObject["mediaType"]!!.jsonPrimitive.content
        assertEquals("text/html", htmlType)
    }

    @Test
    @DisplayName("get_artifacts reports none for an unknown result")
    fun getArtifactsEmpty() {
        val out = structured(tools.getArtifacts(buildJsonObject { put("resultId", "nope") }))
        assertTrue(out["artifacts"]!!.jsonArray.isEmpty())
    }

    @Test
    @DisplayName("get_artifact inlines text content and carries the on-disk path")
    fun getArtifactInlinesText() {
        seed("res1", "welch.html", "<html>welch</html>")
        val r = tools.getArtifact(buildJsonObject { put("resultId", "res1"); put("name", "welch.html") })
        val out = structured(r)
        assertEquals("<html>welch</html>", out["content"]!!.jsonPrimitive.content, "text artifact is inlined")
        assertEquals("<html>welch</html>", firstText(r), "summary text is the artifact body")
        assertTrue(out["path"]!!.jsonPrimitive.content.endsWith("welch.html"), "carries the on-disk path")
    }

    @Test
    @DisplayName("get_artifact errors for a missing or traversing name")
    fun getArtifactMissing() {
        seed("res1", "welch.html", "x")
        assertTrue(tools.getArtifact(buildJsonObject { put("resultId", "res1"); put("name", "missing.html") }).isError == true)
        assertTrue(tools.getArtifact(buildJsonObject { put("resultId", "res1"); put("name", "../result.json") }).isError == true)
    }
}
