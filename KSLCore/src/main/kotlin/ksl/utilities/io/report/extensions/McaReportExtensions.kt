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

import ksl.utilities.io.plotting.ConfidenceIntervalsPlot
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.ReportBuilder
import ksl.utilities.io.report.dsl.report
import ksl.utilities.statistic.MultipleComparisonAnalyzer

/**
 * Controls which MCB direction sections are rendered by [multipleComparison].
 *
 * - [MAX]  — render only the "bigger is better" MCB intervals and max screening
 * - [MIN]  — render only the "smaller is better" MCB intervals and min screening
 * - [BOTH] — render both max and min sections (the default)
 */
enum class MCBDirection { MAX, MIN, BOTH }

/**
 * DSL extension functions on [ReportBuilder] for rendering
 * [MultipleComparisonAnalyzer] results.
 *
 * The extension produces a self-contained section covering:
 * 1. Per-alternative summary statistics (`StatTable`)
 * 2. Pairwise difference statistics (`DataTable`)
 * 3. MCB max intervals and plot — when `direction` is [MCBDirection.MAX] or [MCBDirection.BOTH]
 * 4. MCB min intervals and plot — when `direction` is [MCBDirection.MIN] or [MCBDirection.BOTH]
 * 5. Screening results — direction-matched to `direction`
 */

/**
 * Appends a self-contained section for a [MultipleComparisonAnalyzer].
 *
 * **Produces (inside a section titled [mca.name][MultipleComparisonAnalyzer.name]
 * or `"Multiple Comparison Analysis"` when the name is blank):**
 *
 * 1. **Alternative Statistics** — `StatTable` at [altConfidenceLevel]
 * 2. **Pairwise Differences** — `DataTable` of half-width CIs at [diffConfidenceLevel]
 * 3. **MCB Max Intervals** *(when `direction` is [MCBDirection.MAX] or [MCBDirection.BOTH])* —
 *    `DataTable` with columns `Alternative | Lower | Upper | Possible Best` and a
 *    `ConfidenceIntervalsPlot`. An alternative is **Possible Best** for max when its
 *    upper interval limit is strictly greater than zero.
 * 4. **MCB Min Intervals** *(when `direction` is [MCBDirection.MIN] or [MCBDirection.BOTH])* —
 *    same structure. An alternative is **Possible Best** for min when its lower interval
 *    limit is strictly less than zero.
 * 5. **Screening** — direction-matched `DataTable`(s) of alternatives surviving screening
 *    at [probCorrectSelection].
 *
 * Usage:
 * ```kotlin
 * // Full report — both max and min, all defaults from the MCA object
 * val doc = report("Throughput Study") {
 *     multipleComparison(mca)
 * }
 *
 * // Minimum only, custom indifference zone and PCS
 * val doc = report("Cost Study") {
 *     multipleComparison(
 *         mca,
 *         direction           = MCBDirection.MIN,
 *         indifferenceZone    = 5.0,
 *         probCorrectSelection = 0.90
 *     )
 * }
 * ```
 *
 * @param mca                 the multiple comparison analyzer to report
 * @param direction           which MCB direction(s) to render; default [MCBDirection.BOTH]
 * @param indifferenceZone    delta for MCB interval construction; defaults to
 *                            [mca.defaultIndifferenceZone][MultipleComparisonAnalyzer.defaultIndifferenceZone]
 * @param altConfidenceLevel  confidence level for the alternatives `StatTable`; defaults to
 *                            [mca.defaultLevel][MultipleComparisonAnalyzer.defaultLevel]
 * @param diffConfidenceLevel confidence level for the pairwise differences table; defaults to
 *                            [mca.defaultLevel][MultipleComparisonAnalyzer.defaultLevel]
 * @param probCorrectSelection probability of correct selection for screening; defaults to
 *                            [mca.defaultLevel][MultipleComparisonAnalyzer.defaultLevel]
 * @param showAltCIPlot       `true` inserts an **"Alternative Confidence Intervals"**
 *                            sub-section immediately after the Alternative Statistics table;
 *                            the section contains a [ConfidenceIntervalsPlot] showing the
 *                            across-replication CI for each alternative's mean at
 *                            [altConfidenceLevel]; defaults to `false`
 * @param showBoxPlot         `true` inserts a **"Response Distributions"** sub-section
 *                            after the CI plot sub-section (or directly after the statistics
 *                            table when [showAltCIPlot] is `false`); the section contains a
 *                            [ksl.utilities.io.plotting.MultiBoxPlot] with one box per
 *                            alternative sourced from
 *                            [MultipleComparisonAnalyzer.observationsAsMap]; defaults to `false`
 */
fun ReportBuilder.multipleComparison(
    mca: MultipleComparisonAnalyzer,
    direction: MCBDirection = MCBDirection.BOTH,
    indifferenceZone: Double = mca.defaultIndifferenceZone,
    altConfidenceLevel: Double = mca.defaultLevel,
    diffConfidenceLevel: Double = mca.defaultLevel,
    probCorrectSelection: Double = mca.defaultLevel,
    showAltCIPlot: Boolean = false,
    showBoxPlot: Boolean = false
) {
    val myTitle = mca.name.ifBlank { "Multiple Comparison Analysis" }
    section(myTitle) {

        // ── 1. Per-alternative summary statistics ─────────────────────────────
        statTable(
            stats = mca.statistics,
            caption = "Alternative Statistics",
            confidenceLevel = altConfidenceLevel
        )

        // ── 1a. Optional alternative CI plot ──────────────────────────────────
        if (showAltCIPlot) {
            section("Alternative Confidence Intervals") {
                multipleComparisonCIPlot(mca, confidenceLevel = altConfidenceLevel)
            }
        }

        // ── 1b. Optional response distributions box plot ──────────────────────
        if (showBoxPlot) {
            section("Response Distributions") {
                multipleComparisonBoxPlot(mca)
            }
        }

        // ── 2. Pairwise difference statistics ────────────────────────────────
        section("Pairwise Differences") {
            val myDiffStats = mca.pairedDifferenceStatistics
            if (myDiffStats.isNotEmpty()) {
                val myHeaders = listOf(
                    "Pair", "Count", "Mean Diff", "Std Dev",
                    "Half Width", "CI Lower", "CI Upper"
                )
                val myRows = myDiffStats.map { stat ->
                    val myHw = stat.halfWidth(diffConfidenceLevel)
                    listOf(
                        stat.name,
                        stat.count.toInt().toString(),
                        fmtD(stat.average),
                        fmtD(stat.standardDeviation),
                        fmtD(myHw),
                        fmtD(stat.average - myHw),
                        fmtD(stat.average + myHw)
                    )
                }
                dataTable(myHeaders, myRows, caption = "Pairwise Difference Statistics (CL = $diffConfidenceLevel)")
            } else {
                paragraph("No pairwise differences available.")
            }
        }

        // ── 3. MCB max intervals ──────────────────────────────────────────────
        if (direction == MCBDirection.MAX || direction == MCBDirection.BOTH) {
            section("MCB Max Intervals") {
                val myMaxMap = mca.mcbMaxIntervalsAsMap(indifferenceZone)
                paragraph(
                    "Indifference delta: $indifferenceZone  |  " +
                    "Best (max): ${mca.nameOfMaximumAverageOfData}"
                )
                val myHeaders = listOf("Alternative", "Lower", "Upper", "Possible Best")
                val myRows = myMaxMap.entries.map { (name, iv) ->
                    listOf(
                        name,
                        fmtD(iv.lowerLimit),
                        fmtD(iv.upperLimit),
                        (iv.upperLimit != 0.0).toString()
                    )
                }
                dataTable(myHeaders, myRows, caption = "MCB Max Intervals (delta = $indifferenceZone)")
                plot(
                    ConfidenceIntervalsPlot(myMaxMap, referencePoint = 0.0),
                    caption = "MCB Max Intervals: $myTitle"
                )
            }
        }

        // ── 4. MCB min intervals ──────────────────────────────────────────────
        if (direction == MCBDirection.MIN || direction == MCBDirection.BOTH) {
            section("MCB Min Intervals") {
                val myMinMap = mca.mcbMinIntervalsAsMap(indifferenceZone)
                paragraph(
                    "Indifference delta: $indifferenceZone  |  " +
                    "Best (min): ${mca.nameOfMinimumAverageOfData}"
                )
                val myHeaders = listOf("Alternative", "Lower", "Upper", "Possible Best")
                val myRows = myMinMap.entries.map { (name, iv) ->
                    listOf(
                        name,
                        fmtD(iv.lowerLimit),
                        fmtD(iv.upperLimit),
                        (iv.lowerLimit != 0.0).toString()
                    )
                }
                dataTable(myHeaders, myRows, caption = "MCB Min Intervals (delta = $indifferenceZone)")
                plot(
                    ConfidenceIntervalsPlot(myMinMap, referencePoint = 0.0),
                    caption = "MCB Min Intervals: $myTitle"
                )
            }
        }

        // ── 5. Screening ──────────────────────────────────────────────────────
        section("Screening") {
            paragraph("Probability of correct selection: $probCorrectSelection")
            if (direction == MCBDirection.MAX || direction == MCBDirection.BOTH) {
                val myMaxSurvivors = mca.screenForMaximum(probCorrectSelection)
                dataTable(
                    headers = listOf("Alternative", "Survives Screening for Maximum"),
                    rows = mca.statistics.map { stat ->
                        listOf(stat.name, myMaxSurvivors.contains(stat.name).toString())
                    },
                    caption = "Screening for Maximum (PCS = $probCorrectSelection)"
                )
            }
            if (direction == MCBDirection.MIN || direction == MCBDirection.BOTH) {
                val myMinSurvivors = mca.screenForMinimum(probCorrectSelection)
                dataTable(
                    headers = listOf("Alternative", "Survives Screening for Minimum"),
                    rows = mca.statistics.map { stat ->
                        listOf(stat.name, myMinSurvivors.contains(stat.name).toString())
                    },
                    caption = "Screening for Minimum (PCS = $probCorrectSelection)"
                )
            }
        }
    }
}

// ── Standalone CI plot wrapper ────────────────────────────────────────────────

/**
 * Appends a [ConfidenceIntervalsPlot] showing the across-replication confidence
 * interval for each alternative's mean.
 *
 * Each alternative is represented by a point (its sample mean) and an error bar
 * spanning its CI at [confidenceLevel]. The intervals are computed from
 * [MultipleComparisonAnalyzer.statistics] via
 * [ksl.utilities.io.StatisticReporter.confidenceIntervals].
 *
 * An optional vertical [referencePoint] line can mark a target value or known
 * standard; defaults to `null` (no reference line) because there is no universal
 * reference for absolute alternative means.
 *
 * Independently callable in any composite document:
 * ```kotlin
 * report("Throughput Study") {
 *     multipleComparisonCIPlot(mca, confidenceLevel = 0.95)
 * }
 * ```
 *
 * @param mca             the analyzer whose per-alternative statistics supply the intervals
 * @param confidenceLevel confidence level for the intervals; defaults to [mca.defaultLevel]
 * @param referencePoint  optional x-intercept for a reference line; `null` suppresses it
 * @param caption         optional plot caption; defaults to
 *                        `"Alternative Confidence Intervals — <mca.name>"`
 */
fun ReportBuilder.multipleComparisonCIPlot(
    mca: MultipleComparisonAnalyzer,
    confidenceLevel: Double = mca.defaultLevel,
    referencePoint: Double? = null,
    caption: String? = null
) {
    plot(
        ConfidenceIntervalsPlot(mca.statistics, level = confidenceLevel, referencePoint = referencePoint),
        caption ?: "Alternative Confidence Intervals \u2014 ${mca.name}"
    )
}

// ── toReport() ───────────────────────────────────────────────────────────────

/**
 * Builds a [ReportNode.Document] whose default content is the full multiple-comparison
 * analysis section using all defaults from the [MultipleComparisonAnalyzer] object
 * itself ([MultipleComparisonAnalyzer.defaultLevel],
 * [MultipleComparisonAnalyzer.defaultIndifferenceZone]).
 *
 * Zero-code path:
 * ```kotlin
 * mca.toReport().showInBrowser()
 * mca.toReport().writeMarkdown()
 * mca.toReport().printText()
 * ```
 *
 * Supply a [block] to customise direction, levels, or append additional content:
 * ```kotlin
 * // Minimum-only with custom indifference zone
 * mca.toReport("Cost Study") {
 *     multipleComparison(
 *         this@toReport,
 *         direction        = MCBDirection.MIN,
 *         indifferenceZone = 5.0
 *     )
 * }
 *
 * // Standard full report with a trailing note
 * mca.toReport("Throughput Study") {
 *     multipleComparison(this@toReport)
 *     paragraph("Recommendation: prefer ${this@toReport.nameOfMaximumAverageOfData}.")
 * }
 * ```
 *
 * @param title           document title; defaults to [MultipleComparisonAnalyzer.name] or
 *                        `"Multiple Comparison Analysis"` when the name is blank
 * @param showAltCIPlot   `true` includes an **"Alternative Confidence Intervals"** CI-plot
 *                        sub-section in the default content; defaults to `false`
 * @param showBoxPlot     `true` includes a **"Response Distributions"** box-plot sub-section
 *                        in the default content; defaults to `false`
 * @param block           optional DSL block; replaces the default when provided
 * @return the assembled [ReportNode.Document]
 */
fun MultipleComparisonAnalyzer.toReport(
    title: String = name.ifBlank { "Multiple Comparison Analysis" },
    showAltCIPlot: Boolean = false,
    showBoxPlot: Boolean = false,
    block: ReportBuilder.() -> Unit = {
        multipleComparison(this@toReport, showAltCIPlot = showAltCIPlot, showBoxPlot = showBoxPlot)
    }
): ReportNode.Document = report(title, block)

// ── Private formatting helper ─────────────────────────────────────────────────

/** Formats a [Double] to 4 decimal places; returns `"—"` for NaN or infinite values. */
private fun fmtD(value: Double): String = when {
    value.isNaN() || value.isInfinite() -> "—"
    else -> "%.4f".format(value)
}
