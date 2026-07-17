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

package ksl.service.capability.dbanalysis

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ksl.examples.book.appendixD.GIGcQueue
import ksl.observers.welch.WelchFileObserver
import ksl.simulation.Model
import ksl.utilities.io.dbutil.KSLDatabase
import ksl.utilities.io.dbutil.KSLDatabaseObserver
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [ResultDatabaseService] — by-result database analysis projected to
 * JSON via the existing DataFrame writers. Headless-safe (no plot rendering).
 */
class ResultDatabaseServiceTest {

    private val service = ResultDatabaseService()

    /** Runs [experimentNames] of a GI/G/c queue into one SQLite KSL database
     *  under [outDir], yielding a multi-experiment database to analyze. */
    private fun buildDatabase(outDir: Path, experimentNames: List<String>, numServers: Int = 1) {
        Files.createDirectories(outDir)
        KSLDatabase("results.db", outDir).use { db ->
            for (expName in experimentNames) {
                val m = Model("DbAnalysisModel", autoCSVReports = false)
                m.numberOfReplications = 4
                m.lengthOfReplication = 2000.0
                m.experimentName = expName
                GIGcQueue(m, numServers = numServers, name = "Q")
                KSLDatabaseObserver(m, db)
                m.simulate()
            }
        }
    }

    @Test
    @DisplayName("status, locate, and experiments reflect the produced database")
    fun statusAndExperiments(@TempDir tempDir: Path) {
        val outDir = tempDir.resolve("output")
        buildDatabase(outDir, listOf("baseline", "alt"))

        assertTrue(service.locate(outDir)?.fileName.toString()?.endsWith(".db") == true)
        val status = service.status(outDir)
        assertTrue(status.present, "database should be present")
        assertEquals(2, status.experimentCount)
        assertEquals(setOf("baseline", "alt"), service.experiments(outDir)!!.map { it.name }.toSet())
    }

    @Test
    @DisplayName("summary returns across-replication statistics as JSON")
    fun summaryJson(@TempDir tempDir: Path) {
        val outDir = tempDir.resolve("output")
        buildDatabase(outDir, listOf("baseline"))

        val result = service.summary(outDir, "baseline")
        assertTrue(result is DbQueryResult.Json, "expected JSON; got $result")
        val rows = Json.parseToJsonElement((result as DbQueryResult.Json).payload).jsonArray
        assertTrue(rows.isNotEmpty(), "summary should have response rows")
        val names = rows.map { it.jsonObject["stat_name"]!!.toString() }
        assertTrue(names.any { it.contains("System Time") }, "summary should include System Time; got $names")
    }

    @Test
    @DisplayName("compare returns MCB results/intervals/screening as JSON")
    fun compareJson(@TempDir tempDir: Path) {
        val outDir = tempDir.resolve("output")
        buildDatabase(outDir, listOf("baseline", "alt"))

        val result = service.compare(outDir, "System Time")
        assertTrue(result is DbQueryResult.Json, "expected JSON; got $result")
        val obj = Json.parseToJsonElement((result as DbQueryResult.Json).payload).jsonObject
        assertTrue(obj["intervals"]!!.jsonArray.isNotEmpty(), "MCB intervals should be present")
        assertTrue(obj["results"]!!.jsonArray.isNotEmpty(), "MCB results should be present")
    }

    @Test
    @DisplayName("compare on a single-experiment database is gracefully invalid")
    fun compareNeedsTwoExperiments(@TempDir tempDir: Path) {
        val outDir = tempDir.resolve("output")
        buildDatabase(outDir, listOf("only-one"))

        val result = service.compare(outDir, "System Time")
        assertTrue(result is DbQueryResult.Invalid, "single experiment must be Invalid; got $result")
        assertTrue((result as DbQueryResult.Invalid).reason.contains("at least 2"),
            "reason should explain the precondition; got: ${result.reason}")
    }

    @Test
    @DisplayName("statistical views project to JSON envelopes; time-series is the across-rep summary")
    fun viewsProjectToJson(@TempDir tempDir: Path) {
        val outDir = tempDir.resolve("output")
        buildDatabase(outDir, listOf("baseline", "alt"))

        assertTrue(service.viewNames(outDir)!!.containsAll(listOf("across-replication", "time-series", "histograms")))

        val across = service.viewJson(outDir, "across-replication")
        assertTrue(across is DbQueryResult.Json, "expected JSON; got $across")
        val env = Json.parseToJsonElement((across as DbQueryResult.Json).payload).jsonObject
        assertEquals("across-replication", env["view"]!!.jsonPrimitive.content)
        assertTrue(env["rows"]!!.jsonArray.isNotEmpty(), "across-replication should have rows")
        assertTrue(env["truncated"]!!.jsonPrimitive.content == "false")

        // time-series is the across-replication per-period summary (period + average columns).
        val ts = service.viewJson(outDir, "time-series")
        assertTrue(ts is DbQueryResult.Json, "time-series should project; got $ts")
    }

    @Test
    @DisplayName("view supports an experiment filter, a row cap, and rejects unknown names")
    fun viewFilteringAndCapping(@TempDir tempDir: Path) {
        val outDir = tempDir.resolve("output")
        buildDatabase(outDir, listOf("baseline", "alt"))

        val all = (service.viewJson(outDir, "across-replication") as DbQueryResult.Json)
            .let { Json.parseToJsonElement(it.payload).jsonObject["rows"]!!.jsonArray.size }
        val filtered = (service.viewJson(outDir, "across-replication", experiment = "baseline") as DbQueryResult.Json)
            .let { Json.parseToJsonElement(it.payload).jsonObject }
        assertTrue(filtered["rows"]!!.jsonArray.size < all, "experiment filter should narrow the rows")

        val capped = (service.viewJson(outDir, "across-replication", limit = 1) as DbQueryResult.Json)
            .let { Json.parseToJsonElement(it.payload).jsonObject }
        assertEquals("true", capped["truncated"]!!.jsonPrimitive.content)
        assertEquals(1, capped["rows"]!!.jsonArray.size)

        assertTrue(service.viewJson(outDir, "no-such-view") is DbQueryResult.Invalid)
    }

    @Test
    @DisplayName("export writes CSV files and an Excel workbook (Phase C+)")
    fun exportsCsvAndExcel(@TempDir tempDir: Path) {
        val outDir = tempDir.resolve("output")
        buildDatabase(outDir, listOf("baseline", "alt"))
        val reportsDir = tempDir.resolve("artifacts")

        val csv = service.exportDatabase(outDir, reportsDir, DbExportFormat.CSV)
        assertTrue(csv is DbReportResult.Ok && (csv as DbReportResult.Ok).files.any { it.endsWith(".csv") },
            "CSV export should write .csv files; got $csv")

        val excel = service.exportDatabase(outDir, reportsDir, DbExportFormat.EXCEL)
        assertTrue(excel is DbReportResult.Ok && (excel as DbReportResult.Ok).files.contains("database.xlsx"),
            "Excel export should write database.xlsx; got $excel")
        assertTrue(Files.exists(reportsDir.resolve("database.xlsx")), "the workbook must be on disk")
    }

    @Test
    @DisplayName("comparison report renders an HTML artifact with plots (Phase C+)")
    fun comparisonReportRenders(@TempDir tempDir: Path) {
        assumeFalse(GraphicsEnvironment.isHeadless(), "embedded plot rendering needs a display on this classpath")
        val outDir = tempDir.resolve("output")
        buildDatabase(outDir, listOf("baseline", "alt"))
        val reportsDir = tempDir.resolve("artifacts")

        val result = service.renderComparisonReport(outDir, reportsDir, "System Time")
        assertTrue(result is DbReportResult.Ok, "expected a rendered report; got $result")
        val files = (result as DbReportResult.Ok).files
        assertTrue(files.any { it.endsWith(".html") }, "comparison report should write HTML; got $files")
    }

    @Test
    @DisplayName("experiment summary report renders an HTML artifact (Phase C+)")
    fun summaryReportRenders(@TempDir tempDir: Path) {
        assumeFalse(GraphicsEnvironment.isHeadless(), "embedded plot rendering needs a display on this classpath")
        val outDir = tempDir.resolve("output")
        buildDatabase(outDir, listOf("baseline"))

        val result = service.renderExperimentSummaryReport(outDir, tempDir.resolve("artifacts"), "baseline")
        assertTrue(result is DbReportResult.Ok, "expected a rendered report; got $result")
        assertTrue((result as DbReportResult.Ok).files.any { it.endsWith(".html") }, "summary report HTML expected; got ${result.files}")
    }

    @Test
    @DisplayName("comparison report on one experiment is gracefully invalid (Phase C+)")
    fun comparisonReportNeedsTwoExperiments(@TempDir tempDir: Path) {
        val outDir = tempDir.resolve("output")
        buildDatabase(outDir, listOf("solo"))
        val result = service.renderComparisonReport(outDir, tempDir.resolve("artifacts"), "System Time")
        assertTrue(result is DbReportResult.Invalid, "single experiment must be Invalid; got $result")
    }

    @Test
    @DisplayName("a run with no database is reported gracefully, never as an error")
    fun noDatabaseIsGraceful(@TempDir tempDir: Path) {
        val outDir = tempDir.resolve("output").also { Files.createDirectories(it) }
        // Capture some non-database output to prove *.db discovery is specific.
        val m = Model("NoDbModel", autoCSVReports = false)
        m.outputDirectory = ksl.utilities.io.OutputDirectory(outDir, "kslOutput.txt")
        m.numberOfReplications = 2
        m.lengthOfReplication = 200.0
        GIGcQueue(m, numServers = 1, name = "Q")
        val welch = WelchFileObserver(m.response("System Time")!!, 1.0)
        m.simulate()
        // Close the observer so its .wdf/.json handles are released and @TempDir can be deleted
        // (on Windows an open handle blocks temp-dir cleanup; invisible on Unix).
        welch.close()

        assertTrue(service.locate(outDir) == null, "no *.db should be found")
        val status = service.status(outDir)
        assertTrue(!status.present && status.message.contains("enableKSLDatabase"),
            "absence must carry guidance; got: $status")
        assertEquals(DbQueryResult.NoDatabase, service.compare(outDir, "System Time"))
        assertTrue(service.experiments(outDir) == null)
    }
}
