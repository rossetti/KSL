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

import java.nio.file.Files
import java.nio.file.Path

/**
 * Stateless, by-result database analysis: given a run's server-owned output
 * directory, locate the KSL database it produced (when the run opted in via
 * `enableKSLDatabase`) and answer queries, opening read-only and closing per
 * call (no handle lifecycle, no leaked connections).
 *
 * A thin orchestrator over [DatabaseAnalysisService] — it adds only database
 * discovery and graceful absence; the experiment/response metadata and the
 * JSON projections come from that service unchanged. A run that produced no
 * database degrades to [DbQueryResult.NoDatabase] / `present = false`, never an
 * error.
 */
class ResultDatabaseService(
    private val analysis: DatabaseAnalysisService = DatabaseAnalysisService(),
) {

    /** The KSL database file under [outputDir] (recursive), or null if none. The
     *  `*.db` filter ignores trace/Welch siblings, which are not `.db` files. */
    fun locate(outputDir: Path): Path? {
        if (!Files.isDirectory(outputDir)) return null
        return Files.walk(outputDir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".db") }
                .sorted()
                .findFirst()
                .orElse(null)
        }
    }

    /** Whether [outputDir] has an analyzable database, with a guidance message
     *  when it does not. Always succeeds. */
    fun status(outputDir: Path): DbStatusDto {
        val db = locate(outputDir) ?: return DbStatusDto(false, 0, NO_DATABASE_MESSAGE)
        return withDatabase(db) { handle ->
            val count = analysis.listExperiments(handle).size
            DbStatusDto(true, count, "Database available ($count experiment(s)).")
        }
    }

    /** The experiments in the result's database, or null when there is none. */
    fun experiments(outputDir: Path): List<ExperimentInfoDto>? {
        val db = locate(outputDir) ?: return null
        return withDatabase(db) { analysis.listExperiments(it) }
    }

    /** Across-replication summary statistics for [experimentName] as JSON. */
    fun summary(outputDir: Path, experimentName: String): DbQueryResult {
        val db = locate(outputDir) ?: return DbQueryResult.NoDatabase
        return withDatabase(db) { DbQueryResult.Json(analysis.acrossReplicationSummaryJson(it, experimentName)) }
    }

    /** Multiple-comparison analysis of [responseName] as JSON, or a graceful
     *  [DbQueryResult.NoDatabase] / [DbQueryResult.Invalid] when not analyzable. */
    fun compare(
        outputDir: Path,
        responseName: String,
        experimentNames: List<String>? = null,
        delta: Double = 0.0,
        level: Double = 0.95,
    ): DbQueryResult {
        val db = locate(outputDir) ?: return DbQueryResult.NoDatabase
        return withDatabase(db) { analysis.comparisonJson(it, responseName, experimentNames, delta, level) }
    }

    /** Renders a comparison (MCB) report into [reportsDir], or a graceful result
     *  when there is no database / the request is not analyzable. */
    fun renderComparisonReport(
        outputDir: Path,
        reportsDir: Path,
        responseName: String,
        experimentNames: List<String>? = null,
        delta: Double = 0.0,
        level: Double = 0.95,
        formats: Set<ksl.app.config.ReportFormat> = setOf(ksl.app.config.ReportFormat.HTML),
    ): DbReportResult {
        val db = locate(outputDir) ?: return DbReportResult.NoDatabase
        return withDatabase(db) {
            analysis.renderComparisonReport(it, responseName, experimentNames, delta, level, formats, reportsDir)
        }
    }

    /** Exports the result's database into [reportsDir], or [DbReportResult.NoDatabase]. */
    fun exportDatabase(outputDir: Path, reportsDir: Path, format: DbExportFormat): DbReportResult {
        val db = locate(outputDir) ?: return DbReportResult.NoDatabase
        return withDatabase(db) { DbReportResult.Ok(analysis.exportDatabase(it, format, reportsDir)) }
    }

    private fun <T> withDatabase(db: Path, block: (DbHandle) -> T): T {
        val handle = analysis.open(db)
        try {
            return block(handle)
        } finally {
            analysis.close(handle)
        }
    }
}
