/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.app.swing.common.comparison

import ksl.app.comparison.*

import ksl.app.config.ReportFormat
import ksl.utilities.io.plotting.ConfidenceIntervalsPlot
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.report
import ksl.utilities.io.report.extensions.MCBDirection
import ksl.utilities.io.report.extensions.multiBoxPlot
import ksl.utilities.io.report.extensions.multipleComparison
import ksl.utilities.io.report.writeHtml
import ksl.utilities.io.report.writeMarkdown
import ksl.utilities.io.report.writeText
import ksl.utilities.statistic.MultipleComparisonAnalyzer
import java.nio.file.Files
import java.nio.file.Path

/**
 *  Renderer for the Comparison Analyzer.  Translates a current
 *  [ComparisonSelectionModel] state into a report document via the
 *  substrate's existing report-DSL extensions
 *  ([multiBoxPlot], [multipleComparison],
 *  [ConfidenceIntervalsPlot]) and writes it in every requested
 *  [ReportFormat].  When HTML is among the formats, the rendered
 *  HTML file is opened in the user's default browser via
 *  [java.awt.Desktop.browse] so the analyst sees the result
 *  immediately.
 *
 *  The renderer is intentionally a pure side-effect-bearing object:
 *  no UI state, no Swing dependencies.  Callers that want to
 *  drive it from a unit test can construct the inputs by hand and
 *  inspect the returned [WriteOutcome] without standing up a frame.
 */
object ComparisonReportRenderer {

    /** Result of a [render] call.  Lets the caller surface per-format
     *  successes and errors in one notification pass. */
    data class WriteOutcome(
        val written: List<Path>,
        val errors: List<String>
    )

    // ── Per-analysis renderers ───────────────────────────────────────────
    //
    // Each per-analysis dialog calls its specific renderer directly,
    // passing the observation map it gathered from the selection
    // model.  There is no longer a model-driven convenience overload
    // — analysis-specific knobs (CL, indifference δ, title, etc.)
    // arrive through the per-analysis signature.

    /**
     *  Render a cross-experiment box plot for [responseName].
     *
     *  Iteration order of [observations] is preserved end-to-end:
     *  the map's first key becomes the leftmost box, the last key
     *  the rightmost.  Callers driving this from the analyzer pass
     *  the LinkedHashMap returned by
     *  [ComparisonSelectionModel.gatherObservationsFor], which
     *  reflects the experiments column's top-to-bottom order.
     *
     *  @param caption  optional caption shown beneath the plot.
     *    `null` (the default) yields "Cross-experiment distributions
     *    — <response>".  Blank strings are treated as `null`.
     *  @param formats  output formats to write.  Empty produces an
     *    errors-only [WriteOutcome].
     */
    /**
     *  Render a cross-experiment box plot for [responseName].
     *
     *  Iteration order of [observations] is preserved end-to-end:
     *  the map's first key becomes the leftmost box, the last key
     *  the rightmost.  Callers driving this from the analyzer pass
     *  the LinkedHashMap returned by
     *  [ComparisonSelectionModel.gatherObservationsFor], which
     *  reflects the experiments column's top-to-bottom order.
     *
     *  @param caption  optional caption shown beneath the plot.
     *    `null` (the default) yields "Cross-experiment distributions
     *    — <response>".  Blank strings are treated as `null`.
     *  @param xAxisLabel optional override for the x-axis label.
     *    Defaults to `"Experiment"`.
     *  @param yAxisLabel optional override for the y-axis label.
     *    Defaults to [responseName].
     *  @param formats  output formats to write.  Empty produces an
     *    errors-only [WriteOutcome].
     */
    fun renderBoxPlot(
        sourceLabel: String,
        responseName: String,
        observations: Map<String, DoubleArray>,
        outputDir: Path,
        formats: Set<ReportFormat>,
        caption: String? = null,
        xAxisLabel: String? = null,
        yAxisLabel: String? = null
    ): WriteOutcome {
        if (formats.isEmpty()) {
            return WriteOutcome(emptyList(), listOf("No report formats selected."))
        }
        if (observations.isEmpty()) {
            return WriteOutcome(
                emptyList(),
                listOf("No checked experiment records '$responseName'.")
            )
        }
        val resolvedCaption = caption?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Cross-experiment distributions — $responseName"
        // The substrate's multiBoxPlot extension accepts axis label
        // overrides (added when the comparison analyzer landed); pass
        // through ours with sensible defaults appropriate to the
        // analyzer's mental model: x = "Experiment", y = response name.
        val doc = report("Comparison — Box Plot — $responseName") {
            paragraph(headerSentence(sourceLabel, observations, responseName))
            multiBoxPlot(
                dataMap = observations,
                caption = resolvedCaption,
                xAxisLabel = xAxisLabel?.trim()?.takeIf { it.isNotEmpty() } ?: "Experiment",
                yAxisLabel = yAxisLabel?.trim()?.takeIf { it.isNotEmpty() } ?: responseName
            )
        }
        return writeAll(doc, outputDir, fileStem("comparison-boxplot", responseName), formats)
    }

    /**
     *  Render a Multiple Comparison Analysis report for
     *  [responseName] against [observations].
     *
     *  @param direction              [MCBDirection] for the MCB
     *    intervals (default [MCBDirection.BOTH]).
     *  @param indifferenceZone       δ for MCB (default 0.0).
     *  @param altConfidenceLevel     CL for the per-alternative CIs
     *    (default 0.95).
     *  @param diffConfidenceLevel    CL for the pairwise-difference
     *    CIs (default 0.95).
     *  @param probCorrectSelection   target probability of correct
     *    selection (default 0.95).
     *  @param showAltCIPlot          embed the per-alternative CI
     *    plot in the report (default `false`).
     *  @param showBoxPlot            embed the cross-alternative box
     *    plot in the report (default `false`).
     *  @param title                  optional report title;
     *    blank / null falls back to
     *    `"Comparison — Multiple Comparison — <response>"`.
     *  @param xAxisLabel             optional override for the x-axis
     *    label of every embedded plot.  Defaults to `"Experiment"`.
     *  @param yAxisLabel             optional override for the y-axis
     *    label of every embedded plot.  Defaults to [responseName].
     */
    fun renderMca(
        sourceLabel: String,
        responseName: String,
        observations: Map<String, DoubleArray>,
        outputDir: Path,
        formats: Set<ReportFormat>,
        direction: MCBDirection = MCBDirection.BOTH,
        indifferenceZone: Double = 0.0,
        altConfidenceLevel: Double = 0.95,
        diffConfidenceLevel: Double = 0.95,
        probCorrectSelection: Double = 0.95,
        showAltCIPlot: Boolean = false,
        showBoxPlot: Boolean = false,
        title: String? = null,
        xAxisLabel: String? = null,
        yAxisLabel: String? = null
    ): WriteOutcome {
        if (formats.isEmpty()) {
            return WriteOutcome(emptyList(), listOf("No report formats selected."))
        }
        // MCA constructor asserts ≥2 alternatives and equal lengths;
        // ComparisonSelectionModel.validateForResponse enforces both
        // already, but defend against direct callers that bypassed
        // the model.
        if (observations.size < 2) {
            return WriteOutcome(
                emptyList(),
                listOf("Multiple Comparison needs at least 2 experiments (have ${observations.size}).")
            )
        }
        val lengths = observations.values.map { it.size }.distinct()
        if (lengths.size != 1) {
            return WriteOutcome(
                emptyList(),
                listOf(
                    "Multiple Comparison needs equal replication counts; got " +
                        lengths.sorted().joinToString(", ") + "."
                )
            )
        }
        if (lengths.single() < 2) {
            return WriteOutcome(
                emptyList(),
                listOf("Multiple Comparison needs at least 2 replications per experiment.")
            )
        }
        val mca = MultipleComparisonAnalyzer(observations, responseName)
        val resolvedTitle = title?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Comparison — Multiple Comparison — $responseName"
        val doc = report(resolvedTitle) {
            paragraph(headerSentence(sourceLabel, observations, responseName))
            multipleComparison(
                mca = mca,
                direction = direction,
                indifferenceZone = indifferenceZone,
                altConfidenceLevel = altConfidenceLevel,
                diffConfidenceLevel = diffConfidenceLevel,
                probCorrectSelection = probCorrectSelection,
                showAltCIPlot = showAltCIPlot,
                showBoxPlot = showBoxPlot,
                xAxisLabel = xAxisLabel?.trim()?.takeIf { it.isNotEmpty() } ?: "Experiment",
                yAxisLabel = yAxisLabel?.trim()?.takeIf { it.isNotEmpty() } ?: responseName
            )
        }
        return writeAll(doc, outputDir, fileStem("comparison-mca", responseName), formats)
    }

    /**
     *  Render a side-by-side CI plot for [responseName] — one mean ±
     *  CI bar per checked experiment that records the response.
     *
     *  @param level           confidence level for the CIs (default
     *    0.95).  Each alternative's CI is computed from its own
     *    [observations] via [Statistic.confidenceIntervals], so
     *    unequal replication counts are allowed (unlike MCA).
     *  @param referencePoint  optional vertical reference line on
     *    the value axis (e.g. a target throughput or known
     *    theoretical mean).  `null` (the default) suppresses it.
     *  @param caption         optional plot caption.  Blank / `null`
     *    falls back to `"Mean ± <CL>% CI — <response>"`.
     *  @param title           optional report title.  Blank / `null`
     *    falls back to `"Comparison — Confidence Intervals — <response>"`.
     *  @param xAxisLabel      optional override for the x-axis label
     *    (the value axis).  Defaults to [responseName].
     *  @param yAxisLabel      optional override for the y-axis label
     *    (the alternative axis).  Defaults to `"Experiment"`.
     */
    fun renderCiPlot(
        sourceLabel: String,
        responseName: String,
        observations: Map<String, DoubleArray>,
        outputDir: Path,
        formats: Set<ReportFormat>,
        level: Double = 0.95,
        referencePoint: Double? = null,
        caption: String? = null,
        title: String? = null,
        xAxisLabel: String? = null,
        yAxisLabel: String? = null
    ): WriteOutcome {
        if (formats.isEmpty()) {
            return WriteOutcome(emptyList(), listOf("No report formats selected."))
        }
        if (observations.isEmpty()) {
            return WriteOutcome(
                emptyList(),
                listOf("No checked experiment records '$responseName'.")
            )
        }
        // CI plot via the Statistic-based ConfidenceIntervalsPlot
        // constructor — does not require equal rep counts (Statistic
        // computes each alternative's CI from its own data), only
        // ≥2 observations per alternative so the CI is defined.
        val tooSmall = observations.filterValues { it.size < 2 }.keys
        if (tooSmall.isNotEmpty()) {
            return WriteOutcome(
                emptyList(),
                listOf(
                    "Confidence Intervals need at least 2 replications per experiment; " +
                        "too few for: ${tooSmall.joinToString(", ")}."
                )
            )
        }
        val resolvedTitle = title?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Comparison — Confidence Intervals — $responseName"
        val percent = (level * 100).let {
            // Render 95.0 as "95"; 97.5 as "97.5".
            if (it == it.toInt().toDouble()) it.toInt().toString() else it.toString()
        }
        val resolvedCaption = caption?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Mean ± $percent% CI — $responseName"
        val ciPlot = ConfidenceIntervalsPlot(
            data = observations,
            level = level,
            referencePoint = referencePoint
        ).apply {
            xLabel = xAxisLabel?.trim()?.takeIf { it.isNotEmpty() } ?: responseName
            yLabel = yAxisLabel?.trim()?.takeIf { it.isNotEmpty() } ?: "Experiment"
        }
        val doc = report(resolvedTitle) {
            paragraph(headerSentence(sourceLabel, observations, responseName))
            plot(ciPlot, caption = resolvedCaption)
        }
        return writeAll(doc, outputDir, fileStem("comparison-ciplot", responseName), formats)
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun headerSentence(
        sourceLabel: String,
        observations: Map<String, DoubleArray>,
        responseName: String
    ): String {
        val n = observations.size
        return "Source: $sourceLabel.  Comparing response '$responseName' across " +
            "$n experiment${if (n == 1) "" else "s"}."
    }

    private fun writeAll(
        doc: ReportNode.Document,
        outputDir: Path,
        stem: String,
        formats: Set<ReportFormat>
    ): WriteOutcome {
        Files.createDirectories(outputDir)
        val written = mutableListOf<Path>()
        val errors = mutableListOf<String>()
        var htmlPath: Path? = null
        for (fmt in formats) {
            try {
                val ext = when (fmt) {
                    ReportFormat.HTML -> "html"
                    ReportFormat.MARKDOWN -> "md"
                    ReportFormat.TEXT -> "txt"
                }
                val path = outputDir.resolve("$stem.$ext")
                when (fmt) {
                    ReportFormat.HTML -> {
                        doc.writeHtml(path = path)
                        htmlPath = path
                    }
                    ReportFormat.MARKDOWN -> doc.writeMarkdown(path = path)
                    ReportFormat.TEXT -> doc.writeText(path = path)
                }
                written.add(path)
            } catch (t: Throwable) {
                errors.add("${fmt.name}: ${t.message ?: t::class.simpleName ?: "unknown error"}")
            }
        }
        if (htmlPath != null) {
            try {
                openInBrowser(htmlPath)
            } catch (t: Throwable) {
                errors.add("Browser open: ${t.message ?: t::class.simpleName ?: "unknown error"}")
            }
        }
        return WriteOutcome(written, errors)
    }

    private fun openInBrowser(htmlPath: Path) {
        if (!java.awt.Desktop.isDesktopSupported()) {
            throw UnsupportedOperationException("Desktop browser open is not supported on this platform.")
        }
        val desktop = java.awt.Desktop.getDesktop()
        if (!desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
            throw UnsupportedOperationException("Browser action is not supported on this platform.")
        }
        desktop.browse(htmlPath.toUri())
    }

    private fun fileStem(prefix: String, key: String): String {
        val sanitised = key.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
        return "$prefix-$sanitised"
    }
}
