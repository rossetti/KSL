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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ksl.app.moda.MetricSpec
import ksl.app.moda.ModaDocument
import ksl.app.moda.ModaDocumentFormats
import ksl.app.moda.ModaSourceReference
import ksl.service.capability.run.BundleRegistry
import ksl.service.store.ArtifactStore
import ksl.service.store.ResultStore
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Tests the decision-study tools as an agent calling them would meet them.
 *
 *  An agent works from what a tool says back, so both halves of a reply matter: the structured part
 *  another program reads, and the text an agent reasons over. A reply that is correct but says
 *  nothing an agent can act on is not much use, so the text is checked as well as the data.
 */
class McpModaTest {

    private lateinit var registry: BundleRegistry
    private lateinit var tools: KslMcpTools

    @BeforeTest
    fun setUp() {
        registry = TestBundles.registry()
        val root = Files.createTempDirectory("mcp-moda")
        tools = KslMcpTools(registry, ResultStore(root), ArtifactStore(root))
    }

    @AfterTest
    fun tearDown() {
        tools.close()
        registry.close()
    }

    private fun firstText(r: CallToolResult): String = (r.content.first() as TextContent).text ?: ""

    private fun structured(r: CallToolResult): JsonObject = r.structuredContent!!

    private fun document(name: String = "Siting"): ModaDocument = ModaDocument(
        name = name,
        metrics = listOf(
            MetricSpec("Cost", weight = 2.0, upperLimit = 1000.0),
            MetricSpec("Delay", weight = 1.0, upperLimit = 1000.0)
        ),
        alternatives = listOf("North", "South", "East"),
        source = ModaSourceReference.InlineScores(
            mapOf(
                "North" to mapOf("Cost" to 100.0, "Delay" to 900.0),
                "South" to mapOf("Cost" to 300.0, "Delay" to 500.0),
                "East" to mapOf("Cost" to 500.0, "Delay" to 100.0)
            )
        )
    )

    private fun args(document: ModaDocument): JsonObject = buildJsonObject {
        put("config", ModaDocumentFormats.toJson(document))
    }

    // ------------------------------------------------------------------------------------------
    // Discovery
    // ------------------------------------------------------------------------------------------

    /**
     *  An agent asked to write a study will otherwise invent a plausible-sounding function name, so
     *  what exists has to be discoverable before it writes anything.
     */
    @Test
    fun `an agent can find out which value functions exist before writing a study`() {
        val reply = tools.listValueFunctions()
        val ids = structured(reply)["valueFunctionIds"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("linear" in ids, "the functions on offer were $ids")
        assertTrue(firstText(reply).contains("linear"), "the text does not tell an agent what exists")
    }

    // ------------------------------------------------------------------------------------------
    // Checking before running
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a sound study checks out as runnable`() {
        val reply = tools.validateModaStudy(args(document()))
        val payload = structured(reply)
        assertTrue(payload["runnable"]!!.jsonPrimitive.content.toBoolean(), "payload was $payload")
        assertEquals("Siting", payload["name"]!!.jsonPrimitive.content)
        assertTrue(firstText(reply).contains("can be run"), "the text does not give a verdict")
    }

    @Test
    fun `a study naming a value function nobody supplied is told what it could have named`() {
        val broken = document().copy(
            metrics = listOf(MetricSpec("Cost", valueFunctionId = "sigmoid", upperLimit = 100.0))
        )
        val reply = tools.validateModaStudy(args(broken))
        val payload = structured(reply)
        assertTrue(!payload["runnable"]!!.jsonPrimitive.content.toBoolean())
        val messages = payload["issues"]!!.jsonArray.map { it.jsonObject["message"]!!.jsonPrimitive.content }
        assertTrue(messages.any { it.contains("sigmoid") }, "issues were $messages")
        assertTrue(messages.any { it.contains("linear") }, "the reply does not say what exists: $messages")
        assertTrue(firstText(reply).contains("cannot be run"), "the text does not give a verdict")
    }

    @Test
    fun `every problem names the part of the document it concerns`() {
        val broken = document().copy(alternatives = listOf("North"), name = "")
        val payload = structured(tools.validateModaStudy(args(broken)))
        val elements = payload["issues"]!!.jsonArray.map { it.jsonObject["element"]!!.jsonPrimitive.content }
        assertTrue("alternatives" in elements, "elements were $elements")
        assertTrue("name" in elements, "elements were $elements")
    }

    @Test
    fun `something that is not a study is refused as an error rather than answered`() {
        val reply = tools.validateModaStudy(buildJsonObject { put("config", "{ not json") })
        assertEquals(true, reply.isError, "a malformed document should be an error")
        assertTrue(firstText(reply).contains("MODA"), "the error does not say what was wrong")
    }

    // ------------------------------------------------------------------------------------------
    // Running
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a study runs and reports what it recommends and why`() = runBlocking {
        val reply = tools.runModaStudy(args(document()))
        val payload = structured(reply)
        assertEquals("COMPLETED", payload["outcome"]!!.jsonPrimitive.content)

        val snapshot = payload["snapshot"]!!.jsonObject
        val alternatives = snapshot["alternatives"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("North", "South", "East"), alternatives)
        val recommendation = snapshot["primaryRecommendation"]!!.jsonPrimitive.content
        assertTrue(recommendation in alternatives)

        val text = firstText(reply)
        assertTrue(text.contains(recommendation), "the text does not say what is recommended: $text")
        assertTrue(text.contains("Ranked best first"), "the text gives no order behind the recommendation")
        assertTrue(text.contains("Cost") && text.contains("Delay"), "the text does not account for the metrics")
    }

    /**
     *  A metric every alternative ties on carries nothing that could separate them, and an agent
     *  reporting the study should be able to say so rather than presenting it as if it counted.
     */
    @Test
    fun `a metric that separates nothing is pointed out`() = runBlocking {
        val tied = document().copy(
            source = ModaSourceReference.InlineScores(
                mapOf(
                    "North" to mapOf("Cost" to 100.0, "Delay" to 500.0),
                    "South" to mapOf("Cost" to 300.0, "Delay" to 500.0),
                    "East" to mapOf("Cost" to 500.0, "Delay" to 500.0)
                )
            )
        )
        val reply = tools.runModaStudy(args(tied))
        val text = firstText(reply)
        assertTrue(
            text.contains("separates nothing"),
            "the text does not point out the metric that carries no information: $text"
        )
        val metrics = structured(reply)["snapshot"]!!.jsonObject["metrics"]!!.jsonArray
        val delay = metrics.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == "Delay" }
        assertEquals(true, delay["hadTiedScores"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `a study that cannot be run comes back refused with the reasons`() = runBlocking {
        val broken = document().copy(alternatives = listOf("North"))
        val reply = tools.runModaStudy(args(broken))
        val payload = structured(reply)
        assertEquals("REFUSED", payload["outcome"]!!.jsonPrimitive.content)
        assertTrue(payload["issues"]!!.jsonArray.isNotEmpty())
        assertTrue(firstText(reply).contains("was not run"), "the text does not say it did not run")
    }

    @Test
    fun `the same study run twice gives the same answer`() = runBlocking {
        val first = structured(tools.runModaStudy(args(document())))["snapshot"].toString()
        val second = structured(tools.runModaStudy(args(document())))["snapshot"].toString()
        assertEquals(first, second, "the same study came back differently")
    }
}
