/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ksl.service.store.ArtifactStore
import ksl.service.store.ResultStore
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.BeforeTest
import kotlin.test.assertTrue

/**
 *  One theme, four places: an input the caller supplied is discarded and the result does not say so.
 *
 *  Each of these asserts the *naming*, not merely that something failed. A tool that refuses without
 *  repeating back what it could not use leaves the caller to guess which of their arguments was the
 *  problem, and the guessing is the cost.
 */
class McpSilentDropTest {

    private lateinit var tools: KslMcpTools

    @BeforeTest
    fun setUp() {
        val root = Files.createTempDirectory("mcp-silent-drop")
        tools = KslMcpTools(TestBundles.registry(), ResultStore(root), ArtifactStore(root))
    }

    private fun textOf(r: io.modelcontextprotocol.kotlin.sdk.types.CallToolResult): String =
        r.content.joinToString("\n") {
            (it as? io.modelcontextprotocol.kotlin.sdk.types.TextContent)?.text ?: ""
        }

    private fun structured(r: io.modelcontextprotocol.kotlin.sdk.types.CallToolResult): JsonObject =
        r.structuredContent ?: JsonObject(emptyMap())

    @Test
    @DisplayName("an unknown resultId is distinguished from a result with no database")
    fun unknownResultIdIsNotNoDatabase() {
        val result = tools.dbExperiments(buildJsonObject { put("resultId", "does-not-exist") })
        val s = structured(result)

        // Both cases used to answer a bare present:false, and the guidance that came with it told the
        // caller to re-run with the database enabled -- useless advice when the id is simply wrong.
        assertTrue(
            s["code"]?.jsonPrimitive?.content == "UNKNOWN_RESULT_ID",
            "an id this server never held must not be reported as a missing database: $s"
        )
        assertTrue("does-not-exist" in textOf(result), "the refusal must quote the id it did not find")
        assertTrue(
            s["message"] != null,
            "the guidance belongs in structuredContent too, as db_status already does it: $s"
        )
        assertTrue(
            "list_results" in (s["message"]?.jsonPrimitive?.content ?: ""),
            "and should point at how to find a real id"
        )
    }

    @Test
    @DisplayName("an unsupported report format is refused by name, not dropped")
    fun unsupportedFormatIsRefused() {
        val result = tools.dbCompareReport(buildJsonObject {
            put("resultId", "any")
            put("responseName", "System Time")
            put("formats", buildJsonArray { add("HTML"); add("MARKDOWN"); add("PDF") })
        })
        val text = textOf(result)

        // Before: mapNotNull silently dropped PDF, the tool rendered HTML and MD, and advertised a
        // .pdf URL that 404s. A dead link is the worst way to learn a format is unsupported.
        assertTrue("PDF" in text, "the refusal must name the format it cannot make: $text")
        assertTrue(
            "HTML" in text && "MARKDOWN" in text && "TEXT" in text,
            "and list the ones it can, which is the whole of ReportFormat: $text"
        )
    }
}
