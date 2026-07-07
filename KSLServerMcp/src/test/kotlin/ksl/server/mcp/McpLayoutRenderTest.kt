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
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ksl.service.capability.run.BundleRegistry
import ksl.service.store.ArtifactStore
import ksl.service.store.ResultStore
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers Theme E4: `render_animation_layout` draws a static PNG preview of a layout, returned
 * inline (so a vision model can see it) plus as a downloadable artifact.
 */
class McpLayoutRenderTest {

    private lateinit var registry: BundleRegistry
    private lateinit var tools: KslMcpTools

    @BeforeTest
    fun setUp() {
        registry = TestBundles.registry()
        val root = Files.createTempDirectory("mcp-layout-render")
        tools = KslMcpTools(registry, ResultStore(root), ArtifactStore(root))
    }

    @AfterTest
    fun tearDown() {
        tools.close()
        registry.close()
    }

    private fun firstText(r: CallToolResult) = (r.content.filterIsInstance<TextContent>().first()).text ?: ""

    @Test
    fun `render_animation_layout produces a valid PNG from a scaffold, inline and as an artifact`() {
        // A scaffold layout for the MM1 dogfood model (E2), then render it (E4).
        val layoutJson = firstText(
            tools.autoLayout(buildJsonObject { put("bundleId", "ksl.examples.mm1"); put("modelId", "MM1") }),
        )
        val result = tools.renderAnimationLayout(buildJsonObject { put("layout", layoutJson) })
        assertFalse(result.isError ?: false, "render should not error: ${firstText(result)}")

        // The image is returned inline as PNG, and the base64 decodes to a real image.
        val image = result.content.filterIsInstance<ImageContent>().firstOrNull()
        assertNotNull(image, "expected an inline image in the result")
        assertEquals("image/png", image.mimeType)
        val decoded = ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(image.data)))
        assertNotNull(decoded, "the inline base64 should decode to a PNG")
        assertTrue(decoded.width > 0 && decoded.height > 0, "the decoded image should have positive dimensions")

        // The same PNG is a downloadable artifact that decodes on disk.
        val sc = result.structuredContent!!.jsonObject
        val pngRef = sc["artifacts"]!!.jsonArray.map { it.jsonObject }
            .firstOrNull { it["name"]!!.jsonPrimitive.content.endsWith(".png") }
        assertNotNull(pngRef, "expected a .png artifact; got ${sc["artifacts"]}")
        val onDisk = ImageIO.read(File(pngRef["path"]!!.jsonPrimitive.content))
        assertNotNull(onDisk, "the .png artifact should decode as an image")
    }

    @Test
    fun `render_animation_layout rejects an unparseable layout`() {
        val result = tools.renderAnimationLayout(buildJsonObject { put("layout", "this is not a layout") })
        assertTrue(result.isError ?: false, "an unparseable layout should be an error")
    }
}
