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

package ksl.app.swing.simopt.results.export

import ksl.app.config.optimization.OptimizationRunConfiguration
import ksl.app.session.RunResult
import ksl.simopt.solvers.SolverResult
import java.nio.file.Path

/**
 *  Canonical filenames for the auto-written artifacts.  Keeping
 *  them centralized makes it trivial for the Swing dashboard
 *  ([ksl.app.swing.simopt.results.ResultsFilesCard]) to discover
 *  what's on disk and offer per-file [Open] buttons.
 */
internal object ArtifactNames {
    const val SUMMARY_TOML = "summary.toml"
    const val ITERATION_HISTORY_CSV = "iteration_history.csv"
    const val BEST_SOLUTION_CSV = "best_solution.csv"
    const val CONVERGENCE_PNG = "convergence.png"
    const val REPORT_HTML = "report.html"
}

/**
 *  Result of an artifact-write attempt.  Per-artifact flags let
 *  callers (and tests) verify that a partial set landed even when
 *  individual writes failed.
 */
internal data class ArtifactWriteResult(
    val summaryTomlWritten: Boolean,
    val iterationHistoryCsvWritten: Boolean,
    val bestSolutionCsvWritten: Boolean,
    val convergencePngWritten: Boolean,
    val reportHtmlWritten: Boolean
) {
    val allWritten: Boolean get() =
        summaryTomlWritten && iterationHistoryCsvWritten &&
            bestSolutionCsvWritten && convergencePngWritten && reportHtmlWritten
    val anyWritten: Boolean get() =
        summaryTomlWritten || iterationHistoryCsvWritten ||
            bestSolutionCsvWritten || convergencePngWritten || reportHtmlWritten
}

/**
 *  Coordinator that writes the full artifact set on a successful
 *  run completion.  Each writer is best-effort — a single
 *  failure (e.g. lets-plot PNG export failing because the JVM
 *  can't find its native renderer) doesn't abort the rest.
 *
 *  The controller calls [writeCompleted] from its terminal-state
 *  observer when the run resolves as
 *  [RunResult.OptimizationCompleted].  Cancelled / failed runs
 *  go through [writeIncomplete] which writes only the summary
 *  TOML with `status = CANCELLED` / `FAILED`.
 */
internal object ResultsArtifactWriter {

    /** Write the full artifact set into [runDir] for a successful
     *  run.  Returns per-artifact success flags.
     *
     *  [solverResult] is the substrate's projection of the live
     *  solver's state — captured by the caller before the live
     *  `Solver` reference is cleared.  The HTML report writer
     *  delegates to the framework's
     *  [ksl.utilities.io.report.extensions.solverResult] DSL
     *  extension to render run-summary / evaluator-metrics /
     *  initial-current-best solution tables. */
    fun writeCompleted(
        config: OptimizationRunConfiguration,
        result: RunResult.OptimizationCompleted,
        solverResult: SolverResult,
        runDir: Path
    ): ArtifactWriteResult {
        val problem = config.problem
            ?: return ArtifactWriteResult(false, false, false, false, false)

        val summary = RunSummaryWriter.forCompleted(config, result)

        val summaryOk = RunSummaryWriter.write(
            summary, runDir.resolve(ArtifactNames.SUMMARY_TOML)
        )
        val historyOk = IterationHistoryCsvWriter.write(
            result.iterationHistory, problem, runDir.resolve(ArtifactNames.ITERATION_HISTORY_CSV)
        )
        val bestOk = BestSolutionCsvWriter.write(
            result.bestSolution, problem, runDir.resolve(ArtifactNames.BEST_SOLUTION_CSV)
        )
        val pngOk = ConvergencePlotBuilder.write(
            result.iterationHistory, runDir.resolve(ArtifactNames.CONVERGENCE_PNG)
        )
        val htmlOk = HtmlReportWriter.write(
            config = config,
            runResult = result,
            solverResult = solverResult,
            path = runDir.resolve(ArtifactNames.REPORT_HTML)
        )

        return ArtifactWriteResult(
            summaryTomlWritten = summaryOk,
            iterationHistoryCsvWritten = historyOk,
            bestSolutionCsvWritten = bestOk,
            convergencePngWritten = pngOk,
            reportHtmlWritten = htmlOk
        )
    }

    /** Write a partial artifact set for a cancelled or failed run.
     *
     *  Only the summary TOML is written — without a completed
     *  result and a settled iteration history there's nothing to
     *  meaningfully render in CSV / PNG / HTML.  The TOML records
     *  algorithm, start/end times, the best-so-far snapshot if
     *  any iteration fired, and the cancellation reason. */
    fun writeIncomplete(
        config: OptimizationRunConfiguration,
        status: ResultsStatus,
        runId: String,
        startTimeIso: String,
        endTimeIso: String,
        elapsedMillis: Long,
        latestBest: LatestBestSnapshot?,
        statusReason: String?,
        runDir: Path
    ): ArtifactWriteResult {
        val summary = RunSummaryWriter.forIncomplete(
            config = config,
            status = status,
            runId = runId,
            startTimeIso = startTimeIso,
            endTimeIso = endTimeIso,
            elapsedMillis = elapsedMillis,
            latestBest = latestBest,
            statusReason = statusReason
        )
        val summaryOk = RunSummaryWriter.write(
            summary, runDir.resolve(ArtifactNames.SUMMARY_TOML)
        )
        return ArtifactWriteResult(
            summaryTomlWritten = summaryOk,
            iterationHistoryCsvWritten = false,
            bestSolutionCsvWritten = false,
            convergencePngWritten = false,
            reportHtmlWritten = false
        )
    }
}
