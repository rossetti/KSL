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

package ksl.server.rest

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import ksl.app.config.ExperimentRunOverrides
import ksl.app.config.ModelReference
import ksl.app.config.OutputConfig
import ksl.app.config.RunConfiguration
import ksl.app.config.RunConfigurationJson
import ksl.app.config.ScenarioSpec
import ksl.app.config.ReportFormat
import ksl.service.capability.dbanalysis.DbExportFormat
import ksl.service.capability.dbanalysis.DbQueryResult
import ksl.service.capability.dbanalysis.DbReportResult
import ksl.service.store.ArtifactStore
import ksl.service.store.ResultStore
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * End-to-end Phase C wiring over the run service: a scenario run that opts into a
 * KSL database has it retained under the result and analyzable by resultId; a run
 * that does not is reported gracefully (never an error). JSON path — headless-safe.
 */
class RestDatabaseAnalysisTest {

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

    private fun freshService(): Pair<KslRestService, () -> Unit> {
        val root = Files.createTempDirectory("rest-dbanalysis")
        val registry = TestBundles.registry()
        val service = KslRestService(registry, resultStore = ResultStore(root), artifactStore = ArtifactStore(root))
        return service to { service.close(); registry.close() }
    }

    private suspend fun runToCompletion(service: KslRestService, doc: String): String {
        val submission = service.submitRunDocument(doc)
        withTimeout(90_000) { while (service.runResult(submission.jobId) == null) delay(100) }
        return submission.resultId
    }

    @Test
    @DisplayName("a scenario run with the database option is analyzable by resultId")
    fun databaseRunIsAnalyzable() = runBlocking {
        val (service, close) = freshService()
        try {
            val resultId = runToCompletion(service, scenarioDoc(enableDb = true))

            val status = service.dbStatus(resultId)
            assertTrue(status.present, "database should be present; got $status")
            assertTrue(status.experimentCount >= 2, "scenario run should record 2 experiments; got ${status.experimentCount}")

            val compare = service.dbCompare(resultId, "System Time", null, 0.0, 0.95)
            assertTrue(compare is DbQueryResult.Json, "compare should yield JSON; got $compare")
            val obj = Json.parseToJsonElement((compare as DbQueryResult.Json).payload).jsonObject
            assertTrue(obj["intervals"]!!.jsonArray.isNotEmpty(), "MCB intervals expected")
        } finally {
            close()
        }
    }

    @Test
    @DisplayName("comparison report and database export are produced as artifacts (headless)")
    fun reportsAndExports() = runBlocking {
        val (service, close) = freshService()
        try {
            val resultId = runToCompletion(service, scenarioDoc(enableDb = true))

            // CSV export — headless-safe (no plots).
            assertTrue(service.dbExport(resultId, DbExportFormat.CSV) is DbReportResult.Ok)
            // Comparison report — embedded plots render headless (Swing frontend excluded here).
            val report = service.dbCompareReport(resultId, "System Time", null, 0.0, 0.95, setOf(ReportFormat.HTML))
            assertTrue(report is DbReportResult.Ok, "comparison report should render; got $report")
            // Single-experiment summary report — also renders headless.
            val summary = service.dbSummaryReport(resultId, "baseline", 0.95, true, setOf(ReportFormat.HTML))
            assertTrue(summary is DbReportResult.Ok, "summary report should render; got $summary")

            val names = service.artifacts(resultId).map { it.name }
            assertTrue(names.any { it.endsWith(".csv") }, "expected CSV export; got $names")
            assertTrue(names.any { it.endsWith(".html") }, "expected report .html files; got $names")
            assertTrue(names.any { it.contains("summary") && it.endsWith(".html") }, "expected a summary report; got $names")
        } finally {
            close()
        }
    }

    @Test
    @DisplayName("a run without the database option is reported gracefully")
    fun noDatabaseRunIsGraceful() = runBlocking {
        val (service, close) = freshService()
        try {
            val resultId = runToCompletion(service, scenarioDoc(enableDb = false))

            val status = service.dbStatus(resultId)
            assertTrue(!status.present, "no database expected; got $status")
            assertTrue(status.message.contains("enableKSLDatabase"), "guidance expected; got: ${status.message}")
            assertTrue(service.dbCompare(resultId, "System Time", null, 0.0, 0.95) == DbQueryResult.NoDatabase)
        } finally {
            close()
        }
    }
}
