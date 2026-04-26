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
import ksl.utilities.io.tabularfiles.TabularFile
import ksl.utilities.io.tabularfiles.TabularInputFile
import ksl.utilities.io.tabularfiles.TabularOutputFile
import ksl.utilities.statistic.Histogram
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.StringFrequency

/**
 * DSL extension functions on [ReportBuilder] for rendering [TabularFile],
 * [TabularInputFile], and [TabularOutputFile] instances.
 *
 * These extensions produce reports that are structurally consistent with
 * `HistogramReportExtensions` and `StatisticReportExtensions`: extensions on
 * [ReportBuilder] for composable content, then zero-code `toReport()` entry
 * points on the file types themselves.
 *
 * **Typical usage — zero-code:**
 * ```kotlin
 * val tif = TabularInputFile(path)
 * tif.toReport().showInBrowser()
 * tif.toReport().writeMarkdown()
 *
 * val tof = TabularOutputFile(columns, path)
 * tof.toReport().showInBrowser()
 * ```
 *
 * **DSL composition — custom reports:**
 * ```kotlin
 * val doc = report("My Analysis") {
 *     tabularInputFileResults(tif, maxRows = 1000)
 *     section("Notes") { paragraph("Pre-processing complete.") }
 * }
 * doc.showInBrowser()
 * ```
 *
 * **Content functions:**
 * - [tabularFileSchema]           — column schema table; works on any [TabularFile]
 * - [tabularInputFileSummary]     — schema + compact stats table + text-column summary
 * - [tabularInputFileColumns]     — per-column drill-down with histograms and value frequencies
 * - [tabularInputFileResults]     — composite: summary + column detail (canonical full report)
 */

// ── Schema ────────────────────────────────────────────────────────────────────

/**
 * Appends a "Schema" section describing the column structure of any [TabularFile].
 *
 * This is the only content function that works on both [TabularInputFile] and
 * [TabularOutputFile], because schema metadata is available on the base class
 * regardless of read/write direction.
 *
 * **Produces (inside a section titled [caption] or `"Schema: <filename>"`):**
 * 1. A [ReportNode.Paragraph] summarising total, numeric, and text column counts.
 * 2. A [ReportNode.DataTable] ("Column Schema") with columns:
 *    Index | Column Name | Type | Numeric? | Text?
 *
 * Usage:
 * ```kotlin
 * val doc = report("File Structure") {
 *     tabularFileSchema(myOutputFile)
 * }
 * ```
 *
 * @param file    the tabular file whose schema is reported
 * @param caption optional section title; defaults to `"Schema: <filename>"`
 */
fun ReportBuilder.tabularFileSchema(
    file: TabularFile,
    caption: String? = null
) {
    val myTitle = caption ?: "Schema: ${file.path.fileName}"
    section(myTitle) {
        paragraph(
            "Columns: ${file.numberColumns}  |  " +
            "Numeric: ${file.numNumericColumns}  |  " +
            "Text: ${file.numTextColumns}"
        )
        val myRows = file.columnNames.mapIndexed { i, name ->
            listOf(
                i.toString(),
                name,
                file.dataTypes[i].name
            )
        }
        dataTable(
            headers = listOf("Index", "Column Name", "Type"),
            rows    = myRows,
            caption = "Column Schema"
        )
    }
}

// ── Summary ───────────────────────────────────────────────────────────────────

/**
 * Appends a "Summary" section for a [TabularInputFile] containing the column
 * schema, a compact across-column statistics table for all numeric columns,
 * and a distinct/missing count table for all text columns.
 *
 * At most [maxRows] rows are read from the file per column. Pass `0` to read
 * all rows (subject to available memory).
 *
 * **Produces (inside a section titled [caption] or `"Summary: <filename>"`):**
 * 1. [tabularFileSchema] sub-section.
 * 2. A [ReportNode.Paragraph] stating total row count and how many are reported.
 * 3. *(if numeric columns exist)* A [ReportNode.StatTable] ("Numeric Column Statistics")
 *    — one row per numeric column, built from a [Statistic] over the fetched values.
 *    NaN values in the file are preserved in the array and counted as Missing by
 *    [Statistic] without affecting the other summary fields.
 * 4. *(if text columns exist)* A [ReportNode.DataTable] ("Text Column Summary")
 *    — columns: Column | Count | Distinct | Missing.
 *
 * Usage:
 * ```kotlin
 * val doc = report("Sales Data") {
 *     tabularInputFileSummary(tif, confidenceLevel = 0.90, detail = true)
 * }
 * ```
 *
 * @param file            the tabular input file to summarise
 * @param caption         optional section title; defaults to `"Summary: <filename>"`
 * @param maxRows         maximum rows read per column; `0` = all rows; defaults to 500
 * @param confidenceLevel confidence level for the [ReportNode.StatTable] half-width and CI;
 *                        must be in (0, 1); defaults to 0.95
 * @param detail          `false` (default) = compact summary only;
 *                        `true` = compact summary + diagnostic table
 */
fun ReportBuilder.tabularInputFileSummary(
    file: TabularInputFile,
    caption: String? = null,
    maxRows: Int = 500,
    confidenceLevel: Double = 0.95,
    detail: Boolean = false
) {
    val myTitle = caption ?: "Summary: ${file.path.fileName}"
    section(myTitle) {

        // ── Schema sub-section ────────────────────────────────────────────────
        tabularFileSchema(file)

        // ── Row count paragraph ───────────────────────────────────────────────
        paragraph(
            "Total rows: ${file.totalNumberRows}  |  " +
            "Reporting on ${file.rowsLabel(maxRows)}"
        )

        // ── Numeric column statistics ─────────────────────────────────────────
        if (file.numNumericColumns > 0) {
            val myStats = file.numericColumnNames.map { name ->
                val myValues = file.fetchNumericColumn(name, maxRows, removeMissing = false)
                Statistic(name, myValues)
            }
            statTable(
                stats           = myStats,
                caption         = "Numeric Column Statistics",
                confidenceLevel = confidenceLevel,
                detail          = detail
            )
        }

        // ── Text column summary ───────────────────────────────────────────────
        if (file.numTextColumns > 0) {
            val myRows = file.textColumnNames.map { name ->
                val myValues = file.fetchTextColumn(name, maxRows, removeMissing = false)
                val myDistinct = myValues.filterNotNull().toSet().size
                val myMissing  = myValues.count { it == null }
                listOf(name, myValues.size.toString(), myDistinct.toString(), myMissing.toString())
            }
            dataTable(
                headers = listOf("Column", "Count", "Distinct", "Missing"),
                rows    = myRows,
                caption = "Text Column Summary"
            )
        }
    }
}

// ── Column detail ─────────────────────────────────────────────────────────────

/**
 * Appends a "Columns" section for a [TabularInputFile] with a dedicated sub-section
 * for each column.
 *
 * **For each numeric column** the existing [histogram] DSL extension is called, which
 * produces — at no additional cost — an overview paragraph, a bin-frequency
 * [ReportNode.DataTable], an 18-property [ReportNode.StatPropertyTable] (including
 * the Missing count sourced from NaN values in the file), and optionally a
 * [ReportNode.PlotNode]. [Histogram.create] is called with the raw `DoubleArray`
 * (NaN values retained) so that `numberMissing` is populated correctly.
 *
 * **For each text column** the existing [stringFrequency] DSL extension is called, which
 * produces an overview paragraph, a frequency [ReportNode.DataTable] with columns
 * String | Count | Cum Count | % | Cum %, and optionally a [ReportNode.PlotNode] bar chart.
 * Null values in the file are mapped to `"(missing)"` so they appear as a tabulated
 * category rather than being silently dropped.
 *
 * At most [maxRows] rows are read from the file per column. Pass `0` to read all rows.
 *
 * Usage:
 * ```kotlin
 * val doc = report("Column Exploration") {
 *     tabularInputFileColumns(tif, maxRows = 1000, showPlots = false)
 * }
 * ```
 *
 * @param file            the tabular input file to detail
 * @param caption         optional section title; defaults to `"Columns: <filename>"`
 * @param maxRows         maximum rows read per column; `0` = all rows; defaults to 500
 * @param confidenceLevel confidence level for histogram [ReportNode.StatPropertyTable] CI
 * @param showPlots       when `true` (default) a [ReportNode.PlotNode] is appended in each
 *                        numeric column sub-section via [histogram]
 */
fun ReportBuilder.tabularInputFileColumns(
    file: TabularInputFile,
    caption: String? = null,
    maxRows: Int = 500,
    confidenceLevel: Double = 0.95,
    showPlots: Boolean = true
) {
    val myTitle = caption ?: "Columns: ${file.path.fileName}"
    section(myTitle) {
        paragraph(
            "Total rows: ${file.totalNumberRows}  |  " +
            "Reporting on ${file.rowsLabel(maxRows)}"
        )

        // ── Numeric columns — delegate entirely to histogram() DSL ────────────
        for (myName in file.numericColumnNames) {
            val myValues = file.fetchNumericColumn(myName, maxRows, removeMissing = false)
            val myHist   = Histogram.create(myValues, name = myName)
            histogram(
                h               = myHist,
                caption         = myName,
                confidenceLevel = confidenceLevel,
                showPlot        = showPlots
            )
        }

        // ── Text columns — delegate entirely to stringFrequency() DSL ──────────
        for (myName in file.textColumnNames) {
            val myValues = file.fetchTextColumn(myName, maxRows, removeMissing = false)
            val myFreq   = StringFrequency(
                data = myValues.map { it ?: "(missing)" },
                name = myName
            )
            stringFrequency(freq = myFreq, caption = myName, showPlot = showPlots)
        }
    }
}

// ── Composite ─────────────────────────────────────────────────────────────────

/**
 * Appends the canonical full report for a [TabularInputFile]: a summary section
 * followed by a per-column detail section.
 *
 * This is the DB-equivalent of [dbSimulationResults]: a single call that produces
 * the complete standard report structure. Both component sections can also be called
 * independently when finer control over layout is needed.
 *
 * **Produces in order:**
 * 1. [tabularInputFileSummary] — schema + compact stats + text summary
 * 2. [tabularInputFileColumns] — per-column drill-down with histograms / frequencies
 *
 * Usage:
 * ```kotlin
 * val doc = report("Sensor Readings") {
 *     tabularInputFileResults(tif, maxRows = 2000, detail = true)
 * }
 * doc.showInBrowser()
 * ```
 *
 * @param file            the tabular input file to report
 * @param maxRows         maximum rows read per column; `0` = all rows; defaults to 500
 * @param confidenceLevel confidence level for all statistical tables; must be in (0, 1)
 * @param detail          `true` appends a diagnostic table to the [tabularInputFileSummary]
 *                        stat table (skewness, kurtosis, Lag-1 correlation, Von Neumann)
 * @param showPlots       when `true` (default) histogram plots are included in the column
 *                        detail section
 */
fun ReportBuilder.tabularInputFileResults(
    file: TabularInputFile,
    maxRows: Int = 500,
    confidenceLevel: Double = 0.95,
    detail: Boolean = false,
    showPlots: Boolean = true
) {
    tabularInputFileSummary(
        file            = file,
        maxRows         = maxRows,
        confidenceLevel = confidenceLevel,
        detail          = detail
    )
    tabularInputFileColumns(
        file            = file,
        maxRows         = maxRows,
        confidenceLevel = confidenceLevel,
        showPlots       = showPlots
    )
}

// ── toReport() — zero-code entry points ──────────────────────────────────────

/**
 * Builds a [ReportNode.Document] for this [TabularOutputFile] whose default content
 * is a schema section ([tabularFileSchema]).
 *
 * Row data is not accessible from a [TabularOutputFile] (rows are write-only until
 * flushed and re-opened as a [TabularInputFile]), so the schema is the only safe
 * content. Convert to a [TabularInputFile] via [TabularOutputFile.asTabularInputFile]
 * if a full report including data is needed.
 *
 * Zero-code path:
 * ```kotlin
 * val tof = TabularOutputFile(columns, path)
 * tof.toReport().showInBrowser()
 * ```
 *
 * Custom block **replaces** the default:
 * ```kotlin
 * tof.toReport("Output Schema") {
 *     tabularFileSchema(this@toReport)
 *     paragraph("Written during pre-processing phase.")
 * }
 * ```
 *
 * @param title document title; defaults to the file name
 * @param block optional DSL block; replaces the default when provided
 * @return the assembled [ReportNode.Document]
 */
fun TabularOutputFile.toReport(
    title: String = path.fileName.toString(),
    block: ReportBuilder.() -> Unit = { tabularFileSchema(this@toReport) }
): ReportNode.Document = report(title, block)

/**
 * Builds a [ReportNode.Document] for this [TabularInputFile] whose default content
 * is the full standard report ([tabularInputFileResults]): column schema, compact
 * statistics table for numeric columns, text-column summary, and per-column
 * drill-down sections with histograms and value-frequency tables.
 *
 * Zero-code path:
 * ```kotlin
 * val tif = TabularInputFile(path)
 * tif.toReport().showInBrowser()
 * tif.toReport().writeMarkdown()
 * ```
 *
 * Custom block **replaces** the default — call [tabularInputFileResults] inside
 * the block to include the standard report alongside additional custom content:
 * ```kotlin
 * tif.toReport("Sensor Analysis", maxRows = 2000) {
 *     tabularInputFileResults(this@toReport, maxRows = 2000, detail = true)
 *     section("Commentary") { paragraph("Outliers noted in column C3.") }
 * }
 * ```
 *
 * @param title           document title; defaults to the file name
 * @param maxRows         maximum rows read per column for all sub-sections;
 *                        `0` = all rows; defaults to 500
 * @param confidenceLevel confidence level for all statistical tables; must be in (0, 1)
 * @param detail          `true` appends a diagnostic statistics table to the summary section
 * @param showPlots       when `true` (default) histogram plots are included
 * @param block           optional DSL block; replaces the default when provided
 * @return the assembled [ReportNode.Document]
 */
fun TabularInputFile.toReport(
    title: String = path.fileName.toString(),
    maxRows: Int = 500,
    confidenceLevel: Double = 0.95,
    detail: Boolean = false,
    showPlots: Boolean = true,
    block: ReportBuilder.() -> Unit = {
        tabularInputFileResults(
            file            = this@toReport,
            maxRows         = maxRows,
            confidenceLevel = confidenceLevel,
            detail          = detail,
            showPlots       = showPlots
        )
    }
): ReportNode.Document = report(title, block)

// ── Private helpers ───────────────────────────────────────────────────────────

/**
 * Returns a human-readable description of how many rows will be reported.
 *
 * - `maxRows <= 0` or `maxRows >= totalNumberRows` → `"all N rows"`
 * - otherwise → `"first M of N rows"`
 */
private fun TabularInputFile.rowsLabel(maxRows: Int): String =
    if (maxRows <= 0 || maxRows >= totalNumberRows)
        "all $totalNumberRows rows"
    else
        "first $maxRows of $totalNumberRows rows"

