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

package ksl.app.swing.scenario

import ksl.app.config.ReportFormat
import ksl.app.session.RunResult
import ksl.utilities.io.dbutil.SimulationSnapshot
import ksl.utilities.io.report.dsl.report
import ksl.utilities.io.report.writeHtml
import ksl.utilities.io.report.writeMarkdown
import ksl.utilities.io.report.writeText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 *  Minimal cross-scenario summary report.  Generates one row per
 *  completed scenario with bookkeeping fields (name, requested vs
 *  completed replications, completion status, start / end timestamps,
 *  duration).  Renders the same document in each [ReportFormat] the
 *  user has selected and writes the files under
 *  `<outputDir>/cross-scenario.<ext>`.
 */
object CrossScenarioReport {

    data class WriteOutcome(
        val written: List<Path>,
        val errors: List<String>
    )

    /**
     *  Render the report and write it in every format in [formats].
     *  Returns the list of files written (in caller-friendly order)
     *  and any per-format error messages.  When [formats] is empty,
     *  returns an immediate success with no files.
     */
    fun render(
        result: RunResult.BatchCompleted,
        outputDir: Path,
        formats: Set<ReportFormat>
    ): WriteOutcome {
        if (formats.isEmpty()) return WriteOutcome(emptyList(), listOf("No report formats selected."))
        Files.createDirectories(outputDir)

        val doc = report("Cross-Scenario Summary — ${result.summary.orchestratorName}") {
            paragraph(
                "Run id: ${result.summary.runId}.  " +
                    "${result.summary.completedItems} of ${result.summary.totalItems} scenarios completed " +
                    if (result.summary.failedItems > 0) "(${result.summary.failedItems} failed)." else "."
            )
            paragraph(
                "Run started ${formatInstant(result.summary.beginTime)}, " +
                    "ended ${formatInstant(result.summary.endTime)}."
            )

            section("Per-Scenario Summary") {
                dataTable(
                    headers = listOf(
                        "Scenario",
                        "Requested reps",
                        "Completed reps",
                        "Status",
                        "Start",
                        "End",
                        "Duration"
                    ),
                    rows = result.snapshots.map { snap -> rowFor(snap) }
                )
            }
        }

        val written = mutableListOf<Path>()
        val errors = mutableListOf<String>()
        val baseName = "cross-scenario"
        for (fmt in formats) {
            try {
                val ext = extensionFor(fmt)
                val path = outputDir.resolve("$baseName.$ext")
                when (fmt) {
                    ReportFormat.HTML -> doc.writeHtml(path = path)
                    ReportFormat.MARKDOWN -> doc.writeMarkdown(path = path)
                    ReportFormat.TEXT -> doc.writeText(path = path)
                }
                written.add(path)
            } catch (t: Throwable) {
                errors.add("${fmt.name}: ${t.message ?: t::class.simpleName ?: "unknown error"}")
            }
        }
        return WriteOutcome(written, errors)
    }

    private fun rowFor(snap: SimulationSnapshot.ExperimentCompleted): List<String> {
        val run = snap.simulationRun
        val started = run.run_start_time_stamp?.let { Instant.ofEpochMilli(it) }
        val ended = run.run_end_time_stamp?.let { Instant.ofEpochMilli(it) }
        val duration: String = if (started != null && ended != null) {
            val ms = ended.toEpochMilli() - started.toEpochMilli()
            "${ms / 1000.0} s"
        } else ""
        val completedReps = run.last_rep_id?.toString() ?: ""
        return listOf(
            run.run_name,
            run.num_reps.toString(),
            completedReps,
            if (run.run_error_msg.isNullOrBlank()) "Completed" else "Failed",
            started?.let { formatInstant(kotlinx.datetime.Instant.fromEpochMilliseconds(it.toEpochMilli())) } ?: "",
            ended?.let { formatInstant(kotlinx.datetime.Instant.fromEpochMilliseconds(it.toEpochMilli())) } ?: "",
            duration
        )
    }

    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    private fun formatInstant(instant: kotlinx.datetime.Instant): String =
        formatter.format(Instant.ofEpochMilli(instant.toEpochMilliseconds()))

    private fun extensionFor(fmt: ReportFormat): String = when (fmt) {
        ReportFormat.HTML -> "html"
        ReportFormat.MARKDOWN -> "md"
        ReportFormat.TEXT -> "txt"
    }
}
