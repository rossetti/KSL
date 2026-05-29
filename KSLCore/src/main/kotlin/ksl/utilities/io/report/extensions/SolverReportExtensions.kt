/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.utilities.io.report.extensions

import ksl.simopt.evaluator.Solution
import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.SolverResult
import ksl.utilities.io.report.dsl.ReportBuilder

/**
 * DSL extension functions on [ReportBuilder] for rendering simulation optimisation
 * results from [Solver] and [SolverResult].
 *
 * Neither [Solver] nor [SolverResult] is modified; all bridging is done through
 * non-intrusive extension functions.
 *
 * Two entry points are provided:
 * - [solverResult] — takes a [SolverResult] directly (useful when results are
 *   persisted or passed between components)
 * - [solver] — convenience wrapper that calls `solver.solverResult` then delegates
 *   to [solverResult]
 */

/**
 * Appends a self-contained section reporting the outcome of a simulation
 * optimisation run described by a [SolverResult].
 *
 * **`SolverResult.NotExecuted`** — emits a single `Paragraph` stating that the
 * solver has not been run yet.
 *
 * **`SolverResult.Completed`** — emits (inside a section titled `caption` or the
 * solver name):
 * 1. A `DataTable` ("Run Summary") — solver name, problem name, termination reason,
 *    stopping criteria satisfied, execution time, and total iterations
 * 2. A `DataTable` ("Evaluator Metrics") — evaluator calls, design points evaluated,
 *    total replications requested, oracle replications, and cached replications
 * 3. Optionally a sub-section for the initial solution (when non-null)
 * 4. A sub-section for the current (final) solution
 * 5. A sub-section for the best solution found (when non-null)
 *
 * Each solution sub-section contains:
 * - A `DataTable` ("Decision Variables") with columns `Variable | Value` from the
 *   solution's [ksl.simopt.problem.InputMap]
 * - A `DataTable` ("Objective Function") with columns
 *   `Name | Count | Average | Std Dev | Half Width` for the estimated objective
 * - A `DataTable` ("Response Estimates") (when responses are present) with the same
 *   columns for each response constraint estimate
 *
 * Usage:
 * ```kotlin
 * solver.runAllIterations()
 * val doc = report("Optimisation Results") {
 *     solverResult(solver.solverResult)
 * }
 * ```
 *
 * @param result  the solver result to report
 * @param caption optional section title; defaults to the solver name embedded in
 *                the result, or `"Solver Results"` when blank
 */
fun ReportBuilder.solverResult(
    result: SolverResult,
    caption: String? = null
) {
    when (result) {
        is SolverResult.NotExecuted -> {
            val myTitle = caption ?: result.solverName.ifBlank { "Solver Results" }
            section(myTitle) {
                paragraph(
                    "Solver \"${result.solverName}\" has not yet been executed " +
                    "on problem \"${result.problemName}\". " +
                    "Call runAllIterations() to generate results."
                )
            }
        }

        is SolverResult.Completed -> {
            val myTitle = caption ?: result.solverName.ifBlank { "Solver Results" }
            section(myTitle) {
                // ── 1. Run summary ────────────────────────────────────────────
                val myTimeStr = result.executionTimeMillis?.let { "${it} ms" } ?: "Not tracked"
                val myTermReason = if (result.isStoppingCriteriaMet)
                    "Stopping criteria satisfied" else "Execution halted / error"
                dataTable(
                    headers = listOf("Property", "Value"),
                    rows = listOf(
                        listOf("Solver",                   result.solverName),
                        listOf("Problem",                  result.problemName),
                        listOf("Termination",              myTermReason),
                        listOf("Stopping Criteria Met",    result.isStoppingCriteriaMet.toString()),
                        listOf("Total Iterations",         result.totalIterations.toString()),
                        listOf("Execution Time",           myTimeStr)
                    ),
                    caption = "Run Summary"
                )

                // ── 2. Evaluator metrics ──────────────────────────────────────
                val myMetrics = result.evaluatorMetrics
                val myReqTotal = myMetrics.totalReplicationsRequested
                val mySavedPct = if (myReqTotal > 0)
                    "%.1f%%".format(myMetrics.totalCachedReplications.toDouble() / myReqTotal * 100.0)
                else "—"
                dataTable(
                    headers = listOf("Metric", "Value"),
                    rows = listOf(
                        listOf("Evaluator Calls",           myMetrics.totalEvaluatorCalls.toString()),
                        listOf("Design Points Evaluated",   myMetrics.totalDesignPointsEvaluated.toString()),
                        listOf("Replications Requested",    myReqTotal.toString()),
                        listOf("Oracle Replications",       myMetrics.totalOracleReplications.toString()),
                        listOf("Cached Replications",       myMetrics.totalCachedReplications.toString()),
                        listOf("Cache Savings",             mySavedPct)
                    ),
                    caption = "Evaluator Metrics"
                )

                // ── 3. Initial solution (optional) ────────────────────────────
                if (result.initialSolution != null) {
                    section("Initial Solution") {
                        solutionTables(result.initialSolution)
                    }
                }

                // ── 4. Current (final) solution ───────────────────────────────
                section("Current Solution") {
                    solutionTables(result.currentSolution)
                }

                // ── 5. Best solution (optional) ───────────────────────────────
                if (result.bestSolution != null) {
                    section("Best Solution Found") {
                        solutionTables(result.bestSolution)
                    }
                }
            }
        }
    }
}

/**
 * Convenience overload that reads [Solver.solverResult] and delegates to
 * [solverResult].
 *
 * Usage:
 * ```kotlin
 * solver.runAllIterations()
 * val doc = report("Optimisation Study") {
 *     solver(mySolver)
 * }
 * ```
 *
 * @param s       the solver whose current result will be reported
 * @param caption optional section title; defaults to the solver's name
 */
fun ReportBuilder.solver(
    s: Solver,
    caption: String? = null
) {
    solverResult(s.solverResult, caption ?: s.name.ifBlank { null })
}

/**
 * Appends a single [DataTable] enumerating the supplied solver's
 * configuration properties — the structured form of
 * [Solver.toString], usable in any [ReportBuilder]-built document.
 *
 * Pairs naturally with [solverResult] / [solver] in the same report:
 * the configuration table documents *what was run* and the result
 * tables document *what came out*.
 *
 * Insertion order of [Solver.configurationProperties] is preserved
 * — base-class fields appear first, then each subclass's
 * distinctive fields.
 *
 * Usage:
 * ```kotlin
 * solver.runAllIterations()
 * val doc = report("Optimisation Study") {
 *     solverConfiguration(mySolver)
 *     solver(mySolver)
 * }
 * ```
 *
 * @param s        the solver whose configuration is enumerated
 * @param caption  optional table caption; defaults to "Solver Configuration"
 */
fun ReportBuilder.solverConfiguration(
    s: Solver,
    caption: String = "Solver Configuration"
) {
    val rows = s.configurationProperties.entries.map { (k, v) -> listOf(k, v) }
    if (rows.isEmpty()) return
    dataTable(
        headers = listOf("Property", "Value"),
        rows = rows,
        caption = caption
    )
}

// ── Private helpers ───────────────────────────────────────────────────────────

/**
 * Emits the decision-variable table, objective function table, and (when present)
 * response estimate table for a [Solution].
 *
 * Called from within an already-open section block; does not create its own section.
 */
private fun ReportBuilder.solutionTables(sol: Solution) {
    // Decision variables
    dataTable(
        headers = listOf("Variable", "Value"),
        rows = sol.inputMap.entries.map { (name, value) ->
            listOf(name, fmtDouble(value))
        },
        caption = "Decision Variables"
    )

    // Objective function
    val myObj = sol.estimatedObjFnc
    val myObjHw = if (myObj.count > 1.0)
        myObj.standardDeviation / Math.sqrt(myObj.count) * 1.96 else Double.NaN
    dataTable(
        headers = listOf("Name", "Count", "Average", "Std Dev", "Half Width (95%)"),
        rows = listOf(
            listOf(
                myObj.name,
                myObj.count.toInt().toString(),
                fmtDouble(myObj.average),
                fmtDouble(myObj.standardDeviation),
                fmtDouble(myObjHw)
            )
        ),
        caption = "Objective Function"
    )

    // Response constraint estimates (if any)
    if (sol.responseEstimates.isNotEmpty()) {
        val myHeaders = listOf("Response", "Count", "Average", "Std Dev", "Half Width (95%)")
        val myRows = sol.responseEstimates.map { re ->
            val myHw = if (re.count > 1.0)
                re.standardDeviation / Math.sqrt(re.count) * 1.96 else Double.NaN
            listOf(
                re.name,
                re.count.toInt().toString(),
                fmtDouble(re.average),
                fmtDouble(re.standardDeviation),
                fmtDouble(myHw)
            )
        }
        dataTable(myHeaders, myRows, caption = "Response Estimates")
    }
}

