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
import ksl.utilities.io.report.dsl.ReportBuilder
import ksl.utilities.statistic.MultipleComparisonAnalyzer

/**
 * DSL extension functions on [ReportBuilder] for rendering
 * [MultipleComparisonAnalyzer] results.
 *
 * The extension produces a self-contained section covering:
 * 1. Per-alternative summary statistics (`StatTable`)
 * 2. Pairwise difference statistics (`DataTable`)
 * 3. MCB max intervals and plot (`DataTable` + `PlotNode`)
 * 4. MCB min intervals and plot (`DataTable` + `PlotNode`)
 * 5. Screening results for maximum and minimum (two `DataTable`s)
 */

/**
 * Appends a self-contained section for a [MultipleComparisonAnalyzer].
 *
 * **Produces (inside a section titled [mca.name][MultipleComparisonAnalyzer.name]
 * or "Multiple Comparison Analysis" when the name is blank):**
 *
 * 1. **Alternative Statistics** — `StatTable` for all `mca.statistics`
 * 2. **Pairwise Differences** — `DataTable` with columns
 *    `Pair | Count | Mean Diff | Std Dev | Half Width | CI Lower | CI Upper`
 * 3. **MCB Max Intervals** — `DataTable` with columns
 *    `Alternative | Lower | Upper | Possible Best (Max)` and a
 *    `ConfidenceIntervalsPlot` of the max intervals
 * 4. **MCB Min Intervals** — `DataTable` with columns
 *    `Alternative | Lower | Upper | Possible Best (Min)` and a
 *    `ConfidenceIntervalsPlot` of the min intervals
 * 5. **Screening** — `DataTable` of alternatives surviving screening for maximum
 *    and minimum at [confidenceLevel]
 *
 * Usage:
 * ```kotlin
 * val doc = report("Throughput Study") {
 *     multipleComparison(mca, confidenceLevel = 0.95)
 * }
 * ```
 *
 * @param mca             the multiple comparison analyzer to report
 * @param confidenceLevel probability of correct selection for MCB intervals and
 *                        screening; must be in (0, 1)
 */
fun ReportBuilder.multipleComparison(
    mca: MultipleComparisonAnalyzer,
    confidenceLevel: Double = 0.95
) {
    val myTitle = mca.name.ifBlank { "Multiple Comparison Analysis" }
    section(myTitle) {
        // ── 1. Per-alternative summary statistics ─────────────────────────────
        statTable(
            stats = mca.statistics,
            caption = "Alternative Statistics",
            confidenceLevel = confidenceLevel
        )

        // ── 2. Pairwise difference statistics ────────────────────────────────
        section("Pairwise Differences") {
            val myDiffStats = mca.pairedDifferenceStatistics
            if (myDiffStats.isNotEmpty()) {
                val myHeaders = listOf(
                    "Pair", "Count", "Mean Diff", "Std Dev",
                    "Half Width", "CI Lower", "CI Upper"
                )
                val myRows = myDiffStats.map { stat ->
                    val myHw = stat.halfWidth(confidenceLevel)
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
                dataTable(myHeaders, myRows, caption = "Pairwise Difference Statistics")
            } else {
                paragraph("No pairwise differences available.")
            }
        }

        // ── 3. MCB max intervals ──────────────────────────────────────────────
        section("MCB Max Intervals") {
            val myMaxMap = mca.mcbMaxIntervalsAsMap(mca.defaultIndifferenceZone)
            paragraph(
                "Indifference delta: ${mca.defaultIndifferenceZone}  |  " +
                "Best (max): ${mca.nameOfMaximumAverageOfData}"
            )
            val myHeaders = listOf("Alternative", "Lower", "Upper", "Contains 0 (Possible Best)")
            val myRows = myMaxMap.entries.map { (name, iv) ->
                listOf(
                    name,
                    fmtD(iv.lowerLimit),
                    fmtD(iv.upperLimit),
                    (iv.lowerLimit <= 0.0 && iv.upperLimit >= 0.0).toString()
                )
            }
            dataTable(myHeaders, myRows, caption = "MCB Max Intervals (delta = ${mca.defaultIndifferenceZone})")
            plot(
                ConfidenceIntervalsPlot(myMaxMap, referencePoint = 0.0),
                caption = "MCB Max Intervals: $myTitle"
            )
        }

        // ── 4. MCB min intervals ──────────────────────────────────────────────
        section("MCB Min Intervals") {
            val myMinMap = mca.mcbMinIntervalsAsMap(mca.defaultIndifferenceZone)
            paragraph(
                "Indifference delta: ${mca.defaultIndifferenceZone}  |  " +
                "Best (min): ${mca.nameOfMinimumAverageOfData}"
            )
            val myHeaders = listOf("Alternative", "Lower", "Upper", "Contains 0 (Possible Best)")
            val myRows = myMinMap.entries.map { (name, iv) ->
                listOf(
                    name,
                    fmtD(iv.lowerLimit),
                    fmtD(iv.upperLimit),
                    (iv.lowerLimit <= 0.0 && iv.upperLimit >= 0.0).toString()
                )
            }
            dataTable(myHeaders, myRows, caption = "MCB Min Intervals (delta = ${mca.defaultIndifferenceZone})")
            plot(
                ConfidenceIntervalsPlot(myMinMap, referencePoint = 0.0),
                caption = "MCB Min Intervals: $myTitle"
            )
        }

        // ── 5. Screening ──────────────────────────────────────────────────────
        section("Screening") {
            val myMaxSurvivors = mca.screenForMaximum(confidenceLevel)
            val myMinSurvivors = mca.screenForMinimum(confidenceLevel)
            paragraph("Probability of correct selection: $confidenceLevel")

            dataTable(
                headers = listOf("Alternative", "Survives Screening for Maximum"),
                rows = mca.statistics.map { stat ->
                    listOf(stat.name, myMaxSurvivors.contains(stat.name).toString())
                },
                caption = "Screening for Maximum (PCS = $confidenceLevel)"
            )
            dataTable(
                headers = listOf("Alternative", "Survives Screening for Minimum"),
                rows = mca.statistics.map { stat ->
                    listOf(stat.name, myMinSurvivors.contains(stat.name).toString())
                },
                caption = "Screening for Minimum (PCS = $confidenceLevel)"
            )
        }
    }
}

// ── Private formatting helper ─────────────────────────────────────────────────

/** Formats a [Double] to 4 decimal places; returns `"—"` for NaN or infinite values. */
private fun fmtD(value: Double): String = when {
    value.isNaN() || value.isInfinite() -> "—"
    else -> "%.4f".format(value)
}
