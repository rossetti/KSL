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

import ksl.controls.experiments.Scenario
import ksl.controls.experiments.ScenarioRunner
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.ReportBuilder
import ksl.utilities.io.report.dsl.report

/**
 * DSL extension functions on [ReportBuilder] for rendering [Scenario] and
 * [ScenarioRunner] within the KSL reporting framework.
 *
 * **Separation of concerns — granular functions (composable building blocks):**
 * - [scenario] — overview table (name, model, configured settings, status) followed
 *   by the full [simulationRun] detail when the scenario has been executed;
 *   gracefully handles un-executed scenarios with a paragraph notice
 * - [scenarioRunner] — composite report: runner overview, scenario summary table,
 *   and one [scenario] section per scenario in the runner's list
 *
 * **Zero-code entry points:**
 * - [Scenario.toReport] — standalone scenario document
 * - [ScenarioRunner.toReport] — full runner report
 *
 * **Composability example — runner summary with custom preamble:**
 * ```kotlin
 * report("Queue Study — Scenario Results") {
 *     paragraph("Three server-count alternatives were compared under the same arrival process.")
 *     scenarioRunner(runner, confidenceLevel = 0.99)
 * }
 * ```
 */

// ── DSL Function 1: Single Scenario ──────────────────────────────────────────

/**
 * Appends a section reporting the configuration and results of [scenario].
 *
 * **Produces (inside a section titled `caption` or the scenario name):**
 * 1. **Scenario Overview** — `DataTable` (Property | Value): scenario name, model
 *    name, configured replications, run length, warm-up period, and execution status
 * 2. **Simulation Results** sub-section — full [simulationRun] detail (run identity,
 *    run parameters, inputs, optional timing, response statistics); present only when
 *    the scenario has been executed
 * 3. Paragraph notice when the scenario has not yet been executed
 *
 * @param scenario    the [Scenario] to report
 * @param confidenceLevel confidence level for response statistics; defaults to 0.95
 * @param showTimings `true` includes the Replication Timing sub-section in the
 *                    embedded simulation run report; defaults to `false`
 * @param caption     optional section title; defaults to the scenario name
 */
fun ReportBuilder.scenario(
    scenario: Scenario,
    confidenceLevel: Double = 0.95,
    showTimings: Boolean = false,
    caption: String? = null
) {
    section(caption ?: scenario.name) {
        val myRun    = scenario.simulationRun
        val myHasRun = myRun != null

        // ── 1. Scenario overview ──────────────────────────────────────────────
        dataTable(
            headers = listOf("Property", "Value"),
            rows    = listOf(
                listOf("Scenario Name",   scenario.name),
                listOf("Model Name",      scenario.model.name),
                listOf("Replications",    scenario.numberOfReplications.toString()),
                listOf("Run Length",      fmtSCR(scenario.lengthOfReplication)),
                listOf("Warm-Up",         fmtSCR(scenario.lengthOfReplicationWarmUp)),
                listOf("Status",          if (myHasRun) "Executed" else "Not Executed")
            ),
            caption = "Scenario Overview"
        )

        // ── 2. Simulation results or not-executed notice ──────────────────────
        if (myHasRun) {
            simulationRun(myRun!!, confidenceLevel, showTimings, caption = "Simulation Results")
        } else {
            paragraph(
                "This scenario has not been executed. Call `scenario.simulate()` or " +
                "include it in a `ScenarioRunner` before generating a full report."
            )
        }
    }
}

// ── DSL Function 2: Scenario Runner (composite) ───────────────────────────────

/**
 * Appends a self-contained section reporting the full contents of [runner].
 *
 * **Produces (inside a section titled `caption` or `"Scenario Runner"`):**
 * 1. **Runner Overview** — `DataTable` (Property | Value): runner name, output
 *    directory path, total scenario count, executed count, pending count
 * 2. **Scenario Summary** sub-section — `DataTable` (Scenario Name | Model Name |
 *    Replications | Run Length | Warm-Up | Status | Error) with one row per scenario;
 *    the Error column shows `"—"` for un-executed scenarios, `"No"` for clean runs,
 *    and `"Yes ⚠"` when [ksl.controls.experiments.SimulationRun.runErrorMsg] is non-empty
 * 3. One [scenario] sub-section per scenario in [ScenarioRunner.scenarioList]
 *
 * @param runner          the [ScenarioRunner] to report
 * @param confidenceLevel confidence level for response statistics; defaults to 0.95
 * @param showTimings     `true` includes Replication Timing sub-sections in each
 *                        embedded scenario run report; defaults to `false`
 * @param caption         optional section title; defaults to `"Scenario Runner"`
 */
fun ReportBuilder.scenarioRunner(
    runner: ScenarioRunner,
    confidenceLevel: Double = 0.95,
    showTimings: Boolean = false,
    caption: String? = null
) {
    section(caption ?: "Scenario Runner") {
        val myTotal    = runner.scenarioList.size
        val myExecuted = runner.scenarioList.count { it.simulationRun != null }
        val myPending  = myTotal - myExecuted

        // ── 1. Runner overview ────────────────────────────────────────────────
        dataTable(
            headers = listOf("Property", "Value"),
            rows    = listOf(
                listOf("Runner Name",         runner.name),
                listOf("Output Directory",    runner.pathToOutputDirectory.toString()),
                listOf("Total Scenarios",     myTotal.toString()),
                listOf("Scenarios Executed",  myExecuted.toString()),
                listOf("Scenarios Pending",   myPending.toString())
            ),
            caption = "Scenario Runner Overview"
        )

        // ── 2. Scenario summary table ─────────────────────────────────────────
        section("Scenario Summary") {
            val mySummaryRows = runner.scenarioList.map { s ->
                val myRun = s.simulationRun
                listOf(
                    s.name,
                    s.model.name,
                    s.numberOfReplications.toString(),
                    fmtSCR(s.lengthOfReplication),
                    fmtSCR(s.lengthOfReplicationWarmUp),
                    if (myRun != null) "Executed" else "Not Executed",
                    when {
                        myRun == null    -> "\u2014"
                        myRun.hasError   -> "Yes \u26a0"
                        else             -> "No"
                    }
                )
            }
            dataTable(
                headers = listOf(
                    "Scenario Name", "Model Name", "Replications",
                    "Run Length", "Warm-Up", "Status", "Error"
                ),
                rows    = mySummaryRows,
                caption = "Scenarios ($myTotal total, $myExecuted executed)"
            )
        }

        // ── 3. Per-scenario sections ──────────────────────────────────────────
        for (s in runner.scenarioList) {
            scenario(s, confidenceLevel, showTimings)
        }
    }
}

// ── toReport() — zero-code entry points ──────────────────────────────────────

/**
 * Builds a [ReportNode.Document] containing a standalone scenario report
 * via [scenario].
 *
 * Zero-code path:
 * ```kotlin
 * scenario.simulate()
 * scenario.toReport("Queue Baseline").showInBrowser()
 * ```
 *
 * @param title           document title; defaults to the scenario name
 * @param confidenceLevel confidence level for response statistics; defaults to 0.95
 * @param showTimings     `true` includes the Replication Timing sub-section;
 *                        defaults to `false`
 * @param block           optional DSL block; replaces the default when provided
 * @return the assembled [ReportNode.Document]
 */
fun Scenario.toReport(
    title: String = "Scenario \u2014 $name",
    confidenceLevel: Double = 0.95,
    showTimings: Boolean = false,
    block: ReportBuilder.() -> Unit = { scenario(this@toReport, confidenceLevel, showTimings) }
): ReportNode.Document = report(title, block)

/**
 * Builds a [ReportNode.Document] containing a full scenario runner report
 * via [scenarioRunner].
 *
 * Zero-code path:
 * ```kotlin
 * runner.simulate()
 * runner.toReport("Queue Study").showInBrowser()
 * ```
 *
 * Custom block:
 * ```kotlin
 * runner.toReport("Server Count Study") {
 *     paragraph("Three server configurations were compared.")
 *     scenarioRunner(this@toReport, confidenceLevel = 0.99)
 * }
 * ```
 *
 * @param title           document title; defaults to the runner name
 * @param confidenceLevel confidence level for response statistics; defaults to 0.95
 * @param showTimings     `true` includes Replication Timing sub-sections;
 *                        defaults to `false`
 * @param block           optional DSL block; replaces the default when provided
 * @return the assembled [ReportNode.Document]
 */
fun ScenarioRunner.toReport(
    title: String = "Scenario Runner \u2014 $name",
    confidenceLevel: Double = 0.95,
    showTimings: Boolean = false,
    block: ReportBuilder.() -> Unit = { scenarioRunner(this@toReport, confidenceLevel, showTimings) }
): ReportNode.Document = report(title, block)

// ── Private formatting helper ─────────────────────────────────────────────────

/** Formats a [Double] to 4 decimal places; returns `"—"` for NaN or infinite values. */
private fun fmtSCR(value: Double): String = when {
    value.isNaN() || value.isInfinite() -> "\u2014"
    else -> "%.4f".format(value)
}
