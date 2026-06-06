/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
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

package ksl.utilities.io.report.extensions

import ksl.utilities.io.dbutil.ExperimentTableData
import ksl.utilities.io.dbutil.FrequencyTableData
import ksl.utilities.io.dbutil.HistogramTableData
import ksl.utilities.io.dbutil.KSLDatabase
import ksl.utilities.io.plotting.DbHistogramPlotData
import ksl.utilities.io.plotting.DbIntegerFrequencyPlotData
import ksl.utilities.io.plotting.HistogramPlot
import ksl.utilities.io.plotting.IntegerFrequencyPlot
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.ReportBuilder
import ksl.utilities.io.report.dsl.report
import ksl.utilities.statistic.FrequencyData
import ksl.utilities.statistic.HistogramBinData
import ksl.utilities.statistic.Statistic

/**
 * DSL extension functions on [ReportBuilder] for rendering simulation output stored in a
 * [KSLDatabase].
 *
 * These extensions produce reports that are structurally equivalent to the live-simulation
 * reports built by [SimulationReportExtensions], but sourced entirely from the database.
 * This enables post-hoc report generation from stored simulation results without
 * requiring the originating [ksl.simulation.Model] to be re-run.
 *
 * **Typical usage — single experiment:**
 * ```kotlin
 * val db = KSLDatabase("pharmacy.db")
 * db.toReport("Experiment 1").showInBrowser()
 * ```
 *
 * **Typical usage — all experiments in one document:**
 * ```kotlin
 * db.toReport().showInBrowser()
 * ```
 *
 * **DSL composition — custom report with additional sections:**
 * ```kotlin
 * val doc = report("Custom Analysis") {
 *     dbSimulationResults(db, "Experiment 1")
 *     section("Commentary") { paragraph("Results look good.") }
 * }
 * doc.showInBrowser()
 * ```
 */

// ── Experiment configuration ──────────────────────────────────────────────────

/**
 * Appends a "Experiment Configuration" data table derived from [ExperimentTableData]
 * and a derived replication count.
 *
 * The table uses the same "Property | Value" layout as [simulationSummary] so that
 * database-sourced and live-simulation reports look identical.
 *
 * @param exp     the experiment record to display
 * @param numReps number of replications completed (derived from per-replication data)
 */
fun ReportBuilder.dbExperimentConfig(exp: ExperimentTableData, numReps: Int) {
    dataTable(
        headers = listOf("Property", "Value"),
        rows = listOf(
            listOf("Simulation Name",   exp.sim_name),
            listOf("Model Name",        exp.model_name),
            listOf("Experiment Name",   exp.exp_name),
            listOf("Replications",      numReps.toString()),
            listOf("Run Length",        exp.length_of_rep?.toString() ?: "—"),
            listOf("Warm-Up Length",    exp.length_of_warm_up?.toString() ?: "—"),
            listOf("Number of Chunks",  exp.num_chunks.toString()),
            listOf("Rep Init Option",   exp.rep_init_option.toString()),
            listOf("Antithetic",        exp.antithetic_option.toString()),
            listOf("Adv Sub-Stream",    exp.adv_next_sub_stream_option.toString()),
            listOf("Stream Advances",   exp.num_stream_advances.toString()),
            listOf("GC After Rep",      exp.gc_after_rep_option.toString())
        ),
        caption = "Experiment Configuration"
    )
}

// ── Simulation summary (metadata + statistics table) ─────────────────────────

/**
 * Appends a "Simulation Summary" section for the named experiment, containing:
 * 1. An experiment-configuration data table (from [dbExperimentConfig]).
 * 2. An across-replication statistics half-width summary table built from the
 *    per-replication observations stored in the database.
 *
 * The per-replication observations are retrieved via
 * `db.replicationDataArraysByExperimentAndResponse()`, and a [Statistic] is
 * constructed for each response, matching the format produced by [simulationSummary].
 *
 * Does nothing (emits a paragraph) if the experiment is not found or has no data.
 *
 * @param db              the [KSLDatabase] to query
 * @param expName         the experiment name to report
 * @param confidenceLevel confidence level for the half-width summary table
 */
fun ReportBuilder.dbSimulationSummary(
    db: KSLDatabase,
    expName: String,
    confidenceLevel: Double = 0.95
) {
    section("Simulation Summary") {
        val expRecord = db.fetchExperimentData(expName)
        if (expRecord == null) {
            paragraph("Experiment '$expName' was not found in the database.")
            return@section
        }

        // ── Across-replication data arrays ────────────────────────────────────
        val repArraysByExp = db.replicationDataArraysByExperimentAndResponse()
        val repArrays = repArraysByExp[expName]
        val numReps = repArrays?.values?.firstOrNull()?.size ?: 0

        dbExperimentConfig(expRecord, numReps)

        if (repArrays == null || repArrays.isEmpty()) {
            paragraph("No across-replication statistics available for experiment '$expName'.")
            return@section
        }

        // Build one Statistic per response from per-replication observations
        val stats = repArrays.entries
            .sortedBy { it.key }
            .mapNotNull { (responseName, values) ->
                val nonNan = values.filter { !it.isNaN() }.toDoubleArray()
                if (nonNan.isEmpty()) null else Statistic(responseName, nonNan)
            }

        if (stats.isEmpty()) {
            paragraph("All replication values were missing for experiment '$expName'.")
        } else {
            statTable(
                stats = stats,
                caption = "Across-Replication Statistics",
                confidenceLevel = confidenceLevel
            )
        }
    }
}

// ── Histograms ────────────────────────────────────────────────────────────────

/**
 * Appends a "Histograms" section for the named experiment.
 *
 * Each distinct response name in `tblHistogram` for this experiment produces one
 * sub-section containing a bin-frequency data table and a histogram bar-chart plot.
 *
 * The section is silently omitted when no histogram data exists for the experiment.
 *
 * @param db      the [KSLDatabase] to query
 * @param expName the experiment name whose histograms should be reported
 * @param showPlot when `true` (default) a histogram plot is appended in each sub-section
 */
fun ReportBuilder.dbSimulationHistograms(
    db: KSLDatabase,
    expName: String,
    showPlot: Boolean = true
) {
    val rows = db.histogramDataFor(expName)
    if (rows.isEmpty()) return

    section("Histograms") {
        // Group by response name; preserve insertion order
        val byResponse = rows.groupBy { it.response_name }
        for ((responseName, binRows) in byResponse) {
            dbHistogram(responseName, binRows, showPlot = showPlot)
        }
    }
}

/**
 * Appends a single histogram sub-section for one response from the database.
 *
 * **Produces (inside a section titled [responseName]):**
 * 1. A data table ("Bin Frequencies") with columns: Bin | Label | Lower | Upper |
 *    Count | Cum Count | % | Cum %
 * 2. A histogram bar-chart plot (when [showPlot] is `true`)
 *
 * Note: unlike the live-simulation [histogram] extension, no across-replication
 * statistics property sheet is included because the histogram bins are stored
 * aggregated across replications (one histogram per simulation run) rather than
 * as raw observations.
 *
 * @param responseName the response name used as the section title
 * @param binRows      the [HistogramTableData] rows for this response; must be non-empty
 * @param caption      optional section title override; defaults to [responseName]
 * @param showPlot     when `true` (default) a bar-chart plot is appended
 */
fun ReportBuilder.dbHistogram(
    responseName: String,
    binRows: List<HistogramTableData>,
    caption: String? = null,
    showPlot: Boolean = true
) {
    val myTitle = caption ?: responseName
    section(myTitle) {
        val mySorted = binRows.sortedBy { it.bin_num }

        // ── Overview paragraph ────────────────────────────────────────────────
        val totalCount = mySorted.sumOf { it.bin_count ?: 0.0 }.toInt()
        val lower = mySorted.firstOrNull()?.bin_lower_limit
        val upper = mySorted.lastOrNull()?.bin_upper_limit
        val lowerStr = if (lower == null) "—" else dbFormatLimit(lower)
        val upperStr = if (upper == null) "—" else dbFormatLimit(upper)
        paragraph(
            "Bins: ${mySorted.size}  |  Range: [$lowerStr, $upperStr]  |  Total in Bins: $totalCount"
        )

        // ── Bin frequency table ───────────────────────────────────────────────
        val myHeaders = listOf("Bin", "Label", "Lower", "Upper", "Count", "Cum Count", "%", "Cum %")
        val myRows = mySorted.map { b ->
            listOf(
                b.bin_num.toString(),
                b.bin_label,
                if (b.bin_lower_limit == null) "—" else dbFormatLimit(b.bin_lower_limit!!),
                if (b.bin_upper_limit == null) "—" else dbFormatLimit(b.bin_upper_limit!!),
                (b.bin_count ?: 0.0).toInt().toString(),
                (b.bin_cum_count ?: 0.0).toInt().toString(),
                dbFormatPct(b.bin_proportion ?: 0.0),
                dbFormatPct(b.bin_cum_proportion ?: 0.0)
            )
        }
        dataTable(myHeaders, myRows, caption = "Bin Frequencies")

        // ── Histogram plot ────────────────────────────────────────────────────
        if (showPlot && mySorted.isNotEmpty()) {
            val binData = mySorted.map { r ->
                HistogramBinData(
                    id             = r.id,
                    name           = r.response_name,
                    binNum         = r.bin_num,
                    binLabel       = r.bin_label,
                    binLowerLimit  = r.bin_lower_limit ?: 0.0,
                    binUpperLimit  = r.bin_upper_limit ?: 0.0,
                    binCount       = r.bin_count ?: 0.0,
                    cumCount       = r.bin_cum_count ?: 0.0,
                    proportion     = r.bin_proportion ?: 0.0,
                    cumProportion  = r.bin_cum_proportion ?: 0.0
                )
            }
            val plotData = DbHistogramPlotData(binData)
            val histPlot = HistogramPlot(plotData)
            histPlot.title = myTitle
            plot(histPlot, caption = myTitle)
        }
    }
}

// ── Frequencies ───────────────────────────────────────────────────────────────

/**
 * Appends a "Frequencies" section for the named experiment.
 *
 * Each distinct frequency name in `tblFrequency` for this experiment produces one
 * sub-section containing a frequency data table and a bar-chart plot.
 *
 * The section is silently omitted when no frequency data exists for the experiment.
 *
 * @param db      the [KSLDatabase] to query
 * @param expName the experiment name whose frequencies should be reported
 * @param showPlot when `true` (default) a frequency bar-chart plot is appended in each sub-section
 */
fun ReportBuilder.dbSimulationFrequencies(
    db: KSLDatabase,
    expName: String,
    showPlot: Boolean = true
) {
    val rows = db.frequencyDataFor(expName)
    if (rows.isEmpty()) return

    section("Frequencies") {
        val byName = rows.groupBy { it.name }
        for ((freqName, cellRows) in byName) {
            dbIntegerFrequency(freqName, cellRows, showPlot = showPlot)
        }
    }
}

/**
 * Appends a single integer-frequency sub-section for one response from the database.
 *
 * **Produces (inside a section titled [freqName]):**
 * 1. A data table ("Frequency Table") with columns: Value | Label | Count | Cum Count | % | Cum %
 * 2. A frequency bar-chart plot (when [showPlot] is `true`)
 *
 * @param freqName  the frequency response name used as the section title
 * @param cellRows  the [FrequencyTableData] rows for this response; must be non-empty
 * @param caption   optional section title override; defaults to [freqName]
 * @param showPlot  when `true` (default) a bar-chart plot is appended
 */
fun ReportBuilder.dbIntegerFrequency(
    freqName: String,
    cellRows: List<FrequencyTableData>,
    caption: String? = null,
    showPlot: Boolean = true
) {
    val myTitle = caption ?: freqName
    section(myTitle) {
        val mySorted = cellRows.sortedBy { it.value }

        // ── Overview paragraph ────────────────────────────────────────────────
        val totalCount = mySorted.sumOf { it.count ?: 0.0 }.toInt()
        val minVal = mySorted.firstOrNull()?.value
        val maxVal = mySorted.lastOrNull()?.value
        val rangeStr = if (minVal != null && maxVal != null) "$minVal–$maxVal" else "—"
        paragraph(
            "Values: $rangeStr  |  Distinct: ${mySorted.size}  |  Total: $totalCount"
        )

        // ── Frequency table ───────────────────────────────────────────────────
        val myHeaders = listOf("Value", "Label", "Count", "Cum Count", "%", "Cum %")
        val myRows = mySorted.map { f ->
            listOf(
                f.value.toString(),
                f.cell_label,
                (f.count ?: 0.0).toInt().toString(),
                (f.cum_count ?: 0.0).toInt().toString(),
                dbFormatPct(f.proportion ?: 0.0),
                dbFormatPct(f.cum_proportion ?: 0.0)
            )
        }
        dataTable(myHeaders, myRows, caption = "Frequency Table")

        // ── Frequency plot ────────────────────────────────────────────────────
        if (showPlot && mySorted.isNotEmpty()) {
            val freqData = mySorted.map { r ->
                FrequencyData(
                    id            = r.id,
                    name          = r.name,
                    cellLabel     = r.cell_label,
                    value         = r.value,
                    count         = r.count ?: 0.0,
                    cum_count     = r.cum_count ?: 0.0,
                    proportion    = r.proportion ?: 0.0,
                    cumProportion = r.cum_proportion ?: 0.0
                )
            }
            val plotData = DbIntegerFrequencyPlotData(freqData)
            val freqPlot = IntegerFrequencyPlot(plotData)
            freqPlot.title = myTitle
            plot(freqPlot, caption = myTitle)
        }
    }
}

// ── Composite experiment results ──────────────────────────────────────────────

/**
 * Appends a complete set of sections for the named experiment in the following order:
 * 1. **Simulation Summary** — experiment metadata and across-replication statistics table
 * 2. **Histograms** — one sub-section per response with histogram data (omitted if none)
 * 3. **Frequencies** — one sub-section per response with frequency data (omitted if none)
 *
 * This is the DB equivalent of [simulationResults].
 *
 * Usage:
 * ```kotlin
 * val doc = report("Pharmacy Study") {
 *     dbSimulationResults(db, "Experiment 1")
 * }
 * doc.showInBrowser()
 * ```
 *
 * @param db              the [KSLDatabase] to query
 * @param expName         the experiment name to report
 * @param confidenceLevel confidence level for all statistical tables
 * @param showPlots       when `true` (default) histogram and frequency plots are included
 */
fun ReportBuilder.dbSimulationResults(
    db: KSLDatabase,
    expName: String,
    confidenceLevel: Double = 0.95,
    showPlots: Boolean = true
) {
    dbSimulationSummary(db, expName, confidenceLevel)
    dbSimulationHistograms(db, expName, showPlot = showPlots)
    dbSimulationFrequencies(db, expName, showPlot = showPlots)
}

// ── KSLDatabase.toReport() — zero-code entry points ──────────────────────────

/**
 * Builds a [ReportNode.Document] for a single named experiment in this database.
 *
 * The default content is equivalent to calling [dbSimulationResults] for the named
 * experiment: experiment configuration, across-replication statistics table, histograms
 * (if any), and frequency distributions (if any).
 *
 * Zero-code path:
 * ```kotlin
 * val db = KSLDatabase("pharmacy.db")
 * db.toReport("Experiment 1").showInBrowser()
 * db.toReport("Experiment 1").writeMarkdown()
 * ```
 *
 * Custom block **replaces** the default — call [dbSimulationResults] inside the block
 * to include the standard sections alongside additional custom content:
 * ```kotlin
 * db.toReport("Experiment 1", title = "Pharmacy Study") {
 *     dbSimulationResults(this@toReport, "Experiment 1")
 *     section("Custom Notes") { paragraph("Analysis complete.") }
 * }
 * ```
 *
 * @param expName         the experiment name to report
 * @param title           document title; defaults to the experiment name
 * @param confidenceLevel confidence level for all statistical tables; must be in (0, 1)
 * @param showPlots       when `true` (default) histogram and frequency plots are included
 * @param block           optional DSL block; replaces the default when provided
 * @return the assembled [ReportNode.Document]
 */
fun KSLDatabase.toReport(
    expName: String,
    title: String = expName,
    confidenceLevel: Double = 0.95,
    showPlots: Boolean = true,
    block: ReportBuilder.() -> Unit = {
        dbSimulationResults(this@toReport, expName, confidenceLevel, showPlots)
    }
): ReportNode.Document = report(title, block)

/**
 * Builds a [ReportNode.Document] covering **all** experiments stored in this database.
 *
 * Each experiment appears as a top-level section inside the document, produced by
 * [dbSimulationResults]. Experiments are reported in the order returned by
 * [KSLDatabase.experimentNames].
 *
 * Zero-code path:
 * ```kotlin
 * val db = KSLDatabase("pharmacy.db")
 * db.toReport().showInBrowser()
 * db.toReport().writeMarkdown()
 * ```
 *
 * When the database contains no experiments a single paragraph noting this is emitted.
 *
 * @param title           document title; defaults to the database label
 * @param confidenceLevel confidence level for all statistical tables; must be in (0, 1)
 * @param showPlots       when `true` (default) histogram and frequency plots are included
 * @return the assembled [ReportNode.Document]
 */
fun KSLDatabase.toReport(
    title: String = label,
    confidenceLevel: Double = 0.95,
    showPlots: Boolean = true
): ReportNode.Document = report(title) {
    val names = experimentNames
    if (names.isEmpty()) {
        paragraph("No experiments found in database '$label'.")
    } else {
        for (expName in names) {
            section(expName) {
                dbSimulationResults(this@toReport, expName, confidenceLevel, showPlots)
            }
        }
    }
}

// ── Cross-experiment comparison ───────────────────────────────────────────────

/**
 * Builds a [ReportNode.Document] containing a complete multiple-comparison
 * analysis of one response across several experiments stored in this database.
 *
 * This is the cross-experiment counterpart to the single-experiment `toReport`
 * entry points above.  It composes two pieces of existing KSL infrastructure:
 * `multipleComparisonAnalyzerFor` (which builds a `MultipleComparisonAnalyzer`
 * from the per-replication values stored for the named experiments) and the
 * [multipleComparison] report section (which renders alternative statistics,
 * pairwise differences, MCB max/min intervals with confidence-interval plots,
 * and screening results).  No statistics are recomputed here and no HTML is
 * hand-built; the document is assembled entirely from the report DSL.
 *
 * Zero-code path:
 * ```kotlin
 * val db = KSLDatabase("pharmacy.db")
 * db.toComparisonReport("SystemTime", listOf("Base", "AddServer", "AddKiosk"))
 *     .showInBrowser()
 * db.toComparisonReport("SystemTime", listOf("Base", "AddServer"))
 *     .writeMarkdown()
 * ```
 *
 * All experiments named in [expNames] must record [responseName] with equal
 * replication counts — the underlying `MultipleComparisonAnalyzer` requires
 * this.  An `IllegalArgumentException` is thrown when an experiment name is not
 * present in the database (propagated from `multipleComparisonAnalyzerFor`).
 *
 * @param responseName         the response, time-weighted, or counter name to compare
 * @param expNames             the experiments to compare; each must be present in the database
 * @param title                document title; defaults to "Comparison — <responseName>"
 * @param direction            which MCB direction(s) to render; default [MCBDirection.BOTH]
 * @param indifferenceZone     delta for MCB interval construction; `null` (default) uses the
 *                             analyzer's own default indifference zone
 * @param confidenceLevel      confidence level for the alternative and pairwise-difference
 *                             tables; must be in (0, 1); default 0.95
 * @param probCorrectSelection probability of correct selection for screening; default 0.95
 * @param showAltCIPlot        when `true` (default) include the per-alternative
 *                             confidence-interval plot
 * @param showBoxPlot          when `true` (default) include the per-alternative box plot
 * @return the assembled [ReportNode.Document]
 */
fun KSLDatabase.toComparisonReport(
    responseName: String,
    expNames: List<String>,
    title: String = "Comparison — $responseName",
    direction: MCBDirection = MCBDirection.BOTH,
    indifferenceZone: Double? = null,
    confidenceLevel: Double = 0.95,
    probCorrectSelection: Double = 0.95,
    showAltCIPlot: Boolean = true,
    showBoxPlot: Boolean = true
): ReportNode.Document {
    val mca = multipleComparisonAnalyzerFor(expNames, responseName)
    val delta = indifferenceZone ?: mca.defaultIndifferenceZone
    return report(title) {
        multipleComparison(
            mca = mca,
            direction = direction,
            indifferenceZone = delta,
            altConfidenceLevel = confidenceLevel,
            diffConfidenceLevel = confidenceLevel,
            probCorrectSelection = probCorrectSelection,
            showAltCIPlot = showAltCIPlot,
            showBoxPlot = showBoxPlot
        )
    }
}

// ── Private formatting helpers ────────────────────────────────────────────────

/**
 * Formats a bin limit for display.  Infinite and very-large values are rendered as
 * the appropriate infinity symbol; NaN as an em-dash; all other values with four
 * significant figures.
 */
private fun dbFormatLimit(value: Double): String = when {
    value == Double.NEGATIVE_INFINITY || value == -Double.MAX_VALUE -> "−∞"
    value == Double.POSITIVE_INFINITY || value == Double.MAX_VALUE  -> "+∞"
    value.isNaN() -> "—"
    else -> "%.4g".format(value)
}

/**
 * Formats a proportion (in [0, 1]) as a percentage string with two decimal places.
 * Returns `"—"` for NaN or infinite values.
 */
private fun dbFormatPct(value: Double): String = when {
    value.isNaN() || value.isInfinite() -> "—"
    else -> "%.2f%%".format(value * 100.0)
}
