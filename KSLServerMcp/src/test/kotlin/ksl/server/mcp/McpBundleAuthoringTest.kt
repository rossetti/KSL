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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import ksl.app.bundle.BundleLoader
import ksl.examples.general.appsupport.MM1ModelBuilder
import ksl.examples.general.appsupport.ManifestBundleFixtures
import ksl.service.capability.run.BundleRegistry
import ksl.service.store.ArtifactStore
import ksl.service.store.ResultStore
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end for the LLM-driven bundle authoring (G0): `bundle_authoring_candidates` → an authoring
 * payload → `assemble_bundle` produces a loadable bundle carrying the authored identity + catalog;
 * `preview_bundle_authoring` flags a bad payload without writing.
 */
class McpBundleAuthoringTest {

    private lateinit var registry: BundleRegistry
    private lateinit var tools: KslMcpTools

    @BeforeTest
    fun setUp() {
        registry = TestBundles.registry()
        val root = Files.createTempDirectory("mcp-bundle-auth-store")
        tools = KslMcpTools(registry, ResultStore(root), ArtifactStore(root))
    }

    @AfterTest
    fun tearDown() {
        tools.close()
        registry.close()
    }

    private fun structured(r: CallToolResult) = r.structuredContent!!.jsonObject

    private fun buildersJar(prefix: String) =
        ManifestBundleFixtures.buildersJar(Files.createTempDirectory(prefix), "mm1-builders", MM1ModelBuilder::class.java)

    private fun firstBuilderClass(jarPath: String): String =
        structured(tools.bundleAuthoringCandidates(buildJsonObject { put("buildersJarPath", jarPath) }))["models"]!!
            .jsonArray.first().jsonObject["builderClass"]!!.jsonPrimitive.content

    @Test
    fun `candidates then assemble_bundle produces a loadable bundle with the authored identity and catalog`() {
        val jar = buildersJar("mcp-bundle-auth")
        val candModel = structured(tools.bundleAuthoringCandidates(buildJsonObject { put("buildersJarPath", jar.toString()) }))["models"]!!
            .jsonArray.first().jsonObject
        val builderClass = candModel["builderClass"]!!.jsonPrimitive.content
        val inputKey = candModel["inputs"]!!.jsonArray.first().jsonObject["key"]!!.jsonPrimitive.content
        val output = jar.parent.resolve("mm1.jar")

        val authoring = buildJsonObject {
            putJsonObject("identity") { put("bundleId", "edu.test.mcp.mm1"); put("displayName", "Test MM1") }
            putJsonArray("models") {
                add(buildJsonObject {
                    put("builderClass", builderClass)
                    put("displayName", "M/M/1 Queue")
                    putJsonArray("supportedApps") { add("SINGLE"); add("SCENARIO") }
                    putJsonObject("catalog") {
                        putJsonArray("inputs") { add(buildJsonObject { put("key", inputKey); put("displayName", "Authored Input") }) }
                    }
                })
            }
        }
        val result = structured(tools.assembleBundle(buildJsonObject {
            put("buildersJarPath", jar.toString()); put("authoring", authoring); put("outputPath", output.toString())
        }))
        assertTrue(result["ok"]!!.jsonPrimitive.content.toBoolean(), "assemble should succeed; got $result")
        assertEquals(output.toString(), result["output"]!!.jsonPrimitive.content)

        val loaded = BundleLoader.loadJar(output)
        try {
            assertEquals("edu.test.mcp.mm1", loaded.first().bundle.bundleId, "the authored bundleId should load back")
        } finally {
            loaded.forEach { runCatching { it.close() } }
        }
    }

    @Test
    fun `preview_bundle_authoring flags a blank bundleId and writes nothing`() {
        val jar = buildersJar("mcp-bundle-auth-bad")
        val authoring = buildJsonObject {
            putJsonObject("identity") { put("bundleId", "") }
            putJsonArray("models") { add(buildJsonObject { put("builderClass", firstBuilderClass(jar.toString())) }) }
        }
        val result = structured(tools.previewBundleAuthoring(buildJsonObject {
            put("buildersJarPath", jar.toString()); put("authoring", authoring)
        }))
        assertFalse(result["ok"]!!.jsonPrimitive.content.toBoolean(), "a blank bundleId should not be ok; got $result")
        assertTrue(result["findings"]!!.jsonArray.isNotEmpty(), "expected validation findings")
        assertTrue(Files.list(jar.parent).use { s -> s.noneMatch { it.fileName.toString() == "mm1.jar" } },
            "preview must not write a bundle")
    }
}
