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

package ksl.utilities.io.report.dsl

import ksl.utilities.io.plotting.PlotIfc
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.statistic.StatisticIfc
import ksl.utilities.statistic.WeightedStatistic

/**
 * DSL marker annotation for the KSL report builder. Prevents implicit receiver
 * leakage when [ReportBuilder] blocks are nested (e.g., inside [ReportBuilder.section]).
 */
@DslMarker
annotation class ReportDsl

/**
 * Builder for assembling a [ReportNode] tree using a Kotlin DSL.
 *
 * Each method corresponds to one [ReportNode] leaf type and appends a node to the
 * current builder's child list. The [section] method creates a nested [ReportBuilder],
 * applies the user's block, and wraps the result in a [ReportNode.Section].
 *
 * Users never instantiate this class directly — use the top-level [report] function:
 * ```kotlin
 * val doc = report("My Report") {
 *     heading("Summary", level = 2)
 *     statTable(myStats, confidenceLevel = 0.90)
 *     section("Details") {
 *         histogram(myHistogram)
 *         plot(myPlot, caption = "Distribution")
 *     }
 * }
 * ```
 *
 * KSL-provided extension functions on [ReportBuilder] (e.g., `histogram()`,
 * `multipleComparison()`, `simulationResults()`) add domain-specific content by
 * calling the primitive methods below, keeping this class small and stable.
 *
 * All mutable state uses the `my` prefix per KSL coding conventions.
 */
@ReportDsl
class ReportBuilder internal constructor(private val myTitle: String? = null) {

    private val myChildren: MutableList<ReportNode> = mutableListOf()

    // ── Structural ──────────────────────────────────────────────────────────────

    /**
     * Creates a nested section with the given [title] and appends it to this builder.
     * The [block] is applied to a new [ReportBuilder] scoped to the section.
     *
     * Sections may be nested to any depth. Renderers track depth and promote heading
     * levels automatically.
     *
     * @param title optional section title; null produces an untitled grouping
     * @param block DSL block executed in the context of the new section's builder
     */
    fun section(title: String? = null, block: ReportBuilder.() -> Unit) {
        val mySub = ReportBuilder(title).apply(block)
        myChildren += ReportNode.Section(title, mySub.buildChildren())
    }

    // ── Content leaves ──────────────────────────────────────────────────────────

    /**
     * Appends a [ReportNode.Heading] at the given [level] (1–6).
     *
     * @param text  heading text
     * @param level heading level; defaults to 1
     */
    fun heading(text: String, level: Int = 1) {
        myChildren += ReportNode.Heading(text, level)
    }

    /**
     * Appends a [ReportNode.Paragraph] with the given [text].
     */
    fun paragraph(text: String) {
        myChildren += ReportNode.Paragraph(text)
    }

    /**
     * Appends a [ReportNode.StatPropertyTable] — a vertical `Property | Value` table
     * for a **single** [StatisticIfc].
     *
     * Use this whenever a single statistic deserves its own full property sheet (e.g.
     * inside a histogram section, a frequency section, or after a batch-means analysis).
     * For a side-by-side comparison of **many** statistics use [statTable].
     *
     * @param stat            the single statistic to display
     * @param caption         optional table caption; defaults to [stat.name][StatisticIfc.name]
     * @param confidenceLevel confidence level for the half-width and CI rows; defaults to 0.95
     * @param detail          false = compact 10-row view; true = full 18-row view
     */
    fun statPropertyTable(
        stat: StatisticIfc,
        caption: String? = null,
        confidenceLevel: Double = 0.95,
        detail: Boolean = false
    ) {
        myChildren += ReportNode.StatPropertyTable(stat, caption, confidenceLevel, detail)
    }

    /**
     * Appends a [ReportNode.StatTable] for the given list of [StatisticIfc] instances.
     *
     * Covers [ksl.utilities.statistic.Statistic], [ksl.utilities.statistic.BatchStatistic],
     * and [ksl.utilities.statistic.Histogram] (all implement [StatisticIfc]).
     * For a single statistic property sheet use [statPropertyTable].
     *
     * @param stats           statistics to tabulate
     * @param caption         optional table caption
     * @param confidenceLevel confidence level for half-width and CI; defaults to 0.95
     * @param detail          false = compact summary; true = compact + diagnostic table
     */
    fun statTable(
        stats: List<StatisticIfc>,
        caption: String? = null,
        confidenceLevel: Double = 0.95,
        detail: Boolean = false
    ) {
        myChildren += ReportNode.StatTable(stats, caption, confidenceLevel, detail)
    }

    /**
     * Appends a [ReportNode.WeightedStatPropertyTable] — a vertical `Property | Value`
     * table for a **single** [WeightedStatistic].
     *
     * Use this whenever a single weighted statistic deserves its own property sheet.
     * For a side-by-side comparison of **many** weighted statistics use [weightedStatTable].
     *
     * @param stat    the single weighted statistic to display
     * @param caption optional table caption; defaults to [stat.name][WeightedStatistic.name]
     */
    fun weightedStatPropertyTable(
        stat: WeightedStatistic,
        caption: String? = null
    ) {
        myChildren += ReportNode.WeightedStatPropertyTable(stat, caption)
    }

    /**
     * Appends a [ReportNode.WeightedStatTable] for the given list of [WeightedStatistic]
     * instances. [WeightedStatistic] implements [ksl.utilities.statistic.WeightedStatisticIfc]
     * rather than [StatisticIfc] and must flow through this dedicated node.
     * For a single weighted statistic property sheet use [weightedStatPropertyTable].
     *
     * @param stats   weighted statistics to tabulate
     * @param caption optional table caption
     */
    fun weightedStatTable(
        stats: List<WeightedStatistic>,
        caption: String? = null
    ) {
        myChildren += ReportNode.WeightedStatTable(stats, caption)
    }

    /**
     * Appends a [ReportNode.DataTable] for pre-formatted string data.
     *
     * All rows must have the same number of cells as [headers].
     *
     * @param headers column header labels
     * @param rows    table body; each list must be the same length as [headers]
     * @param caption optional table caption
     */
    fun dataTable(
        headers: List<String>,
        rows: List<List<String>>,
        caption: String? = null
    ) {
        myChildren += ReportNode.DataTable(headers, rows, caption)
    }

    /**
     * Appends a [ReportNode.PlotNode] wrapping the given [PlotIfc] implementation.
     *
     * @param plot    the plot to embed
     * @param caption optional caption displayed below the plot
     */
    fun plot(plot: PlotIfc, caption: String? = null) {
        myChildren += ReportNode.PlotNode(plot, caption)
    }

    /**
     * Appends a [ReportNode.RawText] verbatim text block.
     * Rendered as `<pre>` in HTML, a fenced code block in Markdown, or
     * indented plain text in the text renderer.
     *
     * @param content the verbatim content
     */
    fun text(content: String) {
        myChildren += ReportNode.RawText(content)
    }

    /**
     * Appends a [ReportNode.PageBreak] separator.
     */
    fun pageBreak() {
        myChildren += ReportNode.PageBreak
    }

    // ── Internal ────────────────────────────────────────────────────────────────

    /**
     * Returns an immutable snapshot of the children accumulated so far.
     * Called by [section] for nested builders and by [report] for the root builder.
     */
    internal fun buildChildren(): List<ReportNode> = myChildren.toList()
}
