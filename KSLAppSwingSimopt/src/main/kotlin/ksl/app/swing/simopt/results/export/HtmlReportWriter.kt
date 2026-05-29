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
import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.SolverResult
import ksl.utilities.io.report.dsl.report
import ksl.utilities.io.report.extensions.solverConfiguration
import ksl.utilities.io.report.extensions.solverResult
import ksl.utilities.io.report.writeHtml
import java.nio.file.Files
import java.nio.file.Path

/**
 *  HTML report writer for a finished optimization run.
 *
 *  Built on the framework's
 *  [ksl.utilities.io.report.dsl.ReportBuilder] DSL and the
 *  simulation-optimization-aware
 *  [ksl.utilities.io.report.extensions.solverResult] / `solver(...)`
 *  extensions.  These already render:
 *  - **Run Summary** — solver, problem, termination, total iterations,
 *    execution time
 *  - **Evaluator Metrics** — evaluator calls, design points evaluated,
 *    replications (requested / oracle / cached), cache savings %
 *  - **Initial / Current / Best Solution** sub-sections, each with a
 *    decision-variable table, the objective-function statistics
 *    (count, average, std dev, 95% half-width), and response-constraint
 *    estimates when present.
 *
 *  Around that substrate-rendered body the writer adds:
 *  - The interactive [ConvergencePlot] (zoomable, hover-aware) via
 *    the DSL's `plot(...)` primitive — the HTML renderer embeds the
 *    lets-plot JS fragment so the report is fully interactive in a
 *    browser.
 *  - A compact 5-column iteration-metrics table.  Per-iteration
 *    decision-variable values intentionally live in
 *    `iteration_history.csv` rather than the report so the document
 *    stays compact when problems have many decision variables.
 *
 *  Renders through [writeHtml] with the framework's built-in CSS so
 *  the styling matches reports produced by other KSL apps.
 */
internal object HtmlReportWriter {

    /**
     *  Write the report to [path] using a captured [SolverResult].
     *
     *  The caller (the controller's terminal observer) is
     *  responsible for capturing the [SolverResult] *before* the
     *  live `Solver` reference is cleared — the substrate's
     *  `Solver.solverResult` property is only meaningful while the
     *  `Solver` instance is reachable.
     */
    fun write(
        config: OptimizationRunConfiguration,
        runResult: RunResult.OptimizationCompleted,
        solverResult: SolverResult,
        solverInstance: Solver,
        path: Path
    ): Boolean = try {
        Files.createDirectories(path.parent)
        val analysisName = config.output.analysisName
        val convergencePlot = ConvergencePlotBuilder.buildPlot(runResult.iterationHistory)

        val doc = report("SimOpt results — $analysisName") {
            paragraph("Run output directory: ${path.parent}")

            // Convergence — interactive lets-plot fragment.  We
            // wrap it in its own section so the HTML renderer's
            // per-section plot cap doesn't suppress anything else.
            if (convergencePlot != null) {
                section("Convergence") {
                    plot(convergencePlot, caption = "Best estimated objective vs. iteration")
                }
            }

            // Configuration that produced the result.  Substrate
            // extension iterates Solver.configurationProperties in
            // insertion order (base fields first, then subclass
            // fields, then innerSolver.* for RandomRestartSolver).
            section("Solver configuration") {
                solverConfiguration(solverInstance)
            }

            // Substrate-rendered solver summary, evaluator metrics,
            // and per-solution detail tables (decision variables +
            // objective stats with 95% half-widths + response
            // estimates).
            solverResult(solverResult, caption = "Solver results")

            // Iteration metrics — 5 fixed columns; per-iteration
            // input columns live in iteration_history.csv.
            section("Iteration metrics") {
                paragraph(
                    "Per-iteration decision-variable values live in iteration_history.csv " +
                        "alongside this report; only the five metric columns appear here."
                )
                dataTable(
                    headers = listOf("iteration", "oracle calls", "replications",
                        "est obj", "pen obj"),
                    rows = runResult.iterationHistory.map { snap ->
                        listOf(
                            snap.iterationNumber.toString(),
                            snap.numOracleCalls.toString(),
                            snap.numReplicationsRequested.toString(),
                            formatObj(snap.estimatedObjFncValue),
                            formatObj(snap.penalizedObjFncValue)
                        )
                    },
                    caption = "Iteration metrics"
                )
            }
        }
        doc.writeHtml(path)
        true
    } catch (_: Throwable) {
        false
    }

    /** Sentinel-aware objective formatting matching the GUI's
     *  `formatObjective` so the report and the Execute panel agree
     *  on how `±Double.MAX_VALUE` and `NaN` render. */
    private fun formatObj(v: Double): String = when {
        v == Double.MAX_VALUE || v == Double.POSITIVE_INFINITY -> "+∞"
        v == -Double.MAX_VALUE || v == Double.NEGATIVE_INFINITY -> "−∞"
        v.isNaN() -> "—"
        else -> "%.4f".format(v)
    }
}
