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

import ksl.controls.experiments.DesignedExperiment
import ksl.controls.experiments.LinearModel
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.ReportBuilder
import ksl.utilities.io.report.dsl.report
import ksl.utilities.statistic.Statistic

/**
 * DSL extension functions on [ReportBuilder] for rendering [DesignedExperiment]
 * within the KSL reporting framework.
 *
 * **Separation of concerns — granular functions (composable building blocks):**
 * - [designedExperiment] — composite report: overview, design structure (via
 *   [experimentalDesign]), model responses list, execution summary with one row per
 *   design point, per-response `StatTable` (one section per response using
 *   across-replication observations from each design point's [ksl.controls.experiments.SimulationRun]),
 *   and optional full per-design-point [simulationRun] detail sections
 *
 * **Zero-code entry point:**
 * - [DesignedExperiment.toReport] — full experiment document
 *
 * **Composability example — design, execution, and regression narrative:**
 * ```kotlin
 * report("R-Q Inventory — Response Surface Study") {
 *     designedExperiment(de, confidenceLevel = 0.95, coded = true)
 *     paragraph("Fitted response surface model: see regression analysis output.")
 * }
 * ```
 *
 * **Note on factor-to-control mapping:** the mapping between each [ksl.controls.experiments.Factor]
 * and the model control or RV parameter name is stored as a private field in
 * [DesignedExperiment] and is not accessible via the public API. The execution
 * summary table therefore shows the control/parameter names taken from each
 * [ksl.controls.experiments.SimulationRun.inputs] map rather than the factor names.
 */

// ── DSL Function: Designed Experiment (composite) ────────────────────────────

/**
 * Appends a self-contained section reporting the full structure and results of [de].
 *
 * **Produces (inside a section titled `caption` or `"Designed Experiment"`):**
 * 1. **Overview paragraph** — experiment name, design type, factor count, design
 *    point count, response count, number of runs executed, and display scale
 * 2. **Design Structure** sub-section — full [experimentalDesign] output (overview,
 *    optional CCD configuration, factor summary, design point matrix)
 * 3. **Model Responses** sub-section — `DataTable` listing all response and counter
 *    names collected by the model
 * 4. Paragraph notice when no design points have been executed
 * 5. **Execution Summary** sub-section (when runs exist) — `DataTable` (Point |
 *    Experiment Name | Reps | *control columns* | Status) with one row per executed
 *    design point; control columns are derived from
 *    [ksl.controls.experiments.SimulationRun.inputs] and vary by experiment
 * 6. **Response Statistics** sub-section (when runs exist) — one sub-section per
 *    response name; each sub-section contains a `StatTable` where each row represents
 *    one design point and reports the across-replication statistics (count, mean,
 *    std dev, half-width, CI bounds, min, max) at [confidenceLevel]; observations
 *    are taken from [ksl.controls.experiments.SimulationRun.results]
 * 7. **Design Point Details** sub-section (when [showDetails] is `true`) — one
 *    [simulationRun] section per executed design point, showing the complete run
 *    report including inputs, run parameters, and response statistics
 *
 * @param de              the [DesignedExperiment] to report
 * @param confidenceLevel confidence level for response statistics; defaults to 0.95
 * @param coded           `false` (default) = original measurement scale for the
 *                        design point matrix; `true` = coded (−1/+1) scale
 * @param showDetails     `true` includes per-design-point [simulationRun] sections;
 *                        defaults to `false` because these can be verbose for large
 *                        designs; useful for audit trails and small experiments
 * @param caption         optional section title; defaults to `"Designed Experiment"`
 */
fun ReportBuilder.designedExperiment(
    de: DesignedExperiment,
    confidenceLevel: Double = 0.95,
    coded: Boolean = false,
    showDetails: Boolean = false,
    caption: String? = null
) {
    section(caption ?: "Designed Experiment") {
        val myHasRuns      = de.numSimulationRuns > 0
        val myScaleLabel   = if (coded) "Coded" else "Original"
        val myDesignPoints = de.design.designPoints()
        val myNumPoints    = myDesignPoints.size

        // ── 1. Overview paragraph ──────────────────────────────────────────────
        paragraph(
            "Experiment: ${de.name}  |  " +
            "Design: ${de.design.javaClass.simpleName}  |  " +
            "Factors: ${de.design.numFactors}  |  " +
            "Design Points: $myNumPoints  |  " +
            "Responses: ${de.responseNames.size}  |  " +
            "Runs Executed: ${de.numSimulationRuns}  |  " +
            "Scale: $myScaleLabel"
        )

        // ── 2. Design structure (reuse existing extension) ─────────────────────
        experimentalDesign(de.design, coded, caption = "Design Structure")

        // ── 3. Model responses ─────────────────────────────────────────────────
        section("Model Responses") {
            dataTable(
                headers = listOf("Response Name"),
                rows    = de.responseNames.map { listOf(it) },
                caption = "Responses Collected (${de.responseNames.size})"
            )
        }

        // ── Early exit when not yet run ────────────────────────────────────────
        if (!myHasRuns) {
            paragraph(
                "No design points have been executed. Call `simulateAll()` on the " +
                "DesignedExperiment to run all design points before generating results."
            )
            return@section
        }

        // ── 4. Execution summary ───────────────────────────────────────────────
        section("Execution Summary") {
            // Collect all distinct control/parameter names from the executed runs
            val myCtrlNames = de.simulationRuns
                .flatMap { it.inputs.keys }
                .distinct()
                .sorted()

            val myHeaders = buildList {
                add("Point")
                add("Experiment Name")
                add("Reps")
                addAll(myCtrlNames)
                add("Status")
            }
            val myRows = de.simulationRuns.mapIndexed { idx, myRun ->
                buildList {
                    add((idx + 1).toString())
                    add(myRun.experimentRunParameters.experimentName)
                    add(myRun.numberOfReplications.toString())
                    for (ctrl in myCtrlNames) {
                        add(myRun.inputs[ctrl]?.let { "%.6g".format(it) } ?: "\u2014")
                    }
                    add(if (!myRun.hasError) "OK" else "Error \u26a0")
                }
            }
            dataTable(
                headers = myHeaders,
                rows    = myRows,
                caption = "Design Point Execution (${de.numSimulationRuns} points executed)"
            )
        }

        // ── 5. Per-response statistics ─────────────────────────────────────────
        section("Response Statistics") {
            paragraph(
                "Each row below represents one executed design point. Statistics are " +
                "computed across the replications at that design point at the " +
                "${(confidenceLevel * 100).toInt()}% confidence level."
            )
            for (myResponseName in de.responseNames) {
                // Build a Statistic per design point using replication observations
                val myStats = de.simulationRuns.mapIndexed { idx, myRun ->
                    val myObs = myRun.replicationObservations(myResponseName)
                    if (myObs != null && myObs.isNotEmpty()) {
                        Statistic("Point ${idx + 1}", myObs)
                    } else null
                }.filterNotNull()

                if (myStats.isNotEmpty()) {
                    section(myResponseName) {
                        statTable(
                            stats           = myStats,
                            caption         = "Across-Replication Statistics: $myResponseName",
                            confidenceLevel = confidenceLevel
                        )
                    }
                }
            }
        }

        // ── 6. Per-design-point details (opt-in) ──────────────────────────────
        if (showDetails) {
            section("Design Point Details") {
                paragraph(
                    "Full simulation run detail for each executed design point. " +
                    "This section includes run identity, run parameters, inputs, " +
                    "and response statistics for every design point."
                )
                for ((idx, myRun) in de.simulationRuns.withIndex()) {
                    simulationRun(
                        run             = myRun,
                        confidenceLevel = confidenceLevel,
                        caption         = "Design Point ${idx + 1}: ${myRun.experimentRunParameters.experimentName}"
                    )
                }
            }
        }
    }
}

// ── DSL Function: Designed Experiment Regression (bridge) ────────────────────

/**
 * Appends a self-contained regression-analysis section for a single response from [de].
 *
 * This bridge function fits an OLS model to the across-replication mean responses at
 * each design point and delegates all rendering to the existing [regressionSummary],
 * [regressionParameters], and (optionally) [regressionDiagnostics] extension functions.
 *
 * **Produces (inside a section titled `caption` or `"Regression Analysis — <responseName>"`):**
 * 1. **Regression Setup** `DataTable` — response name, factor count, term count (excluding
 *    intercept), intercept flag, and display scale
 * 2. **ANOVA and Model Fit** — via [regressionSummary]: ANOVA table, R², adjusted R²,
 *    RMSE, and overall F-test p-value
 * 3. **Coefficients** — via [regressionParameters]: coefficient estimates, standard errors,
 *    t-statistics, p-values, and confidence intervals with significance codes
 * 4. **Diagnostics** (when [showDiagnosticPlots] is `true`) — via [regressionDiagnostics]:
 *    residual summary, normal probability plot, residuals-vs-fitted plot, and
 *    residuals-vs-order plot
 *
 * **Prerequisite:** [de] must have been executed via `de.simulateAll()` before calling
 * this function; otherwise [DesignedExperiment.regressionResults] will have no data.
 *
 * @param de                  the [DesignedExperiment] whose results supply the regression data
 * @param responseName        the name of the response to fit; must appear in [de.responseNames]
 * @param linearModel         the model specification (factors, interaction terms, intercept flag)
 * @param confidenceLevel     confidence level for coefficient intervals; defaults to 0.95
 * @param coded               `true` (default) = coded (−1/+1) scale; `false` = original scale
 * @param showDiagnosticPlots `true` (default) = include residual diagnostic plots; `false` =
 *                            summary and coefficients only (useful for large documents)
 * @param caption             optional section title; defaults to
 *                            `"Regression Analysis — <responseName>"`
 */
fun ReportBuilder.designedExperimentRegression(
    de: DesignedExperiment,
    responseName: String,
    linearModel: LinearModel,
    confidenceLevel: Double = 0.95,
    coded: Boolean = true,
    showDiagnosticPlots: Boolean = true,
    caption: String? = null
) {
    section(caption ?: "Regression Analysis \u2014 $responseName") {
        // ── 1. Identification table ────────────────────────────────────────────
        dataTable(
            headers = listOf("Property", "Value"),
            rows    = listOf(
                listOf("Response",   responseName),
                listOf("Factors",    linearModel.mainEffects.size.toString()),
                listOf("Terms",      linearModel.termsAsMap.size.toString()),
                listOf("Intercept",  linearModel.intercept.toString()),
                listOf("Scale",      if (coded) "Coded (\u22121/+1)" else "Original")
            ),
            caption = "Regression Setup"
        )

        // ── 2–4. Fit, coefficients, diagnostics ───────────────────────────────
        val myResults = de.regressionResults(responseName, linearModel, coded)
        regressionSummary(myResults,    confidenceLevel = confidenceLevel)
        regressionParameters(myResults, confidenceLevel = confidenceLevel)
        if (showDiagnosticPlots) {
            regressionDiagnostics(myResults)
        }
    }
}

// ── toReport() — zero-code entry point ───────────────────────────────────────

/**
 * Builds a [ReportNode.Document] containing a full designed experiment report
 * via [designedExperiment].
 *
 * Zero-code path:
 * ```kotlin
 * de.simulateAll(numRepsPerDesignPoint = 10)
 * de.toReport("R-Q Inventory Experiment").showInBrowser()
 * ```
 *
 * Coded scale with per-point details:
 * ```kotlin
 * de.toReport("Response Surface Study", coded = true, showDetails = true)
 *     .showInBrowser()
 * ```
 *
 * Custom block:
 * ```kotlin
 * de.toReport("Factorial Study") {
 *     designedExperiment(this@toReport, coded = true)
 *     paragraph("Regression analysis follows in the next section.")
 * }
 * ```
 *
 * @param title           document title; defaults to the experiment name
 * @param confidenceLevel confidence level for response statistics; defaults to 0.95
 * @param coded           `false` (default) = original scale; `true` = coded scale
 *                        for the design point matrix
 * @param showDetails     `true` includes per-design-point [simulationRun] sections;
 *                        defaults to `false`
 * @param block           optional DSL block; replaces the default when provided
 * @return the assembled [ReportNode.Document]
 */
fun DesignedExperiment.toReport(
    title: String = "Designed Experiment \u2014 $name",
    confidenceLevel: Double = 0.95,
    coded: Boolean = false,
    showDetails: Boolean = false,
    block: ReportBuilder.() -> Unit = {
        designedExperiment(this@toReport, confidenceLevel, coded, showDetails)
    }
): ReportNode.Document = report(title, block)
