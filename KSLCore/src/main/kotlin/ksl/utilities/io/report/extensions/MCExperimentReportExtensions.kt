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

import ksl.utilities.io.plotting.ScatterPlot
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.ReportBuilder
import ksl.utilities.io.report.dsl.report
import ksl.utilities.mcintegration.MC1DIntegration
import ksl.utilities.mcintegration.MCExperiment
import ksl.utilities.mcintegration.MCMultiVariateIntegration

/**
 * DSL extension functions on [ReportBuilder] for rendering
 * [MCExperiment] results within the KSL reporting framework.
 *
 * **Separation of concerns — granular functions (composable building blocks):**
 * - [mcExperimentConfig] — configuration `DataTable` section only (setup parameters;
 *   usable before the simulation has been run)
 * - [mcExperimentDiagnostics] — convergence-diagnostics section only (achieved HW,
 *   error gap, convergence status, optional history plot); shows a "not run" message
 *   when the experiment has not yet been executed
 * - [mcExperiment] — composite function that calls [mcExperimentConfig],
 *   [mcExperimentDiagnostics], and [statPropertyTable] in sequence; the standard
 *   full-report building block for any [MCExperiment]
 * - [mc1DIntegration] — thin wrapper for [MC1DIntegration]; prepends a
 *   **Integration Problem Setup** section (function class, sampler class, antithetic
 *   flag) and delegates to [mcExperiment]
 * - [mcMultiVariateIntegration] — thin wrapper for [MCMultiVariateIntegration];
 *   prepends a **Integration Problem Setup** section (dimension, function class,
 *   sampler class, antithetic flag) and delegates to [mcExperiment]
 *
 * **Zero-code entry points:**
 * - [MCExperiment.toReport] — full report via [mcExperiment]
 * - [MC1DIntegration.toReport] — full report via [mc1DIntegration]
 * - [MCMultiVariateIntegration.toReport] — full report via [mcMultiVariateIntegration]
 *
 * **Composability example — configuration and diagnostics only:**
 * ```kotlin
 * report("Experiment Setup Review") {
 *     mcExperimentConfig(exp)
 *     mcExperimentDiagnostics(exp)
 *     // statPropertyTable omitted deliberately
 *     paragraph("Convergence met — no further tuning required.")
 * }
 * ```
 *
 * **Optional convergence-history plot:**
 * Call `exp.saveConvergenceHistory = true` before [MCExperiment.runSimulation] to
 * enable history recording. When history is present, [mcExperimentDiagnostics]
 * appends a [ScatterPlot] of half-width vs macro-replication number with a
 * horizontal reference line at [MCExperiment.desiredHWErrorBound].
 */

// ── DSL Function 1a: Experiment Configuration (granular) ─────────────────────

/**
 * Appends a single **Experiment Configuration** section containing a `DataTable`
 * of all setup parameters for [exp].
 *
 * This is a standalone building block. It does not require the simulation to have
 * been run — it reports the current values of the configuration properties only.
 * Use it when you want the configuration section without diagnostics or statistics,
 * or when composing a custom report layout.
 *
 * **Produces (inside a section titled `caption` or `"Experiment Configuration"`):**
 * - `DataTable` (headers: Parameter | Value): initial sample size, max sample size,
 *   micro reps per macro, desired HW error bound, confidence level, reset stream
 *   option, save convergence history flag
 *
 * @param exp     the [MCExperiment] whose configuration to report
 * @param caption optional section title; defaults to `"Experiment Configuration"`
 */
fun ReportBuilder.mcExperimentConfig(
    exp: MCExperiment,
    caption: String? = null
) {
    val myConfigRows = listOf(
        listOf("Initial Sample Size",       exp.initialSampleSize.toString()),
        listOf("Max Sample Size",           exp.maxSampleSize.toString()),
        listOf("Micro Reps per Macro",      exp.microRepSampleSize.toString()),
        listOf("Desired HW Error Bound",    fmtD(exp.desiredHWErrorBound)),
        listOf("Confidence Level",          exp.confidenceLevel.toString()),
        listOf("Reset Stream Option",       exp.resetStreamOption.toString()),
        listOf("Save Convergence History",  exp.saveConvergenceHistory.toString())
    )
    section(caption ?: "Experiment Configuration") {
        dataTable(
            headers = listOf("Parameter", "Value"),
            rows    = myConfigRows,
            caption = "Experiment Configuration"
        )
    }
}

// ── DSL Function 1b: Convergence Diagnostics (granular) ──────────────────────

/**
 * Appends a single **Convergence Diagnostics** section for [exp].
 *
 * This is a standalone building block. It shows whether the half-width criterion
 * was met, the achieved vs desired half-width, the error gap, the estimated sample
 * size needed, and total observations executed. When [MCExperiment.saveConvergenceHistory]
 * was `true` before the run, a [ScatterPlot] of half-width vs macro-replication
 * number is appended with a horizontal reference line at [MCExperiment.desiredHWErrorBound].
 *
 * When the experiment has not yet been run, a single paragraph is emitted instead.
 *
 * **Produces (inside a section titled `caption` or `"Convergence Diagnostics"`):**
 * - `DataTable` (headers: Diagnostic | Value): convergence met, desired HW bound,
 *   achieved HW, error gap, estimated sample size needed, macro replications run,
 *   micro reps per macro, total observations
 * - Optional [ScatterPlot] of half-width history (when history was recorded)
 *
 * @param exp     the [MCExperiment] whose diagnostics to report
 * @param caption optional section title; defaults to `"Convergence Diagnostics"`
 */
fun ReportBuilder.mcExperimentDiagnostics(
    exp: MCExperiment,
    caption: String? = null
) {
    section(caption ?: "Convergence Diagnostics") {
        val myStats = exp.statistics()
        if (myStats.count == 0.0) {
            paragraph("No macro replications have been executed. Run the experiment to see results.")
        } else {
            val myHw        = myStats.halfWidth
            val myGap       = myHw - exp.desiredHWErrorBound
            val myConverged = exp.checkStoppingCriteria()
            val myEstN      = exp.estimateSampleSize()
            val myTotalObs  = myStats.count.toLong() * exp.microRepSampleSize

            dataTable(
                headers = listOf("Diagnostic", "Value"),
                rows    = listOf(
                    listOf("Convergence Met",              myConverged.toString()),
                    listOf("Desired HW Bound",             fmtD(exp.desiredHWErrorBound)),
                    listOf("Achieved Half-Width",          fmtD(myHw)),
                    listOf("Error Gap (HW \u2212 Bound)",  fmtD(myGap)),
                    listOf("Estimated Sample Size Needed", if (myEstN.isNaN()) "\u2014" else myEstN.toLong().toString()),
                    listOf("Macro Replications Run",       myStats.count.toLong().toString()),
                    listOf("Micro Reps per Macro",         exp.microRepSampleSize.toString()),
                    listOf("Total Observations",           myTotalObs.toString())
                ),
                caption = "Convergence Diagnostics"
            )

            // Optional convergence-history plot
            val myHistory = exp.convergenceHistory
            if (myHistory != null && myHistory.size >= 2) {
                val myX = DoubleArray(myHistory.size) { (it + 1).toDouble() }
                plot(
                    ScatterPlot(
                        x                   = myX,
                        y                   = myHistory,
                        horizontalReference = exp.desiredHWErrorBound
                    ),
                    caption = "Half-Width Convergence History (reference line = desired bound)"
                )
            }
        }
    }
}

// ── DSL Function 1c: Base MCExperiment (composite) ───────────────────────────

/**
 * Appends a self-contained section reporting the configuration, convergence
 * diagnostics, and statistical estimate for any [MCExperiment].
 *
 * Delegates to [mcExperimentConfig], [mcExperimentDiagnostics], and
 * [statPropertyTable] in sequence. Use the granular functions directly when
 * you need only a subset of these sections.
 *
 * **Produces (inside a section titled `caption` or `"Monte Carlo Experiment"`):**
 * 1. [mcExperimentConfig] section — setup parameters
 * 2. [mcExperimentDiagnostics] section — convergence status, HW gap, optional
 *    history plot; omitted when the experiment has not been run
 * 3. [statPropertyTable] — all 19 statistical fields via
 *    [ksl.utilities.statistic.StatisticIfc]; omitted when the experiment has not
 *    been run
 *
 * @param exp     the [MCExperiment] to report
 * @param caption optional section title; defaults to `"Monte Carlo Experiment"`
 */
fun ReportBuilder.mcExperiment(
    exp: MCExperiment,
    caption: String? = null
) {
    val myTitle = caption ?: "Monte Carlo Experiment"
    section(myTitle) {
        mcExperimentConfig(exp)
        val myStats = exp.statistics()
        if (myStats.count == 0.0) {
            paragraph("No macro replications have been executed. Run the experiment to see results.")
        } else {
            mcExperimentDiagnostics(exp)
            statPropertyTable(
                stat            = myStats,
                caption         = "Monte Carlo Estimate",
                confidenceLevel = exp.confidenceLevel
            )
        }
    }
}

// ── DSL Function 2: MC1DIntegration ──────────────────────────────────────────

/**
 * Appends a self-contained section for a [MC1DIntegration] experiment.
 *
 * **Produces (inside a section titled `caption` or `"MC 1D Integration"`):**
 * 1. **Integration Problem Setup** `DataTable` — function class, sampler class,
 *    antithetic option
 * 2. Full [mcExperiment] section (configuration, convergence diagnostics, estimate)
 *
 * @param mc      the [MC1DIntegration] to report
 * @param caption optional section title
 */
fun ReportBuilder.mc1DIntegration(
    mc: MC1DIntegration,
    caption: String? = null
) {
    val myTitle = caption ?: "MC 1D Integration"
    section(myTitle) {

        // ── Integration Problem Setup ─────────────────────────────────────────
        val mySetupRows = listOf(
            listOf("Function",          mc.function.javaClass.simpleName.ifBlank { mc.function.javaClass.name }),
            listOf("Sampler",           mc.sampler.javaClass.simpleName.ifBlank  { mc.sampler.javaClass.name }),
            listOf("Antithetic Option", mc.isAntitheticOptionOn.toString())
        )
        dataTable(
            headers = listOf("Parameter", "Value"),
            rows    = mySetupRows,
            caption = "Integration Problem Setup"
        )

        // ── Experiment (configuration + diagnostics + estimate) ───────────────
        mcExperiment(mc)
    }
}

// ── DSL Function 3: MCMultiVariateIntegration ─────────────────────────────────

/**
 * Appends a self-contained section for a [MCMultiVariateIntegration] experiment.
 *
 * **Produces (inside a section titled `caption` or `"MC Multivariate Integration"`):**
 * 1. **Integration Problem Setup** `DataTable` — dimension, function class, sampler
 *    class, antithetic option
 * 2. Full [mcExperiment] section (configuration, convergence diagnostics, estimate)
 *
 * @param mc      the [MCMultiVariateIntegration] to report
 * @param caption optional section title
 */
fun ReportBuilder.mcMultiVariateIntegration(
    mc: MCMultiVariateIntegration,
    caption: String? = null
) {
    val myTitle = caption ?: "MC Multivariate Integration"
    section(myTitle) {

        // ── Integration Problem Setup ─────────────────────────────────────────
        val mySetupRows = listOf(
            listOf("Dimension",         mc.function.dimension.toString()),
            listOf("Function",          mc.function.javaClass.simpleName.ifBlank { mc.function.javaClass.name }),
            listOf("Sampler",           mc.sampler.javaClass.simpleName.ifBlank  { mc.sampler.javaClass.name }),
            listOf("Antithetic Option", mc.isAntitheticOptionOn.toString())
        )
        dataTable(
            headers = listOf("Parameter", "Value"),
            rows    = mySetupRows,
            caption = "Integration Problem Setup"
        )

        // ── Experiment (configuration + diagnostics + estimate) ───────────────
        mcExperiment(mc)
    }
}

// ── toReport() — zero-code entry points ──────────────────────────────────────

/**
 * Builds a [ReportNode.Document] containing a full Monte Carlo experiment report
 * via [mcExperiment]. Suitable for any [MCExperiment], including user-defined
 * subclasses that supply a custom [ksl.utilities.mcintegration.MCReplicationIfc].
 *
 * Zero-code path:
 * ```kotlin
 * val exp = MCExperiment(myReplication)
 * exp.runSimulation()
 * exp.toReport("News Vendor Problem").showInBrowser()
 * ```
 *
 * Custom block:
 * ```kotlin
 * exp.toReport("News Vendor Problem") {
 *     mcExperiment(exp)
 *     paragraph("The optimal order quantity is approximately 30 units.")
 * }
 * ```
 *
 * @param title  document title; defaults to `"Monte Carlo Experiment"`
 * @param block  optional DSL block; replaces the default when provided
 * @return the assembled [ReportNode.Document]
 */
fun MCExperiment.toReport(
    title: String = "Monte Carlo Experiment",
    block: ReportBuilder.() -> Unit = { mcExperiment(this@toReport) }
): ReportNode.Document = report(title, block)

/**
 * Builds a [ReportNode.Document] containing a full 1-D integration report via
 * [mc1DIntegration] (problem setup + configuration + diagnostics + estimate).
 *
 * Zero-code path:
 * ```kotlin
 * val mc = MC1DIntegration(SinFunc(), UniformRV(0.0, Math.PI))
 * mc.runSimulation()
 * mc.toReport("Sine Integral").showInBrowser()
 * ```
 *
 * @param title  document title; defaults to `"MC 1D Integration"`
 * @param block  optional DSL block; replaces the default when provided
 * @return the assembled [ReportNode.Document]
 */
fun MC1DIntegration.toReport(
    title: String = "MC 1D Integration",
    block: ReportBuilder.() -> Unit = { mc1DIntegration(this@toReport) }
): ReportNode.Document = report(title, block)

/**
 * Builds a [ReportNode.Document] containing a full multivariate integration report
 * via [mcMultiVariateIntegration] (problem setup + configuration + diagnostics +
 * estimate).
 *
 * Zero-code path:
 * ```kotlin
 * val mc = MCMultiVariateIntegration(MyFunc(), MVIndependentRV(2, UniformRV()))
 * mc.runSimulation()
 * mc.toReport("2D Integration Study").showInBrowser()
 * ```
 *
 * @param title  document title; defaults to `"MC Multivariate Integration"`
 * @param block  optional DSL block; replaces the default when provided
 * @return the assembled [ReportNode.Document]
 */
fun MCMultiVariateIntegration.toReport(
    title: String = "MC Multivariate Integration",
    block: ReportBuilder.() -> Unit = { mcMultiVariateIntegration(this@toReport) }
): ReportNode.Document = report(title, block)

// ── Private formatting helper ─────────────────────────────────────────────────

/** Formats a [Double] to 4 decimal places; returns `"—"` for NaN or infinite values. */
private fun fmtD(value: Double): String = when {
    value.isNaN() || value.isInfinite() -> "\u2014"
    else -> "%.4f".format(value)
}
