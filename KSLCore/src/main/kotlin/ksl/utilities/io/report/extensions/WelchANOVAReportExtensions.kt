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
import ksl.utilities.statistic.WelchANOVA

/**
 * DSL extension functions on [ReportBuilder] for rendering [WelchANOVA] results.
 *
 * Welch's ANOVA tests equality of means across groups without assuming equal variances.
 * Each group is summarised by its [ksl.simopt.evaluator.EstimatedResponseIfc], which
 * provides the sample count, mean, variance, and confidence-interval methods needed
 * to build the group summary table.
 */

/**
 * Appends a self-contained section reporting the results of a [WelchANOVA] test.
 *
 * **Produces (inside a section titled `caption` or "Welch's ANOVA"):**
 * 1. A paragraph stating the number of groups, the significance level α, and H₀.
 * 2. A `DataTable` ("Test Statistics") with Property | Value rows:
 *    Groups k, Weighted mean, F statistic, Numerator df, Denominator df, p-value, α, Reject H₀.
 * 3. A `DataTable` ("Group Summary") with one row per group and columns:
 *    Group | N | Mean | Std Dev | Std Err | HW (level%) | CI Lower | CI Upper.
 *
 * Usage:
 * ```kotlin
 * val doc = report("ANOVA Results") {
 *     welchAnova(anova)
 * }
 * ```
 *
 * @param anova           the [WelchANOVA] result to report
 * @param caption         optional section title; defaults to "Welch's ANOVA"
 * @param confidenceLevel confidence level for the per-group CI columns (default 0.95)
 */
fun ReportBuilder.welchAnova(
    anova: WelchANOVA,
    caption: String? = null,
    confidenceLevel: Double = 0.95
) {
    val myTitle = caption ?: "Welch's ANOVA"
    section(myTitle) {
        // ── Hypothesis paragraph ──────────────────────────────────────────────
        val alpha = anova.type1ErrorCriteria
        paragraph(
            "Comparing means across ${anova.groups.size} groups using Welch's ANOVA " +
            "(α = $alpha). H₀: all group means are equal."
        )

        // ── Test statistics table ─────────────────────────────────────────────
        val testHeaders = listOf("Property", "Value")
        val testRows = listOf(
            listOf("Groups (k)", anova.groups.size.toString()),
            listOf("Weighted mean", "%.6f".format(anova.weightedMean)),
            listOf("F statistic", "%.6f".format(anova.fValue)),
            listOf("Numerator df", "%.4f".format(anova.dof1)),
            listOf("Denominator df", "%.4f".format(anova.dof2)),
            listOf("p-value", "%.6f".format(anova.pValue)),
            listOf("α", alpha.toString()),
            listOf("Reject H₀ (all means equal)", anova.rejectH0.toString())
        )
        dataTable(testHeaders, testRows, caption = "Test Statistics")

        // ── Group summary table ───────────────────────────────────────────────
        val pct = "%.0f%%".format(confidenceLevel * 100.0)
        val groupHeaders = listOf("Group", "N", "Mean", "Std Dev", "Std Err", "HW ($pct)", "CI Lower", "CI Upper")
        val groupRows = anova.groups.map { g ->
            val hw = g.halfWidth(confidenceLevel)
            val ci = g.confidenceInterval(confidenceLevel)
            listOf(
                g.name,
                g.count.toInt().toString(),
                "%.6f".format(g.average),
                "%.6f".format(g.standardDeviation),
                "%.6f".format(g.standardError),
                "%.6f".format(hw),
                "%.6f".format(ci.lowerLimit),
                "%.6f".format(ci.upperLimit)
            )
        }
        dataTable(groupHeaders, groupRows, caption = "Group Summary")
    }
}

/**
 * Builds a [ReportNode.Document] whose default content is the full Welch's ANOVA section.
 *
 * Zero-code path:
 * ```kotlin
 * anova.toReport().showInBrowser()
 * anova.toReport().writeMarkdown()
 * ```
 *
 * Custom block replaces the default:
 * ```kotlin
 * anova.toReport("Experiment Results") {
 *     welchAnova(this@toReport)
 *     paragraph("See attached data for raw observations.")
 * }
 * ```
 *
 * @param title           document title; defaults to "Welch's ANOVA"
 * @param confidenceLevel confidence level for per-group CI columns (default 0.95)
 * @param block           optional DSL block; replaces the default when provided
 * @return the assembled [ReportNode.Document]
 */
fun WelchANOVA.toReport(
    title: String = "Welch's ANOVA",
    confidenceLevel: Double = 0.95,
    block: ReportBuilder.() -> Unit = {
        welchAnova(this@toReport, confidenceLevel = confidenceLevel)
    }
): ReportNode.Document = report(title, block)
