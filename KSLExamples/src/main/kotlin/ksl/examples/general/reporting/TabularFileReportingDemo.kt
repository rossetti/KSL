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

package ksl.examples.general.reporting

import ksl.utilities.io.KSL
import ksl.utilities.io.report.dsl.report
import ksl.utilities.io.report.extensions.tabularFileSchema
import ksl.utilities.io.report.extensions.tabularInputFileColumns
import ksl.utilities.io.report.extensions.tabularInputFileResults
import ksl.utilities.io.report.extensions.tabularInputFileSummary
import ksl.utilities.io.report.extensions.toReport
import ksl.utilities.io.report.showInBrowser
import ksl.utilities.io.report.writeHtml
import ksl.utilities.io.report.writeMarkdown
import ksl.utilities.io.report.writeText
import ksl.utilities.io.tabularfiles.DataType
import ksl.utilities.io.tabularfiles.TabularFile
import ksl.utilities.io.tabularfiles.TabularInputFile
import ksl.utilities.io.tabularfiles.TabularOutputFile
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.NormalRV
import ksl.utilities.random.rvariable.UniformRV
import java.nio.file.Path

// ── Shared file-creation helpers ──────────────────────────────────────────────

/**
 * Writes a purely numeric tabular file (5 columns, 300 rows) to [KSL.outDir] and
 * returns the path.
 *
 * **Columns:**
 * - `svc_time`  — Normal(μ=8, σ=1.5): customer service time (minutes)
 * - `wait_time` — Exponential(mean=4): queue waiting time (minutes)
 * - `travel`    — Normal(μ=12, σ=3): travel time to facility (minutes)
 * - `load_time` — Exponential(mean=2): loading time at counter (minutes)
 * - `util`      — Uniform(0, 1): sampled server utilisation fraction
 *
 * The file is created fresh on each call (overwriting any existing file at the
 * same path). Call [TabularInputFile] on the returned path to read it.
 */
private fun writeNumericFile(numRows: Int = 300): Path {
    val myPath = KSL.outDir.resolve("demo_numeric")
    val myColumns = linkedMapOf(
        "svc_time"  to DataType.NUMERIC,
        "wait_time" to DataType.NUMERIC,
        "travel"    to DataType.NUMERIC,
        "load_time" to DataType.NUMERIC,
        "util"      to DataType.NUMERIC
    )
    val myTof = TabularOutputFile(myColumns, myPath)
    val myRow = myTof.row()
    val mySvc    = NormalRV(8.0,  1.5)
    val myWait   = ExponentialRV(4.0)
    val myTravel = NormalRV(12.0, 3.0)
    val myLoad   = ExponentialRV(2.0)
    val myUtil   = UniformRV(0.0, 1.0)
    for (i in 1..numRows) {
        myRow.setNumeric("svc_time",  mySvc.value.coerceAtLeast(0.0))
        myRow.setNumeric("wait_time", myWait.value)
        myRow.setNumeric("travel",    myTravel.value.coerceAtLeast(0.0))
        myRow.setNumeric("load_time", myLoad.value)
        myRow.setNumeric("util",      myUtil.value)
        myTof.writeRow(myRow)
    }
    myTof.flushRows()
    return myPath
}

/**
 * Writes a mixed numeric-and-text tabular file (3 numeric + 2 text columns, 200 rows)
 * to [KSL.outDir] and returns the path.
 *
 * **Columns:**
 * - `cycle_time` — Exponential(mean=6): job cycle time (minutes)
 * - `defects`    — Normal(μ=2, σ=0.8): defect count per batch (continuous proxy)
 * - `throughput` — Normal(μ=50, σ=5): units produced per hour
 * - `shift`      — text: one of `"Morning"`, `"Afternoon"`, `"Night"` (roughly 1/3 each)
 * - `status`     — text: one of `"Pass"` (~70 %), `"Fail"` (~20 %), `"Rework"` (~10 %)
 *
 * The file is created fresh on each call.
 */
private fun writeMixedFile(numRows: Int = 200): Path {
    val myPath = KSL.outDir.resolve("demo_mixed")
    val myColumns = linkedMapOf(
        "cycle_time" to DataType.NUMERIC,
        "defects"    to DataType.NUMERIC,
        "throughput" to DataType.NUMERIC,
        "shift"      to DataType.TEXT,
        "status"     to DataType.TEXT
    )
    val myTof      = TabularOutputFile(myColumns, myPath)
    val myRow      = myTof.row()
    val myCycle    = ExponentialRV(6.0)
    val myDefects  = NormalRV(2.0, 0.8)
    val myTput     = NormalRV(50.0, 5.0)
    val myShiftRv  = UniformRV(0.0, 1.0)
    val myStatusRv = UniformRV(0.0, 1.0)
    val myShifts   = arrayOf("Morning", "Afternoon", "Night")
    for (i in 1..numRows) {
        myRow.setNumeric("cycle_time", myCycle.value)
        myRow.setNumeric("defects",    myDefects.value.coerceAtLeast(0.0))
        myRow.setNumeric("throughput", myTput.value.coerceAtLeast(0.0))
        // assign shift roughly 1/3 each
        val myShiftIdx = (myShiftRv.value * 3).toInt().coerceAtMost(2)
        myRow.setText("shift", myShifts[myShiftIdx])
        // assign status with weighted probabilities
        val mySv = myStatusRv.value
        myRow.setText("status", when {
            mySv < 0.70 -> "Pass"
            mySv < 0.90 -> "Fail"
            else        -> "Rework"
        })
        myTof.writeRow(myRow)
    }
    myTof.flushRows()
    return myPath
}

// ── Demo 1: TabularOutputFile schema ──────────────────────────────────────────

/**
 * Demonstrates [TabularOutputFile.toReport]: schema-only report for a write-only file.
 *
 * The output file cannot be read back until flushed and re-opened as a
 * [TabularInputFile], so the report contains only the column schema. This is the
 * natural path for documenting a file's structure at creation time, e.g. for
 * a data-pipeline hand-off.
 *
 * All four output formats are written to `kslOutput/`.
 */
fun demoTabularOutputFileSchema() {
    val myPath = KSL.outDir.resolve("demo_schema_only")
    val myColumns = linkedMapOf(
        "arrival_time"   to DataType.NUMERIC,
        "service_time"   to DataType.NUMERIC,
        "departure_time" to DataType.NUMERIC,
        "server_id"      to DataType.TEXT,
        "priority_class" to DataType.TEXT
    )
    val myTof = TabularOutputFile(myColumns, myPath)

    // zero-code entry point — schema only, no row data required
    val myDoc = myTof.toReport(title = "Event Log — Column Schema")
    myDoc.showInBrowser()
    myDoc.writeHtml()
    myDoc.writeMarkdown()
    myDoc.writeText()
    println("Output file schema report written to kslOutput/")
}

// ── Demo 2: Schema section inside a custom report ─────────────────────────────

/**
 * Demonstrates calling [tabularFileSchema] directly from inside a hand-crafted
 * [report] block, combining the schema table with a custom narrative paragraph.
 *
 * This is the typical pattern when a schema section needs to be embedded inside
 * a larger document alongside other content (e.g. a data dictionary that covers
 * several files).
 */
fun demoTabularFileSchemaInCustomReport() {
    val myNumericPath = writeNumericFile()
    val myMixedPath   = writeMixedFile()
    val myNumericTif  = TabularInputFile(myNumericPath)
    val myMixedTif    = TabularInputFile(myMixedPath)

    val myDoc = report("Data Dictionary — Simulation Output Files") {
        paragraph(
            "This document describes the column structure of two tabular output " +
            "files produced by the simulation pre-processing pipeline."
        )
        section("Numeric Observations File") {
            paragraph(
                "Contains per-customer timing observations. Each row is one customer. " +
                "All times are in minutes."
            )
            tabularFileSchema(myNumericTif, caption = "Schema: numeric observations")
        }
        section("Mixed Operations File") {
            paragraph(
                "Contains per-job production observations with categorical shift and " +
                "quality-control status labels."
            )
            tabularFileSchema(myMixedTif, caption = "Schema: mixed operations data")
        }
    }
    myDoc.showInBrowser()
    myDoc.writeMarkdown()
    myNumericTif.close()
    myMixedTif.close()
    println("Data dictionary report written to kslOutput/")
}

// ── Demo 3: Summary report — numeric-only file ────────────────────────────────

/**
 * Demonstrates [tabularInputFileSummary] on a purely numeric file.
 *
 * The summary section contains:
 * 1. A schema sub-section (column index, name, type).
 * 2. A row-count paragraph indicating how many rows were read.
 * 3. A compact [ReportNode.StatTable] with one row per column (count, mean,
 *    std dev, half-width, CI, min, max).
 *
 * `detail = true` appends a second diagnostic table (skewness, kurtosis,
 * Lag-1 correlation, Von Neumann test statistic).
 *
 * `maxRows = 150` caps the read at 150 of the 300 available rows to demonstrate
 * the partial-read behaviour. Use `maxRows = 0` to read all rows.
 */
fun demoTabularInputFileSummary() {
    val myPath = writeNumericFile(numRows = 300)
    val myTif  = TabularInputFile(myPath)

    val myDoc = report("Numeric File Summary — Customer Timing Data") {
        paragraph(
            "Summary statistics for 300 rows of simulated customer timing data. " +
            "Only the first 150 rows are used for this report to demonstrate " +
            "the maxRows parameter."
        )
        tabularInputFileSummary(
            file            = myTif,
            maxRows         = 150,
            confidenceLevel = 0.95,
            detail          = true
        )
    }
    myDoc.showInBrowser()
    myDoc.writeHtml()
    myTif.close()
    println("Numeric summary report written to kslOutput/")
}

// ── Demo 4: Summary report — mixed numeric + text file ────────────────────────

/**
 * Demonstrates [tabularInputFileSummary] on a file with both numeric and text columns.
 *
 * The summary section produces:
 * 1. Schema sub-section.
 * 2. Row-count paragraph.
 * 3. [ReportNode.StatTable] for the three numeric columns.
 * 4. A two-column [ReportNode.DataTable] ("Text Column Summary") for the two
 *    text columns, reporting count, distinct values, and missing count.
 *
 * The zero-code entry point [TabularInputFile.toReport] is used here — it calls
 * [tabularInputFileResults] internally, which calls [tabularInputFileSummary] as
 * its first step. This demo therefore also covers the zero-code path with defaults.
 */
fun demoTabularInputFileSummaryMixed() {
    val myPath = writeMixedFile(numRows = 200)
    val myTif  = TabularInputFile(myPath)

    // use tabularInputFileSummary directly to show summary-only (no per-column detail)
    val myDoc = report("Mixed File Summary — Production Operations Data") {
        paragraph(
            "Summary of 200 rows of simulated production data. " +
            "Three numeric columns (cycle time, defect count, throughput) and " +
            "two text columns (shift assignment, quality-control status)."
        )
        tabularInputFileSummary(
            file            = myTif,
            maxRows         = 200,
            confidenceLevel = 0.95,
            detail          = false
        )
    }
    myDoc.showInBrowser()
    myDoc.writeMarkdown()
    myTif.close()
    println("Mixed summary report written to kslOutput/")
}

// ── Demo 5: Per-column detail — numeric columns with histograms ───────────────

/**
 * Demonstrates [tabularInputFileColumns] on the numeric file.
 *
 * Each numeric column produces a sub-section with:
 * - An overview paragraph (bin count, range, under/overflow, missing).
 * - A bin-frequency [ReportNode.DataTable].
 * - An 18-property [ReportNode.StatPropertyTable] (the Missing row reflects
 *   any NaN values in the file).
 * - A [ReportNode.PlotNode] histogram bar chart.
 *
 * `showPlots = false` is demonstrated in a second document below to show that
 * plots can be suppressed when only tables are needed.
 */
fun demoTabularInputFileColumns() {
    val myPath = writeNumericFile(numRows = 300)
    val myTif  = TabularInputFile(myPath)

    // with plots
    val myDocWithPlots = report("Numeric File — Per-Column Detail (with plots)") {
        paragraph(
            "Per-column histograms and statistics for all five numeric columns. " +
            "Histogram break points are chosen automatically using Sturges' rule."
        )
        tabularInputFileColumns(
            file            = myTif,
            maxRows         = 300,
            confidenceLevel = 0.95,
            showPlots       = true
        )
    }
    myDocWithPlots.showInBrowser()
    myDocWithPlots.writeHtml()

    // without plots — tables only
    val myDocNoPlots = report("Numeric File — Per-Column Detail (tables only)") {
        paragraph(
            "Same per-column detail with plots suppressed. Useful for plain-text " +
            "and LaTeX output targets where embedded plots are handled separately."
        )
        tabularInputFileColumns(
            file            = myTif,
            maxRows         = 300,
            confidenceLevel = 0.95,
            showPlots       = false
        )
    }
    myDocNoPlots.writeText()
    myTif.close()
    println("Per-column detail reports written to kslOutput/")
}

// ── Demo 6: Per-column detail — mixed file (histograms + text frequencies) ────

/**
 * Demonstrates [tabularInputFileColumns] on the mixed file.
 *
 * Numeric columns produce histogram sub-sections as in Demo 5.
 *
 * Text columns produce a sub-section with:
 * - A paragraph stating count, distinct value count, and missing count.
 * - A [ReportNode.DataTable] ("Value Frequencies") with columns Value | Count | %,
 *   sorted descending by count. For `shift` the three categories should appear in
 *   roughly equal proportions. For `status` Pass (~70 %), Fail (~20 %), Rework (~10 %)
 *   should be visible.
 */
fun demoTabularInputFileColumnsMixed() {
    val myPath = writeMixedFile(numRows = 200)
    val myTif  = TabularInputFile(myPath)

    val myDoc = report("Mixed File — Per-Column Detail") {
        paragraph(
            "Per-column breakdown for the production operations data file. " +
            "Numeric columns show histograms and summary statistics. " +
            "Text columns show value-frequency tables."
        )
        tabularInputFileColumns(
            file            = myTif,
            maxRows         = 200,
            confidenceLevel = 0.95,
            showPlots       = true
        )
    }
    myDoc.showInBrowser()
    myDoc.writeMarkdown()
    myTif.close()
    println("Mixed per-column detail report written to kslOutput/")
}

// ── Demo 7: Full composite report — zero-code entry point ─────────────────────

/**
 * Demonstrates the zero-code [TabularInputFile.toReport] entry point on the numeric file.
 *
 * This is the simplest path: one call on the file object produces the full standard
 * report (summary section + per-column detail section) and opens it in a browser.
 * No [report] block is needed. All four output formats are written.
 *
 * `maxRows = 0` instructs the report to read all rows in the file.
 */
fun demoTabularInputFileZeroCode() {
    val myPath = writeNumericFile(numRows = 300)
    val myTif  = TabularInputFile(myPath)

    // single call — full standard report
    val myDoc = myTif.toReport(
        title           = "Customer Timing Data — Full Report",
        maxRows         = 0,          // all rows
        confidenceLevel = 0.95,
        detail          = false,
        showPlots       = true
    )
    myDoc.showInBrowser()
    myDoc.writeHtml()
    myDoc.writeMarkdown()
    myDoc.writeText()
    myTif.close()
    println("Full zero-code report written to kslOutput/")
}

// ── Demo 8: Full composite report — zero-code on mixed file ───────────────────

/**
 * Demonstrates the zero-code [TabularInputFile.toReport] entry point on the mixed file.
 *
 * The default block in [TabularInputFile.toReport] calls [tabularInputFileResults],
 * which calls [tabularInputFileSummary] followed by [tabularInputFileColumns]. This
 * produces a single document with schema, compact stats, text summary, and all
 * per-column sub-sections. `detail = true` enables the diagnostic statistics table
 * in the summary section.
 */
fun demoTabularInputFileMixedZeroCode() {
    val myPath = writeMixedFile(numRows = 200)
    val myTif  = TabularInputFile(myPath)

    val myDoc = myTif.toReport(
        title           = "Production Operations Data — Full Report",
        maxRows         = 200,
        confidenceLevel = 0.95,
        detail          = true,
        showPlots       = true
    )
    myDoc.showInBrowser()
    myDoc.writeHtml()
    myTif.close()
    println("Full mixed-file report written to kslOutput/")
}

// ── Demo 9: Custom DSL block overriding the default ───────────────────────────

/**
 * Demonstrates supplying a custom DSL [block] to [TabularInputFile.toReport] to
 * replace the default [tabularInputFileResults] content.
 *
 * The custom block calls [tabularInputFileSummary] and [tabularInputFileColumns]
 * explicitly, placing additional narrative sections between them and after them.
 * This pattern is used when the standard report layout needs to be augmented with
 * commentary, additional statistics, or cross-file comparisons.
 */
fun demoTabularInputFileCustomBlock() {
    val myPath = writeMixedFile(numRows = 200)
    val myTif  = TabularInputFile(myPath)

    val myDoc = myTif.toReport(
        title   = "Production Operations — Annotated Analysis",
        maxRows = 200
    ) {
        paragraph(
            "This report covers 200 rows of simulated production data from a single " +
            "shift period. Sections are arranged from high-level summary to per-column " +
            "drill-down, with commentary inserted between them."
        )

        // standard summary (schema + compact stats + text summary)
        tabularInputFileSummary(
            file            = myTif,
            caption         = "High-Level Summary",
            maxRows         = 200,
            confidenceLevel = 0.95,
            detail          = false
        )

        section("Analyst Commentary — Summary") {
            paragraph(
                "Cycle time and throughput distributions show moderate right skew, " +
                "consistent with the exponential and normal generating processes. " +
                "The shift column is roughly balanced across three categories. " +
                "The Fail and Rework rates appear within expected tolerance bands."
            )
        }

        // per-column detail (histograms + text frequency tables)
        tabularInputFileColumns(
            file            = myTif,
            caption         = "Detailed Column Analysis",
            maxRows         = 200,
            confidenceLevel = 0.95,
            showPlots       = true
        )

        section("Analyst Commentary — Column Detail") {
            paragraph(
                "All numeric columns produced well-formed histograms with no missing " +
                "values. The status column frequency table confirms the expected " +
                "Pass / Fail / Rework distribution."
            )
            pageBreak()
        }
    }
    myDoc.showInBrowser()
    myDoc.writeMarkdown()
    myTif.close()
    println("Custom annotated report written to kslOutput/")
}

// ── Demo 10: Composite report — two files in one document ─────────────────────

/**
 * Demonstrates combining two [TabularInputFile] reports into a single document
 * using the [report] DSL.
 *
 * Each file is reported in its own top-level section using [tabularInputFileResults].
 * The document title and introductory paragraph tie the two files together
 * narratively — the typical use case for a data-pipeline audit report that
 * covers both raw inputs and processed outputs.
 */
fun demoCompositeTabularReport() {
    val myNumericPath = writeNumericFile(numRows = 300)
    val myMixedPath   = writeMixedFile(numRows = 200)
    val myNumericTif  = TabularInputFile(myNumericPath)
    val myMixedTif    = TabularInputFile(myMixedPath)

    val myDoc = report("Simulation Pipeline — Data Quality Report") {
        paragraph(
            "This report covers two tabular data files produced by the simulation " +
            "pre-processing pipeline. Section 1 reports per-customer timing observations " +
            "(numeric only). Section 2 reports per-job production observations " +
            "(mixed numeric and categorical)."
        )

        section("Section 1 — Customer Timing Observations") {
            paragraph(
                "300 rows of per-customer timing data sampled from Normal and Exponential " +
                "distributions. All columns are numeric; no categorical fields."
            )
            tabularInputFileResults(
                file            = myNumericTif,
                maxRows         = 300,
                confidenceLevel = 0.95,
                detail          = false,
                showPlots       = true
            )
        }

        pageBreak()

        section("Section 2 — Production Operations Data") {
            paragraph(
                "200 rows of per-job production data with three numeric measurement " +
                "columns and two categorical label columns."
            )
            tabularInputFileResults(
                file            = myMixedTif,
                maxRows         = 200,
                confidenceLevel = 0.95,
                detail          = false,
                showPlots       = true
            )
        }
    }
    myDoc.showInBrowser()
    myDoc.writeHtml()
    myDoc.writeMarkdown()
    myNumericTif.close()
    myMixedTif.close()
    println("Composite two-file report written to kslOutput/")
}

// ── Entry point ───────────────────────────────────────────────────────────────

fun main() {
    // Run each demo in sequence. Each opens a browser tab and writes files to
    // kslOutput/. Comment out any demos you do not want to run.
//    demoTabularOutputFileSchema()
//    demoTabularFileSchemaInCustomReport()
//    demoTabularInputFileSummary()
//    demoTabularInputFileSummaryMixed()
//    demoTabularInputFileColumns()
//    demoTabularInputFileColumnsMixed()
    demoTabularInputFileZeroCode()
    demoTabularInputFileMixedZeroCode()
//    demoTabularInputFileCustomBlock()
//    demoCompositeTabularReport()
}
