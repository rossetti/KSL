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

import ksl.observers.ResponseTrace
import ksl.utilities.io.plotting.ScatterPlot
import ksl.utilities.io.plotting.StateVariablePlot
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.ReportBuilder
import ksl.utilities.io.report.dsl.report
import ksl.utilities.statistic.Statistic

/**
 * DSL extension functions on [ReportBuilder] for reporting [ResponseTrace] data.
 *
 * The top-level entry point is [responseTrace], which auto-selects the correct
 * visualisation based on [ResponseTrace.isTimeWeighted]:
 *
 *  - **TWResponse** (step-function semantics) → [stateVariableTrace]: one
 *    [StateVariablePlot] per replication followed by a time-weighted statistics
 *    property table.
 *  - **Response** (observation semantics) → [observationTrace]: one
 *    [ScatterPlot] (time on x, value on y) per replication followed by a
 *    descriptive statistics property table.
 *
 * Statistics reported for the **observation** case are limited to count,
 * average, min, and max.  Half-width, confidence interval, and standard
 * deviation are deliberately excluded: within-replication observations are
 * autocorrelated and the standard variance estimator is biased, making those
 * inferential quantities misleading.
 *
 * The default for [repNums] in every function is `trace.replicationNumbers.take(1)`,
 * i.e. only the first recorded replication.  Pass an explicit list to include
 * additional replications.
 *
 * Zero-code usage:
 * ```kotlin
 * val myTrace = ResponseTrace(myResponse)
 * myModel.simulate()
 * myTrace.toReport().showInBrowser()
 * ```
 *
 * Selected replications with a time window:
 * ```kotlin
 * myTrace.toReport(repNums = listOf(1, 3, 5), startTime = 20.0).writeMarkdown()
 * ```
 */

// ── stateVariableTrace ────────────────────────────────────────────────────────

/**
 * Appends one section per replication in [repNums] for a time-weighted
 * ([ksl.modeling.variable.TWResponse]) [ResponseTrace].
 *
 * Each replication section contains:
 * 1. A [StateVariablePlot] (step function with observation markers) covering
 *    the time window [[startTime], [endTime]].
 * 2. A Property/Value table of the [ksl.utilities.statistic.TimeWeightedStatistic]
 *    computed from the windowed trace data: count, time-weighted average, min,
 *    max, sum of weights (= total observation time), weighted sum, unweighted
 *    sum, and missing count.
 *
 * @param trace      the [ResponseTrace] to report; must be a TWResponse trace
 * @param repNums    replication numbers to include; defaults to the first recorded replication
 * @param startTime  lower bound of the time window; defaults to 0.0
 * @param endTime    upper bound of the time window; defaults to [Double.MAX_VALUE]
 */
fun ReportBuilder.stateVariableTrace(
    trace: ResponseTrace,
    repNums: List<Int> = trace.replicationNumbers.take(1),
    startTime: Double = 0.0,
    endTime: Double = Double.MAX_VALUE
) {
    section(trace.name) {
        for (myRepNum in repNums) {
            section("Replication $myRepNum") {
                val myDataMap = trace.traceDataMap(myRepNum, startTime, endTime)
                val myTimes  = myDataMap["times"]  ?: doubleArrayOf()
                val myValues = myDataMap["values"] ?: doubleArrayOf()

                val myPlot = StateVariablePlot(myValues, myTimes, trace.name)
                myPlot.title = "Sample path for ${trace.name} \u2014 Rep $myRepNum"
                plot(myPlot)

                val myTWS = myPlot.timeWeightedStatistic
                dataTable(
                    headers = listOf("Property", "Value"),
                    rows    = listOf(
                        listOf("Count",             myTWS.count.toInt().toString()),
                        listOf("Time-Weighted Avg", fmtDouble(myTWS.weightedAverage)),
                        listOf("Min",               fmtDouble(myTWS.min)),
                        listOf("Max",               fmtDouble(myTWS.max)),
                        listOf("Sum of Weights",    fmtDouble(myTWS.sumOfWeights)),
                        listOf("Weighted Sum",      fmtDouble(myTWS.weightedSum)),
                        listOf("Unweighted Sum",    fmtDouble(myTWS.unWeightedSum)),
                        listOf("Missing",           myTWS.numberMissing.toInt().toString())
                    ),
                    caption = "Time-Weighted Statistics"
                )
            }
        }
    }
}

// ── observationTrace ──────────────────────────────────────────────────────────

/**
 * Appends one section per replication in [repNums] for an observation-based
 * ([ksl.modeling.variable.Response]) [ResponseTrace].
 *
 * Each replication section contains:
 * 1. A [ScatterPlot] with simulation time on the x-axis and observed value on
 *    the y-axis, covering the time window [[startTime], [endTime]].
 * 2. A Property/Value table of descriptive statistics: count, average, min,
 *    and max.
 *
 * Half-width, confidence interval, and standard deviation are excluded.
 * Within-replication observations are autocorrelated; the standard variance
 * estimator is biased under autocorrelation and the derived inferential
 * quantities would be misleading.
 *
 * @param trace      the [ResponseTrace] to report; must be an observation-based trace
 * @param repNums    replication numbers to include; defaults to the first recorded replication
 * @param startTime  lower bound of the time window; defaults to 0.0
 * @param endTime    upper bound of the time window; defaults to [Double.MAX_VALUE]
 */
fun ReportBuilder.observationTrace(
    trace: ResponseTrace,
    repNums: List<Int> = trace.replicationNumbers.take(1),
    startTime: Double = 0.0,
    endTime: Double = Double.MAX_VALUE
) {
    section(trace.name) {
        for (myRepNum in repNums) {
            section("Replication $myRepNum") {
                val myDataMap = trace.traceDataMap(myRepNum, startTime, endTime)
                val myTimes  = myDataMap["times"]  ?: doubleArrayOf()
                val myValues = myDataMap["values"] ?: doubleArrayOf()

                val myPlot = ScatterPlot(myTimes, myValues)
                myPlot.title  = "Observations for ${trace.name} \u2014 Rep $myRepNum"
                myPlot.xLabel = "Time"
                myPlot.yLabel = "Value"
                plot(myPlot)

                if (myValues.isNotEmpty()) {
                    val myStat = Statistic(trace.name, myValues)
                    dataTable(
                        headers = listOf("Property", "Value"),
                        rows    = listOf(
                            listOf("Count",   myStat.count.toInt().toString()),
                            listOf("Average", fmtDouble(myStat.average)),
                            listOf("Min",     fmtDouble(myStat.min)),
                            listOf("Max",     fmtDouble(myStat.max))
                        ),
                        caption = "Descriptive Statistics"
                    )
                }
            }
        }
    }
}

// ── responseTrace ─────────────────────────────────────────────────────────────

/**
 * Appends trace sections for a [ResponseTrace], auto-selecting the correct
 * visualisation based on [ResponseTrace.isTimeWeighted].
 *
 * - `true`  → [stateVariableTrace]: [StateVariablePlot] + time-weighted statistics
 * - `false` → [observationTrace]: [ScatterPlot] + descriptive statistics
 *
 * @param trace      the [ResponseTrace] to report
 * @param repNums    replication numbers to include; defaults to the first recorded replication
 * @param startTime  lower bound of the time window; defaults to 0.0
 * @param endTime    upper bound of the time window; defaults to [Double.MAX_VALUE]
 */
fun ReportBuilder.responseTrace(
    trace: ResponseTrace,
    repNums: List<Int> = trace.replicationNumbers.take(1),
    startTime: Double = 0.0,
    endTime: Double = Double.MAX_VALUE
) {
    if (trace.isTimeWeighted) {
        stateVariableTrace(trace, repNums, startTime, endTime)
    } else {
        observationTrace(trace, repNums, startTime, endTime)
    }
}

// ── ResponseTrace.toReport ────────────────────────────────────────────────────

/**
 * Builds a [ReportNode.Document] for this [ResponseTrace].
 *
 * The default block calls [responseTrace], which auto-selects between
 * [stateVariableTrace] and [observationTrace] based on [ResponseTrace.isTimeWeighted].
 *
 * Zero-code usage:
 * ```kotlin
 * myTrace.toReport().showInBrowser()
 * myTrace.toReport(repNums = listOf(1, 3, 5), startTime = 20.0).writeMarkdown()
 * ```
 *
 * Custom block replaces the default:
 * ```kotlin
 * myTrace.toReport("Queue Analysis") {
 *     paragraph("Drive-through pharmacy — system time trace.")
 *     responseTrace(myTrace, repNums = listOf(1, 2))
 * }
 * ```
 *
 * @param title      document title; defaults to [ResponseTrace.name]
 * @param repNums    replication numbers to include; defaults to the first recorded replication
 * @param startTime  lower bound of the time window; defaults to 0.0
 * @param endTime    upper bound of the time window; defaults to [Double.MAX_VALUE]
 * @param block      optional DSL block; replaces the default content when provided
 */
fun ResponseTrace.toReport(
    title: String = name,
    repNums: List<Int> = replicationNumbers.take(1),
    startTime: Double = 0.0,
    endTime: Double = Double.MAX_VALUE,
    block: ReportBuilder.() -> Unit = {
        responseTrace(this@toReport, repNums, startTime, endTime)
    }
): ReportNode.Document = report(title, block)
