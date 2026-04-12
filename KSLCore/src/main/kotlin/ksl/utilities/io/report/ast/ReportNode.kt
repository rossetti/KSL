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

package ksl.utilities.io.report.ast

import ksl.utilities.io.plotting.PlotIfc
import ksl.utilities.io.report.visitor.ReportVisitor
import ksl.utilities.statistic.StatisticIfc
import ksl.utilities.statistic.WeightedStatistic

/**
 * A sealed hierarchy of format-agnostic AST nodes representing report content.
 *
 * The tree is assembled by the DSL ([ksl.utilities.io.report.dsl.ReportBuilder]) and
 * traversed by [ReportVisitor] implementations (renderers). Each node implements
 * [accept] for double-dispatch, ensuring compile-time completeness in every renderer.
 *
 * **Structural nodes** (recurse into children):
 * - [Document] — root of the tree; carries the report title
 * - [Section]  — collapsible/titled group of child nodes; may be nested
 *
 * **Content leaf nodes** (carry data, no children):
 * - [Heading]                  — titled heading at a given level (1–6)
 * - [Paragraph]                — free-form text
 * - [StatPropertyTable]        — vertical `Property | Value` sheet for a **single** [StatisticIfc]
 * - [StatTable]                — horizontal comparison table for [List]<[StatisticIfc]>
 * - [WeightedStatPropertyTable]— vertical `Property | Value` sheet for a **single** [WeightedStatistic]
 * - [WeightedStatTable]        — horizontal table for [List]<[WeightedStatistic]>
 * - [DataTable]                — general-purpose pre-formatted string table
 * - [PlotNode]                 — a [PlotIfc] plot with an optional caption
 * - [RawText]                  — verbatim pre-formatted text block
 * - [PageBreak]                — logical page/section separator
 */
sealed class ReportNode {

    /** Double-dispatch entry point — each node calls the appropriate visitor method. */
    abstract fun accept(visitor: ReportVisitor)

    // ── Structural nodes ────────────────────────────────────────────────────────

    /**
     * The root node of a report document.
     *
     * @param title the document title, used as the top-level heading
     * @param children ordered list of child nodes
     */
    data class Document(
        val title: String,
        val children: List<ReportNode>
    ) : ReportNode() {
        override fun accept(visitor: ReportVisitor) {
            visitor.enterDocument(this)
            children.forEach { it.accept(visitor) }
            visitor.exitDocument(this)
        }
    }

    /**
     * A titled, collapsible group of child nodes. Sections may be nested to any depth;
     * renderers are responsible for tracking depth and adjusting heading levels accordingly.
     *
     * @param title optional section title; null produces an untitled grouping
     * @param children ordered list of child nodes
     */
    data class Section(
        val title: String?,
        val children: List<ReportNode>
    ) : ReportNode() {
        override fun accept(visitor: ReportVisitor) {
            visitor.enterSection(this)
            children.forEach { it.accept(visitor) }
            visitor.exitSection(this)
        }
    }

    // ── Content leaf nodes ──────────────────────────────────────────────────────

    /**
     * A standalone heading at the given level.
     *
     * @param text  heading text
     * @param level heading level 1–6 (analogous to HTML h1–h6); defaults to 1
     */
    data class Heading(
        val text: String,
        val level: Int = 1
    ) : ReportNode() {
        init {
            require(level in 1..6) { "Heading level must be in 1..6, was $level" }
        }
        override fun accept(visitor: ReportVisitor) = visitor.visit(this)
    }

    /**
     * A free-form paragraph of text.
     *
     * @param text the paragraph content
     */
    data class Paragraph(
        val text: String
    ) : ReportNode() {
        override fun accept(visitor: ReportVisitor) = visitor.visit(this)
    }

    /**
     * A vertical `Property | Value` property sheet for a **single** [StatisticIfc].
     *
     * The natural complement to [StatTable]: where [StatTable] compares many statistics
     * side-by-side, [StatPropertyTable] shows all properties of one statistic in a
     * readable two-column layout. This mirrors [StatisticIfc.statisticsAsMap] and
     * [ksl.utilities.io.toStatDataFrame], both of which use the same vertical orientation.
     *
     * Properties rendered:
     * - `detail = false` (compact) — 9 rows: Count, Average, Std Dev, Std Error,
     *   Half-width, Confidence Level, CI Lower, CI Upper, Min, Max
     * - `detail = true` (full) — all 18 rows from [StatisticIfc.statisticsAsMap],
     *   including Sum, Variance, Dev SS, Kurtosis, Skewness, Lag-1 Cov, Lag-1 Corr,
     *   Von Neumann Test Statistic, and Missing
     *
     * The [confidenceLevel] from this node is always used for half-width and CI
     * computation — it is never read from the statistic's stored property.
     *
     * @param stat            the single statistic to display
     * @param caption         optional table caption; defaults to [stat.name][StatisticIfc.name]
     * @param confidenceLevel confidence level for half-width and CI rows; defaults to 0.95
     * @param detail          false = compact 10-row view; true = full 18-row view
     */
    data class StatPropertyTable(
        val stat: StatisticIfc,
        val caption: String? = null,
        val confidenceLevel: Double = 0.95,
        val detail: Boolean = false
    ) : ReportNode() {
        init {
            require(confidenceLevel in 0.0..1.0) {
                "confidenceLevel must be in [0, 1], was $confidenceLevel"
            }
        }
        override fun accept(visitor: ReportVisitor) = visitor.visit(this)
    }

    /**
     * A horizontal summary/comparison table for any [List] of [StatisticIfc] instances.
     *
     * Each row is one statistic; columns are: Name, Count, Average, Std Dev,
     * Half-width, CI Lower, CI Upper, Min, Max. When [detail] is true a second
     * diagnostic table is appended with Skewness, Kurtosis, Lag-1 Corr, Von Neumann
     * test statistic + p-value, and Missing.
     *
     * For reporting a **single** statistic as a property sheet use [StatPropertyTable].
     *
     * @param stats           the statistics to tabulate; must not be empty
     * @param caption         optional table caption
     * @param confidenceLevel confidence level for half-width and CI columns; defaults to 0.95
     * @param detail          false (default) = compact half-width summary;
     *                        true = compact summary + diagnostic table
     */
    data class StatTable(
        val stats: List<StatisticIfc>,
        val caption: String? = null,
        val confidenceLevel: Double = 0.95,
        val detail: Boolean = false
    ) : ReportNode() {
        init {
            require(confidenceLevel in 0.0..1.0) {
                "confidenceLevel must be in [0, 1], was $confidenceLevel"
            }
        }
        override fun accept(visitor: ReportVisitor) = visitor.visit(this)
    }

    /**
     * A vertical `Property | Value` property sheet for a **single** [WeightedStatistic].
     *
     * The weighted-statistic analogue of [StatPropertyTable]. Renders a two-column table
     * whose rows are the 9 named properties of one [WeightedStatistic]: Count, Weighted
     * Average, Unweighted Average, Weighted Sum, Sum of Weights, Weighted Sum of Squares,
     * Min, Max, Missing.
     *
     * For reporting **many** weighted statistics side-by-side use [WeightedStatTable].
     *
     * @param stat    the single weighted statistic to display
     * @param caption optional table caption; defaults to [stat.name][WeightedStatistic.name]
     */
    data class WeightedStatPropertyTable(
        val stat: WeightedStatistic,
        val caption: String? = null
    ) : ReportNode() {
        override fun accept(visitor: ReportVisitor) = visitor.visit(this)
    }

    /**
     * A horizontal comparison table for a [List] of [WeightedStatistic] instances.
     *
     * [WeightedStatistic] implements [ksl.utilities.statistic.WeightedStatisticIfc] rather
     * than [StatisticIfc] and therefore cannot flow through [StatTable]. Columns rendered:
     * Name, Count, Wtd Avg, Unwtd Avg, Wtd Sum, Sum Wts, Wtd SS, Min, Max, Missing.
     *
     * For reporting a **single** weighted statistic as a property sheet use
     * [WeightedStatPropertyTable].
     *
     * @param stats   the weighted statistics to tabulate; must not be empty
     * @param caption optional table caption
     */
    data class WeightedStatTable(
        val stats: List<WeightedStatistic>,
        val caption: String? = null
    ) : ReportNode() {
        override fun accept(visitor: ReportVisitor) = visitor.visit(this)
    }

    /**
     * A general-purpose tabular node for pre-formatted string data.
     *
     * Used for histogram bin tables, frequency tables, batch configuration summaries,
     * solver metrics, pairwise CI tables, time-series period tables, and similar
     * domain-specific tabular data that does not fit [StatTable] or [WeightedStatTable].
     *
     * @param headers column header labels
     * @param rows    table body rows; each inner list must have the same length as [headers]
     * @param caption optional table caption
     */
    data class DataTable(
        val headers: List<String>,
        val rows: List<List<String>>,
        val caption: String? = null
    ) : ReportNode() {
        init {
            require(headers.isNotEmpty()) { "DataTable must have at least one column header" }
            rows.forEachIndexed { i, row ->
                require(row.size == headers.size) {
                    "Row $i has ${row.size} cells but there are ${headers.size} headers"
                }
            }
        }
        override fun accept(visitor: ReportVisitor) = visitor.visit(this)
    }

    /**
     * A plot node wrapping any [PlotIfc] implementation.
     *
     * Renderers decide how to handle the plot based on their output format:
     * - HTML renderers call [PlotIfc.toEmbeddedHTML] to inline the Lets-Plot fragment
     * - File-based renderers call [PlotIfc.saveToFile] and embed a reference
     * - Text renderers write the file path as a caption line
     *
     * @param plot    the plot to render
     * @param caption optional caption displayed below the plot
     */
    data class PlotNode(
        val plot: PlotIfc,
        val caption: String? = null
    ) : ReportNode() {
        override fun accept(visitor: ReportVisitor) = visitor.visit(this)
    }

    /**
     * A verbatim pre-formatted text block (analogous to `<pre>` in HTML).
     *
     * Useful for embedding existing text-based output from classes such as
     * [ksl.utilities.statistic.MultipleComparisonAnalyzer] or solver logs.
     *
     * @param content the verbatim text content
     */
    data class RawText(
        val content: String
    ) : ReportNode() {
        override fun accept(visitor: ReportVisitor) = visitor.visit(this)
    }

    /**
     * A logical page or section separator.
     *
     * Renderers map this to their format equivalent: `<hr>` in HTML, `---` in Markdown,
     * `\newpage` in LaTeX, or a line of dashes in plain text.
     */
    data object PageBreak : ReportNode() {
        override fun accept(visitor: ReportVisitor) = visitor.visit(this)
    }
}
