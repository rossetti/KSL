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

import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.ReportBuilder
import ksl.utilities.io.report.dsl.report
import ksl.utilities.statistic.StateFrequency

/**
 * DSL extension functions on [ReportBuilder] for rendering [StateFrequency] instances.
 *
 * [StateFrequency] delegates numeric statistics to its internal [ksl.utilities.statistic.IntegerFrequency],
 * accessible via [StateFrequency.statistic]. The statistics on the observed state numbers are therefore
 * available for a [ksl.utilities.io.report.ast.ReportNode.StatPropertyTable] via that explicit bridge,
 * mirroring the pattern used for [IntegerFrequency] in [FrequencyReportExtensions].
 *
 * In addition to the per-state frequency table, [StateFrequency] tracks transitions between
 * states. The transition count matrix and transition proportion matrix are each available as
 * optional [ksl.utilities.io.report.ast.ReportNode.DataTable] sections, controlled
 * independently by [showTransitions] and [showTransitionProportions].
 */

/**
 * Appends a self-contained section that reports the state frequency distribution, summary
 * statistics on the observed state numbers, optional transition matrices, and a frequency bar plot.
 *
 * **Produces (inside a section titled `caption` or [freq.name][StateFrequency.name]):**
 * 1. A [ksl.utilities.io.report.ast.ReportNode.Paragraph] summarising total observations
 *    and number of states.
 * 2. A `DataTable` ("State Frequency Table") with columns:
 *    State | Count | Cum Count | % | Cum %
 * 3. A [ksl.utilities.io.report.ast.ReportNode.StatPropertyTable] ("Statistics on Observed States")
 *    built from [StateFrequency.statistic] — the explicit bridge to numeric statistics on
 *    state numbers. Omitted when `showStatistics` is `false`.
 * 4. A `DataTable` ("Transition Count Matrix") — square matrix of state-to-state transition
 *    counts, rows = from-state, columns = to-state. Omitted when `showTransitions` is `false`.
 * 5. A `DataTable` ("Transition Proportion Matrix") — same structure as the count matrix but
 *    with row-normalised proportions (each row sums to 1.0 for visited states, 0.0 for
 *    unvisited states). Omitted when `showTransitionProportions` is `false`.
 * 6. A [ksl.utilities.io.report.ast.ReportNode.PlotNode] for the state frequency bar chart.
 *    Omitted when `showPlot` is `false`.
 *
 * Usage:
 * ```kotlin
 * val doc = report("Machine State Analysis") {
 *     stateFrequency(machineStateFreq, showTransitionProportions = true)
 * }
 * ```
 *
 * @param freq                      the state frequency tabulation to report
 * @param caption                   optional section title; defaults to [freq.name][StateFrequency.name]
 * @param confidenceLevel           confidence level for the StatPropertyTable CI; must be in (0, 1)
 * @param showStatistics            when `true` (default) a StatPropertyTable of numeric statistics on
 *                                  observed state numbers is included. Set to `false` when the numeric
 *                                  statistics on state numbers have no meaningful interpretation.
 * @param showTransitions           when `true` (default) a transition count matrix DataTable is included
 * @param showTransitionProportions when `true` a transition proportion matrix DataTable is included
 *                                  (row-normalised: each row sums to 1.0 for visited states).
 *                                  Default is `false`.
 * @param showPlot                  when `true` (default) a frequency bar chart is appended
 * @param proportions               when `true` the bar chart y-axis shows proportions; when `false`
 *                                  (default) it shows counts. Has no effect when [showPlot] is `false`.
 */
fun ReportBuilder.stateFrequency(
    freq: StateFrequency,
    caption: String? = null,
    confidenceLevel: Double = 0.95,
    showStatistics: Boolean = true,
    showTransitions: Boolean = true,
    showTransitionProportions: Boolean = false,
    showPlot: Boolean = true,
    proportions: Boolean = false
) {
    val myTitle = caption ?: freq.name
    section(myTitle) {
        // ── Overview paragraph ────────────────────────────────────────────────
        paragraph(
            "States: ${freq.states.size}  |  " +
            "Total observations: ${freq.totalCount.toInt()}"
        )

        // ── State frequency table ─────────────────────────────────────────────
        // Uses frequency(state.number) rather than the frequencies array so that
        // states never observed still appear in the table with a count of zero.
        val myFreqHeaders = listOf("State", "Count", "Cum Count", "%", "Cum %")
        val myFreqRows = freq.states.map { state ->
            val cnt = freq.frequency(state.number).toInt()
            val prop = freq.proportion(state.number)
            val cumCnt = freq.cumulativeFrequency(state.number).toInt()
            val cumProp = freq.cumulativeProportion(state.number)
            listOf(
                state.label ?: state.name,
                cnt.toString(),
                cumCnt.toString(),
                formatPct(prop),
                formatPct(cumProp)
            )
        }
        dataTable(myFreqHeaders, myFreqRows, caption = "State Frequency Table")

        // ── Statistics on observed state numbers (explicit StatisticIfc bridge) ──
        if (showStatistics) {
            statPropertyTable(
                stat = freq.statistic(),
                caption = "Statistics on Observed States",
                confidenceLevel = confidenceLevel
            )
        }

        // ── Transition count matrix ───────────────────────────────────────────
        if (showTransitions) {
            val myStateNames = freq.stateNames
            val myTxCntHeaders = listOf("From \\ To") + myStateNames
            val myTxCounts = freq.transitionCounts
            val myTxCntRows = freq.states.mapIndexed { i, state ->
                listOf(state.label ?: state.name) + myTxCounts[i].map { it.toString() }
            }
            dataTable(myTxCntHeaders, myTxCntRows, caption = "Transition Count Matrix")
        }

        // ── Transition proportion matrix ──────────────────────────────────────
        if (showTransitionProportions) {
            val myStateNames = freq.stateNames
            val myTxPropHeaders = listOf("From \\ To") + myStateNames
            val myTxProps = freq.transitionProportions
            val myTxPropRows = freq.states.mapIndexed { i, state ->
                listOf(state.label ?: state.name) + myTxProps[i].map { formatPct(it) }
            }
            dataTable(myTxPropHeaders, myTxPropRows, caption = "Transition Proportion Matrix")
        }

        // ── Frequency plot ────────────────────────────────────────────────────
        if (showPlot) {
            plot(freq.frequencyPlot(proportions), caption = myTitle)
        }
    }
}

// ── toReport() ───────────────────────────────────────────────────────────────

/**
 * Builds a [ReportNode.Document] whose default content is the full state-frequency section
 * (overview paragraph, state frequency table, statistics on observed states, transition count
 * matrix, and plot).
 *
 * Zero-code path:
 * ```kotlin
 * machineStateFreq.toReport().showInBrowser()
 * machineStateFreq.toReport().writeMarkdown()
 * ```
 *
 * Custom block replaces the default:
 * ```kotlin
 * machineStateFreq.toReport("Machine State Analysis") {
 *     stateFrequency(this@toReport, showTransitionProportions = true)
 *     paragraph("Steady-state distribution estimated from proportions above.")
 * }
 * ```
 *
 * @param title                     document title; defaults to [StateFrequency.name]
 * @param confidenceLevel           confidence level for the StatPropertyTable CI; must be in (0, 1)
 * @param showStatistics            when `true` (default) numeric statistics on observed state numbers
 *                                  are included
 * @param showTransitions           when `true` (default) a transition count matrix is included
 * @param showTransitionProportions when `true` a transition proportion matrix is included
 *                                  (default `false`)
 * @param showPlot                  when `true` (default) a frequency bar chart is included
 * @param proportions               when `true` the bar chart y-axis shows proportions instead of counts
 * @param block                     optional DSL block; replaces the default when provided
 * @return the assembled [ReportNode.Document]
 */
fun StateFrequency.toReport(
    title: String = name,
    confidenceLevel: Double = 0.95,
    showStatistics: Boolean = true,
    showTransitions: Boolean = true,
    showTransitionProportions: Boolean = false,
    showPlot: Boolean = true,
    proportions: Boolean = false,
    block: ReportBuilder.() -> Unit = {
        stateFrequency(
            this@toReport,
            confidenceLevel = confidenceLevel,
            showStatistics = showStatistics,
            showTransitions = showTransitions,
            showTransitionProportions = showTransitionProportions,
            showPlot = showPlot,
            proportions = proportions
        )
    }
): ReportNode.Document = report(title, block)

// ── Private formatting helper ─────────────────────────────────────────────────

/**
 * Formats a proportion (value in [0, 1]) as a percentage string with two decimal
 * places, e.g. `"12.34%"`. Returns `"—"` for NaN or infinite values.
 */
private fun formatPct(value: Double): String = when {
    value.isNaN() || value.isInfinite() -> "—"
    else -> "%.2f%%".format(value * 100.0)
}
