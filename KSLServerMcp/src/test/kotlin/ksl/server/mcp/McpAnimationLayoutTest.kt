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
import ksl.animation.AnimationLayout
import ksl.animation.scaffoldLayout
import ksl.service.capability.run.BundleRegistry
import ksl.service.store.ArtifactStore
import ksl.service.store.ResultStore
import org.junit.jupiter.api.DisplayName
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers Theme E2: the model-only animation-layout tools. A scaffold validates clean by
 * construction; a mis-named binding is surfaced with the expected UNMATCHED_* issue and a
 * nearest-name hint — no run, no trace.
 */
class McpAnimationLayoutTest {

    private lateinit var registry: BundleRegistry
    private lateinit var tools: KslMcpTools

    @BeforeTest
    fun setUp() {
        registry = TestBundles.registry()
        val root = Files.createTempDirectory("mcp-anim-layout")
        tools = KslMcpTools(registry, ResultStore(root), ArtifactStore(root))
    }

    @AfterTest
    fun tearDown() {
        tools.close()
        registry.close()
    }

    private fun structured(r: CallToolResult) = r.structuredContent!!.jsonObject
    private fun firstText(r: CallToolResult) = (r.content.first() as TextContent).text ?: ""

    /** A dogfood model whose scaffold binds at least one queue (so it can be mis-named). */
    private data class Target(val bundleId: String, val modelId: String, val layout: AnimationLayout)

    private fun target(): Target {
        val t = registry.listBundles().firstNotNullOfOrNull { b ->
            b.modelIds.firstNotNullOfOrNull { m ->
                val layout = runCatching { registry.modelProvider().provideModel(b.bundleId, m).scaffoldLayout() }.getOrNull()
                if (layout != null && layout.queues.isNotEmpty()) Target(b.bundleId, m, layout) else null
            }
        }
        assertNotNull(t, "expected a dogfood model whose scaffold binds at least one queue")
        return t
    }

    @Test
    @DisplayName("auto_layout (no trace) scaffolds a layout that validates clean")
    fun scaffoldValidatesClean() {
        val t = target()
        val template = tools.autoLayout(buildJsonObject { put("bundleId", t.bundleId); put("modelId", t.modelId) })
        assertFalse(template.isError ?: false, "template should not error: ${firstText(template)}")
        val layoutJson = firstText(template)

        val v = structured(tools.validateAnimationLayout(buildJsonObject {
            put("bundleId", t.bundleId); put("modelId", t.modelId); put("layout", layoutJson)
        }))
        assertTrue(v["isValid"]!!.jsonPrimitive.content.toBoolean(), "the scaffold should validate clean; got $v")
        assertTrue(v["issues"]!!.jsonArray.isEmpty(), "no issues expected for a scaffold; got $v")
    }

    @Test
    @DisplayName("validate_animation_layout flags a mis-named queue binding with a suggestion")
    fun validateFlagsMisnamedBinding() {
        val t = target()
        val realQueue = t.layout.queues.first().queueName
        val bogus = realQueue + "X"   // a one-char near-miss so the "did you mean" hint fires
        val corrupted = t.layout.copy(queues = listOf(t.layout.queues.first().copy(queueName = bogus))).toJson()

        val v = structured(tools.validateAnimationLayout(buildJsonObject {
            put("bundleId", t.bundleId); put("modelId", t.modelId); put("layout", corrupted)
        }))
        assertFalse(v["isValid"]!!.jsonPrimitive.content.toBoolean(), "the corrupted layout should be invalid; got $v")
        val queueIssue = v["issues"]!!.jsonArray.map { it.jsonObject }
            .firstOrNull { it["kind"]!!.jsonPrimitive.content == "UNMATCHED_QUEUE" }
        assertNotNull(queueIssue, "expected an UNMATCHED_QUEUE issue; got ${v["issues"]}")
        assertEquals(bogus, queueIssue["name"]!!.jsonPrimitive.content)
        val message = queueIssue["message"]!!.jsonPrimitive.content
        assertTrue("Did you mean" in message && realQueue in message,
            "the message should suggest the real queue '$realQueue'; got: $message")
    }
}
