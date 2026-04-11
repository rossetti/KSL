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

import ksl.modeling.variable.TimeSeriesResponse
import ksl.simulation.Model
import ksl.simulation.SimulationReporter
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.ReportBuilder
import ksl.utilities.io.report.dsl.report
import ksl.utilities.statistic.IntegerFrequency

/**
 * DSL extension functions on [ReportBuilder] for rendering [SimulationReporter] and
 * [Model] results.
 *
 * These extensions assemble complete simulation output reports, including across-
 * replication statistics, histograms, integer frequency distributions, and (when a
 * [Model] reference is supplied) time-series period statistics.
 *
 * None of the underlying simulation classes are modified; all bridging is done
 * via these non-intrusive extension functions.
 */

/**
 * Appends a section containing simulation metadata (model name, experiment name,
 * replications, run length, warm-up length) and the across-replication statistics
 * half-width summary table.
 *
 * Usage:
 * ```kotlin
 * val doc = report("Quick Summary") {
 *     simulationSummary(model.simulationReporter, model)
 * }
 * ```
 *
 * @param reporter        the [SimulationReporter] whose across-replication statistics
 *                        will be tabulated
 * @param model           the [Model] providing experiment metadata; when `null` metadata
 *                        is omitted and only the statistics table is produced
 * @param confidenceLevel confidence level for half-width and CI columns; must be in (0, 1)
 */
fun ReportBuilder.simulationSummary(
    reporter: SimulationReporter,
    model: Model? = null,
    confidenceLevel: Double = 0.95
) {
    section("Simulation Summary") {
        if (model != null) {
            dataTable(
                headers = listOf("Property", "Value"),
                rows = listOf(
                    listOf("Simulation Name",    model.simulationName),
                    listOf("Model Name",         model.name),
                    listOf("Experiment Name",    model.experimentName),
                    listOf("Replications",       model.numberOfReplications.toString()),
                    listOf("Run Length",         model.lengthOfReplication.toString()),
                    listOf("Warm-Up Length",     model.lengthOfReplicationWarmUp.toString())
                ),
                caption = "Experiment Configuration"
            )
        }
        val myStats = reporter.acrossReplicationStatisticsList()
        if (myStats.isNotEmpty()) {
            statTable(
                stats = myStats,
                caption = "Across-Replication Statistics",
                confidenceLevel = confidenceLevel
            )
        } else {
            paragraph("No across-replication statistics available.")
        }
    }
}

/**
 * Appends a section containing one sub-section per histogram collected by the model,
 * each rendered by [histogram].
 *
 * Does nothing if [reporter.histograms][SimulationReporter.histograms] is empty.
 *
 * @param reporter        source of histogram data
 * @param confidenceLevel confidence level for the StatTable in each histogram section
 */
fun ReportBuilder.simulationHistograms(
    reporter: SimulationReporter,
    confidenceLevel: Double = 0.95
) {
    if (reporter.histograms.isEmpty()) return
    section("Histograms") {
        for (myHr in reporter.histograms) {
            histogram(myHr.histogram, caption = myHr.name, confidenceLevel = confidenceLevel)
        }
    }
}

/**
 * Appends a section containing one sub-section per integer-frequency response collected
 * by the model, each rendered by [integerFrequency].
 *
 * Does nothing if [reporter.frequencies][SimulationReporter.frequencies] is empty.
 *
 * @param reporter source of frequency data
 */
fun ReportBuilder.simulationFrequencies(reporter: SimulationReporter) {
    if (reporter.frequencies.isEmpty()) return
    section("Frequencies") {
        for (myFr in reporter.frequencies) {
            // frequencyResponse is declared as IntegerFrequencyIfc but the concrete type
            // is always IntegerFrequency (set in IntegerFrequencyResponse). Cast is safe.
            val myFreq = myFr.frequencyResponse as? IntegerFrequency
            if (myFreq != null) {
                integerFrequency(myFreq, caption = myFr.name)
            }
        }
    }
}

/**
 * Appends a section containing one sub-section per [TimeSeriesResponse] found in the
 * [model], each rendered by [timeSeriesResponse].
 *
 * Does nothing if the model contains no [TimeSeriesResponse] instances.
 *
 * @param model           the model to query for [TimeSeriesResponse] elements
 * @param confidenceLevel confidence level for per-period CI columns
 */
fun ReportBuilder.simulationTimeSeries(
    model: Model,
    confidenceLevel: Double = 0.95
) {
    val myTsList = model.timeSeriesResponses.filterIsInstance<TimeSeriesResponse>()
    if (myTsList.isEmpty()) return
    section("Time-Series Responses") {
        for (myTs in myTsList) {
            timeSeriesResponse(myTs, confidenceLevel = confidenceLevel)
        }
    }
}

/**
 * Appends a complete simulation results report as a sequence of sections inside the
 * current builder scope, in the following order:
 *
 * 1. **Simulation Summary** — experiment metadata (when [model] is non-null) and
 *    across-replication statistics half-width summary table
 * 2. **Histograms** — one section per histogram (omitted when none exist)
 * 3. **Frequencies** — one section per integer-frequency response (omitted when none)
 * 4. **Time-Series Responses** — one section per [TimeSeriesResponse] (omitted when
 *    [model] is `null` or no time-series responses exist)
 *
 * Usage:
 * ```kotlin
 * model.simulate()
 * val doc = report("Drive-Through Pharmacy") {
 *     simulationResults(model.simulationReporter, model)
 * }
 * doc.showInBrowser()
 * doc.writeMarkdown()
 * ```
 *
 * @param reporter        the simulation reporter providing statistics, histograms, and
 *                        frequency distributions
 * @param model           optional model reference; required for metadata and time-series
 *                        sections; pass `null` to skip both
 * @param confidenceLevel confidence level for all statistical tables; must be in (0, 1)
 */
fun ReportBuilder.simulationResults(
    reporter: SimulationReporter,
    model: Model? = null,
    confidenceLevel: Double = 0.95
) {
    simulationSummary(reporter, model, confidenceLevel)
    simulationHistograms(reporter, confidenceLevel)
    simulationFrequencies(reporter)
    if (model != null) {
        simulationTimeSeries(model, confidenceLevel)
    }
}

/**
 * Convenience extension on [Model] that builds a [ReportNode.Document] in a single
 * call, automatically wiring the model's [SimulationReporter] and time-series responses
 * into a complete results report.
 *
 * Usage:
 * ```kotlin
 * model.simulate()
 * val doc = model.buildReport("Drive-Through Pharmacy") {
 *     // additional content (optional)
 *     paragraph("Custom notes here.")
 * }
 * doc.showInBrowser()
 * ```
 *
 * @param title  document title used as the top-level heading; defaults to
 *               [model.simulationName][Model.simulationName]
 * @param block  optional DSL block executed after [simulationResults]; use it to
 *               append custom sections or content
 * @return the assembled [ReportNode.Document]
 */
fun Model.buildReport(
    title: String = simulationName,
    block: ReportBuilder.() -> Unit = {}
): ReportNode.Document = report(title) {
    simulationResults(simulationReporter, this@buildReport)
    block()
}
