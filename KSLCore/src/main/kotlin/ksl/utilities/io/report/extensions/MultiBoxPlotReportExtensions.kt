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

import ksl.controls.experiments.DesignedExperimentIfc
import ksl.controls.experiments.ScenarioRunner
import ksl.utilities.io.plotting.MultiBoxPlot
import ksl.utilities.io.report.dsl.ReportBuilder
import ksl.utilities.statistic.MultipleComparisonAnalyzer
import ksl.utilities.statistic.Statistic

/**
 * DSL extension functions on [ReportBuilder] for rendering [MultiBoxPlot] instances
 * within the KSL reporting framework.
 *
 * **Three-layer architecture:**
 *
 * - **General primitive** — [multiBoxPlot]: accepts any `Map<String, DoubleArray>` and
 *   renders a [MultiBoxPlot] via [Statistic.boxPlotSummaries]; usable for any data
 *   that can be expressed as label → replication observations
 *
 * - **Domain wrappers** — [multipleComparisonBoxPlot], [designedExperimentBoxPlot],
 *   [scenarioRunnerBoxPlot]: call the respective `observationsAsMap` method on the
 *   domain object and delegate to [multiBoxPlot]
 *
 * **Data extraction is on the domain classes** (not here):
 * - [MultipleComparisonAnalyzer.observationsAsMap] — alternative name → observations
 * - [DesignedExperimentIfc.observationsAsMap] — `"Point N"` → observations for one response
 * - [ScenarioRunner.observationsAsMap] — scenario name → observations for one response
 *
 * **Composability example — custom document with a targeted box plot:**
 * ```kotlin
 * report("Queue Study — Service Time") {
 *     multiBoxPlot(
 *         dataMap = mapOf("Config A" to obsA, "Config B" to obsB),
 *         caption = "Service Time Distributions"
 *     )
 * }
 * ```
 *
 * **Domain-specific example:**
 * ```kotlin
 * report("Throughput Study") {
 *     multipleComparisonBoxPlot(mca)
 * }
 *
 * report("Inventory Design") {
 *     designedExperimentBoxPlot(de, responseName = "Total Cost")
 * }
 *
 * report("Server Count Scenarios") {
 *     scenarioRunnerBoxPlot(runner, responseName = "System Time")
 * }
 * ```
 */

// ── General primitive ─────────────────────────────────────────────────────────

/**
 * Appends a [MultiBoxPlot] built from pre-assembled observation data.
 *
 * Each entry in [dataMap] becomes one box on the plot: the key is the box
 * label and the value is the array of per-replication observations.
 * [Statistic.boxPlotSummaries] is called internally to convert the raw arrays
 * into [ksl.utilities.statistic.BoxPlotSummary] objects required by [MultiBoxPlot].
 *
 * Nothing is emitted when [dataMap] is empty or when every array in [dataMap]
 * is empty.
 *
 * This is the foundational building block. Use it directly when working with
 * data that does not come from [MultipleComparisonAnalyzer], [DesignedExperimentIfc],
 * or [ScenarioRunner], or when custom box labels are needed.
 *
 * The constructed [MultiBoxPlot] inherits `xLabel` / `yLabel` from
 * [ksl.utilities.io.plotting.BasePlot] whose defaults are `"x"` / `"y"`.  The
 * caller can override either via [xAxisLabel] / [yAxisLabel]; when those
 * are `null` (the default), this primitive applies the generic placeholders
 * `"Label"` and `"Value"` so the rendered figure carries readable axis text
 * even without a domain-aware caller.  Domain wrappers
 * ([multipleComparisonBoxPlot], [designedExperimentBoxPlot],
 * [scenarioRunnerBoxPlot]) supply context-appropriate defaults of their own.
 *
 * @param dataMap     label → per-replication observations; must not be empty
 *                    for a plot to be emitted
 * @param caption     optional plot caption shown below the figure
 * @param xAxisLabel  optional override for the x-axis label.  Blank / `null`
 *                    falls back to `"Label"`.
 * @param yAxisLabel  optional override for the y-axis label.  Blank / `null`
 *                    falls back to `"Value"`.
 */
fun ReportBuilder.multiBoxPlot(
    dataMap: Map<String, DoubleArray>,
    caption: String? = null,
    xAxisLabel: String? = null,
    yAxisLabel: String? = null
) {
    val myBoxMap = Statistic.boxPlotSummaries(dataMap)
    if (myBoxMap.isNotEmpty()) {
        val plotInstance = MultiBoxPlot(myBoxMap).apply {
            xLabel = xAxisLabel?.trim()?.takeIf { it.isNotEmpty() } ?: "Label"
            yLabel = yAxisLabel?.trim()?.takeIf { it.isNotEmpty() } ?: "Value"
        }
        plot(plotInstance, caption)
    }
}

// ── Domain-specific wrappers ──────────────────────────────────────────────────

/**
 * Appends a [MultiBoxPlot] comparing the per-replication distributions of all
 * alternatives in [mca].
 *
 * Each box represents one alternative; labels are the alternative names from
 * [MultipleComparisonAnalyzer.dataNames]. The data is sourced from
 * [MultipleComparisonAnalyzer.observationsAsMap], which returns a shallow copy
 * of the internal data map in a single pass.
 *
 * The caption defaults to `"Response Distributions — <mca.name>"`.
 *
 * Axis labels default to `"Alternative"` (x) and the MCA's response name (y);
 * a blank MCA name falls the y-axis back to `"Value"`.  Either can be
 * overridden via [xAxisLabel] / [yAxisLabel].
 *
 * @param mca         the analyzer whose alternatives are to be plotted
 * @param caption     optional plot caption; defaults to `"Response Distributions — <mca.name>"`
 * @param xAxisLabel  optional override for the x-axis label
 * @param yAxisLabel  optional override for the y-axis label
 */
fun ReportBuilder.multipleComparisonBoxPlot(
    mca: MultipleComparisonAnalyzer,
    caption: String? = null,
    xAxisLabel: String? = null,
    yAxisLabel: String? = null
) {
    multiBoxPlot(
        dataMap = mca.observationsAsMap,
        caption = caption ?: "Response Distributions \u2014 ${mca.name}",
        xAxisLabel = xAxisLabel ?: "Alternative",
        yAxisLabel = yAxisLabel ?: mca.name.ifBlank { "Value" }
    )
}

/**
 * Appends a [MultiBoxPlot] comparing per-replication distributions across
 * executed design points for a single [responseName] in [de].
 *
 * Each box represents one executed design point, labelled `"Point N"` in
 * execution order, sourced from [DesignedExperimentIfc.observationsAsMap].
 * Design points for which [responseName] produced no observations are omitted.
 * Nothing is emitted when no design points have been executed.
 *
 * Axis labels default to `"Design Point"` (x) and [responseName] (y); override
 * either via [xAxisLabel] / [yAxisLabel].
 *
 * @param de           the designed experiment
 * @param responseName the response to visualise; should appear in
 *                     [DesignedExperimentIfc.responseNames]
 * @param caption      optional plot caption; defaults to
 *                     `"Distributions by Design Point — <responseName>"`
 * @param xAxisLabel   optional override for the x-axis label
 * @param yAxisLabel   optional override for the y-axis label
 */
fun ReportBuilder.designedExperimentBoxPlot(
    de: DesignedExperimentIfc,
    responseName: String,
    caption: String? = null,
    xAxisLabel: String? = null,
    yAxisLabel: String? = null
) {
    multiBoxPlot(
        dataMap = de.observationsAsMap(responseName),
        caption = caption ?: "Distributions by Design Point \u2014 $responseName",
        xAxisLabel = xAxisLabel ?: "Design Point",
        yAxisLabel = yAxisLabel ?: responseName
    )
}

/**
 * Appends a [MultiBoxPlot] comparing per-replication distributions of a single
 * [responseName] across the executed scenarios in [runner].
 *
 * Each box represents one scenario, labelled by [ksl.controls.experiments.Scenario.name],
 * sourced from [ScenarioRunner.observationsAsMap]. Scenarios not yet executed or
 * that produced no observations for [responseName] are omitted.
 * Nothing is emitted when fewer than one scenario produced observations.
 *
 * Axis labels default to `"Scenario"` (x) and [responseName] (y); override
 * either via [xAxisLabel] / [yAxisLabel].
 *
 * @param runner       the scenario runner
 * @param responseName the response to visualise
 * @param caption      optional plot caption; defaults to
 *                     `"Cross-Scenario Distributions — <responseName>"`
 * @param xAxisLabel   optional override for the x-axis label
 * @param yAxisLabel   optional override for the y-axis label
 */
fun ReportBuilder.scenarioRunnerBoxPlot(
    runner: ScenarioRunner,
    responseName: String,
    caption: String? = null,
    xAxisLabel: String? = null,
    yAxisLabel: String? = null
) {
    multiBoxPlot(
        dataMap = runner.observationsAsMap(responseName),
        caption = caption ?: "Cross-Scenario Distributions \u2014 $responseName",
        xAxisLabel = xAxisLabel ?: "Scenario",
        yAxisLabel = yAxisLabel ?: responseName
    )
}
