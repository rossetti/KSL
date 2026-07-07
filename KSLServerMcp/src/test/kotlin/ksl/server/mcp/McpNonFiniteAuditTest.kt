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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import ksl.examples.general.appsupport.MM1ModelBuilder
import ksl.examples.general.appsupport.ManifestBundleFixtures
import ksl.service.capability.run.BundleRegistry
import ksl.service.store.ArtifactStore
import ksl.service.store.ResultStore
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * AUDIT: which MCP tools can emit a non-finite double (±Infinity / NaN) in their structuredContent?
 * A non-finite value serializes to a bare `Infinity`/`NaN` token — NOT valid JSON (RFC 8259) — which a
 * spec-compliant client rejects, breaking the response and the stdio channel. This drives each suspect tool
 * with edge-case inputs chosen to force non-finite outputs, then recursively scans the result for them.
 * The assertion message is the blast-radius report.
 */
class McpNonFiniteAuditTest {

    private lateinit var registry: BundleRegistry
    private lateinit var tools: KslMcpTools

    @BeforeTest
    fun setUp() {
        registry = TestBundles.registry()
        val root = Files.createTempDirectory("mcp-nonfinite-audit")
        tools = KslMcpTools(registry, ResultStore(root), ArtifactStore(root))
    }

    @AfterTest
    fun tearDown() { tools.close(); registry.close() }

    /** Every JSON path in [el] whose value is a non-finite number token (Infinity/-Infinity/NaN). */
    private fun nonFinite(el: JsonElement?, path: String = "$", out: MutableList<String> = mutableListOf()): List<String> {
        when (el) {
            is JsonObject -> el.forEach { (k, v) -> nonFinite(v, "$path.$k", out) }
            is JsonArray -> el.forEachIndexed { i, v -> nonFinite(v, "$path[$i]", out) }
            is JsonPrimitive -> if (!el.isString) {
                val d = el.contentOrNull?.toDoubleOrNull()
                if (d != null && !d.isFinite()) out.add("$path = ${el.content}")
            }
            else -> {}
        }
        return out
    }

    private fun scan(tool: String, r: CallToolResult, into: MutableMap<String, List<String>>) {
        val bad = nonFinite(r.structuredContent)
        if (bad.isNotEmpty()) into[tool] = bad
    }

    private fun constant(n: Int, v: Double = 5.0) = buildJsonArray { repeat(n) { add(v) } }

    @Test
    fun auditToolsForNonFiniteNumbersInOutput() = runBlocking {
        val offenders = linkedMapOf<String, List<String>>()

        // 1. bundle_authoring_candidates — MM1 has a resource-capacity control → upperBound = +∞.
        val jar = ManifestBundleFixtures.buildersJar(Files.createTempDirectory("audit-jar"), "mm1", MM1ModelBuilder::class.java)
        scan("bundle_authoring_candidates",
            tools.bundleAuthoringCandidates(buildJsonObject { put("buildersJarPath", jar.toString()) }), offenders)

        // 2. summarize_data on constant data → zero variance → NaN skewness/kurtosis/lag-1.
        scan("summarize_data", tools.summarizeData(buildJsonObject { put("data", constant(12)) }), offenders)

        // 3. acf_analysis on constant data → 0/0 autocorrelations.
        scan("acf_analysis", tools.acfAnalysis(buildJsonObject { put("data", constant(30)) }), offenders)

        // 4. shift_analysis on constant data.
        scan("shift_analysis", tools.shiftAnalysis(buildJsonObject { put("data", constant(30)) }), offenders)

        // 5. run_model, single replication → undefined dispersion (F-2 regression; expect CLEAN).
        scan("run_model(n=1)", tools.runModel(buildJsonObject {
            put("bundleId", "ksl.examples.mm1"); put("modelId", "MM1")
            put("numberOfReplications", 1); put("lengthOfReplication", 500.0)
        }), offenders)

        // 6. generate_variates — sanity (expect CLEAN).
        scan("generate_variates", tools.generateVariates(buildJsonObject {
            put("familyId", "exponential"); put("n", 50)
        }), offenders)

        // 7. fit_dataset — shares DataSummaryDTO + HistogramDTO (open first/last bins → ±∞) + score DTOs.
        scan("fit_dataset", tools.fitDataset(buildJsonObject {
            put("data", buildJsonArray {
                listOf(0.5, 1.2, 2.3, 0.8, 3.1, 1.5, 2.9, 0.6, 4.2, 1.1, 2.0, 3.3, 0.9, 1.8, 2.5, 0.7, 3.6, 1.3).forEach { add(it) }
            })
        }), offenders)

        val report = if (offenders.isEmpty()) "none" else offenders.entries.joinToString("\n") { (tool, paths) ->
            "  $tool:\n" + paths.joinToString("\n") { "      $it" }
        }
        println("=== NON-FINITE AUDIT (tools emitting Infinity/NaN in structuredContent) ===\n$report")
        assertTrue(offenders.isEmpty(),
            "these tools emit non-finite numbers (invalid JSON on the MCP wire):\n$report")
    }
}
