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

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
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
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `export_layout` writes an animation layout as an extension-typed `.lay.toml` / `.lay.json` file the desktop
 * animation app can open directly (closing the gap that save_document — a bare-name server document — left).
 */
class McpExportLayoutTest {

    private lateinit var registry: BundleRegistry
    private lateinit var tools: KslMcpTools

    @BeforeTest
    fun setUp() {
        registry = TestBundles.registry()
        val root = Files.createTempDirectory("mcp-export-layout")
        tools = KslMcpTools(registry, ResultStore(root), ArtifactStore(root))
    }

    @AfterTest
    fun tearDown() {
        tools.close()
        registry.close()
    }

    private fun structured(r: CallToolResult) = r.structuredContent!!.jsonObject
    private fun firstText(r: CallToolResult) = (r.content.first() as TextContent).text ?: ""

    /** A scaffold layout JSON for the MM1 dogfood model (has resources + queues). */
    private fun scaffoldJson(): String =
        firstText(tools.autoLayout(buildJsonObject { put("bundleId", "ksl.examples.mm1"); put("modelId", "MM1") }))

    @Test
    @DisplayName("export_layout writes a .lay.toml the app's codec reads back")
    fun exportsTomlThatReadsBack() {
        val dir = Files.createTempDirectory("layouts")
        val res = tools.exportLayout(
            buildJsonObject { put("layout", scaffoldJson()); put("name", "MM1-tweaked"); put("dir", dir.toString()) },
        )
        assertFalse(res.isError ?: false, "should not error: ${firstText(res)}")
        val path = structured(res)["path"]!!.jsonPrimitive.content
        assertTrue(path.endsWith("MM1-tweaked.lay.toml"), "should write a .lay.toml file; got $path")
        assertTrue(Files.exists(Path.of(path)), "the exported file should exist")
        // The app loads via AnimationLayout.read (codec chosen by extension); the exported TOML round-trips.
        val reloaded = AnimationLayout.read(Path.of(path))
        assertTrue(
            reloaded.resources.isNotEmpty() || reloaded.queues.isNotEmpty(),
            "the exported layout should round-trip to a real AnimationLayout",
        )
    }

    @Test
    @DisplayName("export_layout format=json writes .lay.json; an unknown format errors")
    fun jsonFormatAndBadFormat() {
        val dir = Files.createTempDirectory("layouts-json")
        val asJson = tools.exportLayout(
            buildJsonObject {
                put("layout", scaffoldJson()); put("name", "MM1"); put("dir", dir.toString()); put("format", "json")
            },
        )
        assertTrue(structured(asJson)["path"]!!.jsonPrimitive.content.endsWith("MM1.lay.json"))
        val bad = tools.exportLayout(
            buildJsonObject {
                put("layout", scaffoldJson()); put("name", "MM1"); put("dir", dir.toString()); put("format", "xml")
            },
        )
        assertTrue(bad.isError ?: false, "an unknown format should error")
    }

    @Test
    @DisplayName("export_layout requires the target 'dir'")
    fun requiresDir() {
        val res = tools.exportLayout(buildJsonObject { put("layout", scaffoldJson()); put("name", "MM1") })
        assertTrue(res.isError ?: false, "a missing 'dir' should error")
        assertTrue("dir" in firstText(res), "the error should name the missing 'dir'")
    }
}
