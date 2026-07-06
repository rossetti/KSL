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

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ksl.app.config.ExperimentRunOverrides
import ksl.app.config.ModelReference
import ksl.app.config.OutputConfig
import ksl.app.config.RunConfiguration
import ksl.app.config.RunConfigurationJson
import ksl.app.config.ScenarioSpec
import ksl.service.capability.run.BundleRegistry
import ksl.service.store.ArtifactStore
import ksl.service.store.ResultStore
import org.junit.jupiter.api.DisplayName
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end Phase C wiring over the MCP tools: a scenario run that opts into a
 * KSL database is analyzable by resultId through db_status / db_compare; a run
 * without one returns graceful guidance. JSON path — headless-safe.
 */
class McpDatabaseAnalysisTest {

    private lateinit var registry: BundleRegistry
    private lateinit var tools: KslMcpTools

    @BeforeTest
    fun setUp() {
        registry = TestBundles.registry()
        val root = Files.createTempDirectory("mcp-dbanalysis")
        tools = KslMcpTools(registry, ResultStore(root), ArtifactStore(root))
    }

    @AfterTest
    fun tearDown() {
        tools.close()
        registry.close()
    }

    private fun scenarioDoc(enableDb: Boolean): String = RunConfigurationJson.encode(
        RunConfiguration(
            scenarios = listOf("baseline", "alt").map {
                ScenarioSpec(
                    name = it,
                    modelReference = ModelReference.ByProviderId("MM1"),
                    runOverrides = ExperimentRunOverrides(numberOfReplications = 4, lengthOfReplication = 2000.0),
                )
            },
            outputConfig = OutputConfig(enableKSLDatabase = enableDb),
        ),
    )

    private suspend fun runResultId(enableDb: Boolean): String =
        tools.runConfig(buildJsonObject { put("config", scenarioDoc(enableDb)) })
            .structuredContent!!.jsonObject["resultId"]!!.jsonPrimitive.content

    @Test
    @DisplayName("db_status and db_compare analyze a scenario run that enabled the database")
    fun analyzesDatabaseRun() = runBlocking {
        val resultId = runResultId(enableDb = true)

        val status = tools.dbStatus(buildJsonObject { put("resultId", resultId) }).structuredContent!!.jsonObject
        assertTrue(status["present"]!!.jsonPrimitive.content.toBoolean(), "database should be present; got $status")

        val compare = tools.dbCompare(buildJsonObject {
            put("resultId", resultId); put("responseName", "System Time")
        }).structuredContent!!.jsonObject
        val intervals = compare["comparison"]!!.jsonObject["intervals"]!!.jsonArray
        assertTrue(intervals.isNotEmpty(), "MCB intervals expected; got $compare")
    }

    @Test
    @DisplayName("db_views lists views and db_view projects one to a JSON envelope")
    fun viewTools() = runBlocking {
        val resultId = runResultId(enableDb = true)

        val views = tools.dbViews(buildJsonObject { put("resultId", resultId) })
            .structuredContent!!.jsonObject["views"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(views.contains("across-replication"), "expected the across-replication view; got $views")

        val env = tools.dbView(buildJsonObject { put("resultId", resultId); put("view", "across-replication") })
            .structuredContent!!.jsonObject["view"]!!.jsonObject
        assertTrue(env["rows"]!!.jsonArray.isNotEmpty(), "across-replication view should have rows; got $env")
        assertTrue(env["truncated"]!!.jsonPrimitive.content == "false")
    }

    @Test
    @DisplayName("db_compare_report and db_export produce downloadable artifacts (headless)")
    fun reportAndExportTools() = runBlocking {
        val resultId = runResultId(enableDb = true)

        val exported = tools.dbExport(buildJsonObject { put("resultId", resultId); put("format", "CSV") })
            .structuredContent!!.jsonObject["artifacts"]!!.jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(exported.any { it.endsWith(".csv") }, "CSV export expected; got $exported")

        val reported = tools.dbCompareReport(buildJsonObject {
            put("resultId", resultId); put("responseName", "System Time")
        }).structuredContent!!.jsonObject["artifacts"]!!.jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(reported.any { it.endsWith(".html") }, "comparison report html expected; got $reported")

        val summarized = tools.dbSummaryReport(buildJsonObject {
            put("resultId", resultId); put("experimentName", "baseline")
        }).structuredContent!!.jsonObject["artifacts"]!!.jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(summarized.any { it.contains("summary") && it.endsWith(".html") }, "summary report expected; got $summarized")
    }

    @Test
    @DisplayName("db_status reports absence gracefully for a run without the database option")
    fun reportsAbsenceGracefully() = runBlocking {
        val resultId = runResultId(enableDb = false)

        val status = tools.dbStatus(buildJsonObject { put("resultId", resultId) }).structuredContent!!.jsonObject
        assertTrue(!status["present"]!!.jsonPrimitive.content.toBoolean(), "no database expected; got $status")
        assertTrue(status["message"]!!.jsonPrimitive.content.contains("enableKSLDatabase"), "guidance expected")
    }

    // ---- C2: in-memory MCB / summary on a headless (database-less) batch ----

    @Test
    @DisplayName("db_compare answers MCB on a headless batch with no database (in-memory fallback)")
    fun inMemoryMcbWithoutDatabase() = runBlocking {
        val resultId = runResultId(enableDb = false)
        val compare = tools.dbCompare(buildJsonObject {
            put("resultId", resultId); put("responseName", "System Time")
        }).structuredContent!!.jsonObject
        // Not the NoDatabase envelope — a real MCB with results + intervals.
        assertTrue("present" !in compare, "should not degrade to NoDatabase; got $compare")
        val comparison = compare["comparison"]!!.jsonObject
        assertTrue(comparison["results"]!!.jsonArray.isNotEmpty(), "MCB results expected; got $compare")
        assertTrue(comparison["intervals"]!!.jsonArray.isNotEmpty(), "MCB intervals expected; got $compare")
    }

    @Test
    @DisplayName("in-memory MCB matches the database-backed MCB on the same deterministic run")
    fun inMemoryMcbMatchesDatabase() = runBlocking {
        fun resultRows(resultId: String): Set<String> =
            tools.dbCompare(buildJsonObject { put("resultId", resultId); put("responseName", "System Time") })
                .structuredContent!!.jsonObject["comparison"]!!.jsonObject["results"]!!.jsonArray
                // Drop the incidental DataFrame record "id" (a per-analysis row counter, not MCB content).
                .map { JsonObject(it.jsonObject.filterKeys { k -> k != "id" }).toString() }.toSet()
        // The same config, once with a database and once headless: the two paths differ only in
        // where the per-replication data came from, so the MCB must be identical.
        val dbRows = resultRows(runResultId(enableDb = true))
        val memRows = resultRows(runResultId(enableDb = false))
        assertEquals(dbRows, memRows, "in-memory MCB results should equal the DB-backed path")
    }

    @Test
    @DisplayName("db_summary projects the retained across-replication stats when there is no database")
    fun inMemorySummaryWithoutDatabase() = runBlocking {
        val resultId = runResultId(enableDb = false)
        val summary = tools.dbSummary(buildJsonObject {
            put("resultId", resultId); put("experimentName", "baseline")
        }).structuredContent!!.jsonObject
        assertTrue("present" !in summary, "should not degrade to NoDatabase; got $summary")
        assertTrue(summary["summary"]!!.jsonArray.isNotEmpty(), "across-rep stats expected; got $summary")
    }

    @Test
    @DisplayName("in-memory db_compare rejects an unknown response with a clear verdict")
    fun inMemoryCompareRejectsUnknownResponse() = runBlocking {
        val resultId = runResultId(enableDb = false)
        val compare = tools.dbCompare(buildJsonObject {
            put("resultId", resultId); put("responseName", "NoSuchResponse")
        }).structuredContent!!.jsonObject
        assertTrue("analyzable" in compare && compare["analyzable"]!!.jsonPrimitive.content == "false", "expected analyzable:false; got $compare")
    }
}
