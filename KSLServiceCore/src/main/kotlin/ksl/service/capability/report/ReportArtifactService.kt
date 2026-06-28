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

package ksl.service.capability.report

import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.app.single.results.StandardReportFormat
import ksl.app.single.results.StandardReportOutcome
import ksl.app.single.results.TraceManifest
import ksl.app.single.results.TraceReportMaterializer
import ksl.app.single.results.WelchReportMaterializer
import ksl.observers.ResponseTraceData
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Renders the post-run reporting artifacts (Welch warm-up, response trace) for a
 * finished single run into a result's artifact directory, reusing the shared
 * KSLApp materializers. It reads only the on-disk capture output a run produced
 * (the `<response>_Welch/` directories and `<response>_Trace` files), so it needs
 * no live model — the run is already over.
 *
 * Each requested format is best-effort: a render failure is logged and skipped,
 * never failing the run. The transports then expose whatever was written through
 * the artifact store.
 *
 * Headless-safety note: on the server runtime the lets-plot Swing display
 * frontend is excluded (see the server build files), so the materializers' plot
 * rendering runs headless. In a JVM that still has the frontend (e.g. the
 * ServiceCore test classpath) plot rendering requires a display.
 */
class ReportArtifactService {

    /**
     * Materializes the reports named in [request] from the run's [outputDir] into
     * [reportsDir], returning the files written (in deterministic order). Returns
     * an empty list when nothing was requested or no capture data was found.
     */
    fun materialize(reportsDir: Path, outputDir: Path, request: ReportRequest): List<Path> {
        val written = mutableListOf<Path>()
        request.welch?.let { written += materializeWelch(reportsDir, outputDir, it) }
        request.trace?.let { written += materializeTrace(reportsDir, outputDir, it) }
        return written
    }

    private fun materializeWelch(reportsDir: Path, outputDir: Path, req: WelchReport): List<Path> {
        val analyzers = WelchReportMaterializer.discoverAnalyzers(outputDir)
        if (analyzers.isEmpty()) {
            logger.info { "trace/welch: no Welch data under $outputDir; skipping Welch report" }
            return emptyList()
        }
        val options = WelchReportMaterializer.Options(
            includePartialSums = req.includePartialSums,
            includeBiasTest = req.includeBiasTest,
            includeBatchMeans = req.includeBatchMeans,
            deletionPoint = req.deletionPoint,
        )
        return req.formats.toFormats().mapNotNull { fmt ->
            WelchReportMaterializer.materialize(analyzers, fmt, reportsDir, fileStem = "welch", options = options)
                .fileOrLog("welch", fmt)
        }
    }

    private fun materializeTrace(reportsDir: Path, outputDir: Path, req: TraceReport): List<Path> {
        val twByName = TraceManifest.read(outputDir)
        val traces = TraceReportMaterializer.discoverTraceFiles(outputDir).map { f ->
            val name = f.fileName.toString().removeSuffix("_Trace")
            ResponseTraceData(f, isTimeWeighted = twByName[name] ?: false, name = name)
        }
        if (traces.isEmpty()) {
            logger.info { "trace/welch: no trace files under $outputDir; skipping trace report" }
            return emptyList()
        }
        val options = TraceReportMaterializer.Options(req.repNums, req.startTime, req.endTime)
        return req.formats.toFormats().mapNotNull { fmt ->
            TraceReportMaterializer.materialize(traces, fmt, reportsDir, fileStem = "trace", options = options)
                .fileOrLog("trace", fmt)
        }
    }

    private fun StandardReportOutcome.fileOrLog(kind: String, fmt: StandardReportFormat): Path? = when (this) {
        is StandardReportOutcome.Ok -> file.toPath()
        is StandardReportOutcome.Failed -> {
            logger.warn { "trace/welch: $kind ${fmt.name} report failed: $reason" }
            null
        }
    }

    private fun List<String>.toFormats(): List<StandardReportFormat> =
        mapNotNull { s ->
            StandardReportFormat.entries.firstOrNull {
                it.name.equals(s, ignoreCase = true) || it.labelForButton.equals(s, ignoreCase = true)
            } ?: run { logger.warn { "trace/welch: unknown report format '$s'; skipping" }; null }
        }
}
