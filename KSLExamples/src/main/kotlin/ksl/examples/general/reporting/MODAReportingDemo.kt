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

import ksl.examples.book.chapter5.PalletWorkCenter
import ksl.simulation.Model
import ksl.utilities.Interval
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.io.dbutil.KSLDatabaseObserver
import ksl.utilities.io.report.extensions.MCBDirection
import ksl.utilities.io.report.extensions.moda
import ksl.utilities.io.report.extensions.modaAnalysis
import ksl.utilities.io.report.extensions.toReport
import ksl.utilities.io.report.printText
import ksl.utilities.io.report.showInBrowser
import ksl.utilities.io.report.writeHtml
import ksl.utilities.io.report.writeMarkdown
import ksl.utilities.moda.MODAAnalyzer
import ksl.utilities.moda.MODAAnalyzerData
import ksl.utilities.moda.MetricIfc
import ksl.utilities.random.rvariable.ExponentialRV

// ── Demo 1: AdditiveMODAModel — distribution fitting evaluation ───────────────

/**
 * Demonstrates [AdditiveMODAModel] reporting via the zero-code [toReport] path.
 *
 * Uses [PDFModeler] to fit a set of candidate distributions to exponential data,
 * scores each fit against multiple goodness-of-fit criteria (KS, AD, BIC, etc.),
 * and renders the resulting MODA model as an HTML report.
 *
 * **Illustrates:**
 * - `AdditiveMODAModel.toReport().showInBrowser()` — zero-code entry point
 * - The full [moda] DSL section: metric definitions, raw scores, transformed
 *   values, overall weighted value, and rankings
 */
fun demoMODAModelReport() {
    println("--- Demo: MODA Model Report (Distribution Fitting) ---")

    // Generate sample data from an Exponential(mean=10) distribution
    val myData = ExponentialRV(10.0, streamNum = 2).sample(1000)

    // Fit all candidate distributions and evaluate with MODA scoring
    val myModeler = PDFModeler(myData)
    val myEstimationResults = myModeler.estimateParameters(PDFModeler.allEstimators)
    val myScoringResults    = myModeler.scoringResults(myEstimationResults)
    val myModel             = myModeler.evaluateScoringResults(myScoringResults)

    // ── Zero-code path ────────────────────────────────────────────────────────
    println("Opening MODA Model report in browser...")
    myModel.toReport("Distribution Fitting — MODA Evaluation").showInBrowser()

    // ── Also write to file formats ────────────────────────────────────────────
    myModel.toReport("Distribution Fitting — MODA Evaluation").writeHtml()
    myModel.toReport("Distribution Fitting — MODA Evaluation").writeMarkdown()

    // ── Console output (text) ─────────────────────────────────────────────────
    println("\n--- Top alternatives by overall MODA value ---")
    myModel.sortedMultiObjectiveValuesByAlternative().take(5).forEach { (name, value) ->
        println("  %-40s  overall value = %.4f".format(name, value))
    }
    println("\nTop alternatives (multi-objective value): ${myModel.topAlternativesByMultiObjectiveValue()}")
    println("Top alternatives (first-rank counts):      ${myModel.topAlternativesByFirstRankCounts()}")

    // ── Custom block — append recommendation paragraph ────────────────────────
    val myTopName = myModel.sortedMultiObjectiveValuesByAlternative().firstOrNull()?.first ?: "—"
    myModel.toReport("Distribution Fitting — Annotated") {
        moda(myModel)
        // Access the report builder's DSL to append custom content
    }.also { doc ->
        doc.writeMarkdown()
        println("Annotated Markdown written.")
    }
}

// ── Demo 2: MODAAnalyzer — simulation-based comparison ───────────────────────

/**
 * Demonstrates [MODAAnalyzer] reporting via the zero-code [toReport] path.
 *
 * Runs the [PalletWorkCenter] model under two staffing configurations
 * ("Two Workers" vs "Three Workers"), collects within-replication data, and
 * performs a MODA analysis comparing the alternatives on three responses:
 * - Worker Utilization (BiggerIsBetter — maximise utilisation)
 * - System Time (SmallerIsBetter — minimise customer wait)
 * - Total Processing Time (SmallerIsBetter — minimise throughput time)
 *
 * **Illustrates:**
 * - `MODAAnalyzer.toReport().showInBrowser()` — zero-code entry point
 * - Average performance matrix
 * - Average MODA model section (via [moda] extension)
 * - MCB for overall MODA value with `direction = MAX`
 * - MCB for response performance with direction derived from metric
 * - MCB for response MODA values with `direction = MAX`
 * - Overall rank frequency distributions across replications
 * - Custom block showing how to use named parameters for fine-grained control
 */
fun demoMODAAnalyzerReport() {
    println("\n--- Demo: MODA Analyzer Report (Simulation Comparison) ---")

    // ── Simulate Two Workers configuration ───────────────────────────────────
    val myModel = Model("MODA Analyzer Demo")
    myModel.numberOfReplications = 10
    myModel.experimentName = "Two Workers"
    val myWorkCenter = PalletWorkCenter(myModel)

    val myDbObserver = KSLDatabaseObserver(myModel)
    val myDb = myDbObserver.db

    myModel.simulate()
    println("Simulated: ${myModel.experimentName}")

    // ── Simulate Three Workers configuration ─────────────────────────────────
    myModel.experimentName = "Three Workers"
    myWorkCenter.numWorkers = 3
    myModel.simulate()
    println("Simulated: ${myModel.experimentName}")

    // ── Define alternatives and response specifications ───────────────────────
    val myAlternatives = setOf("Two Workers", "Three Workers")

    val myResponseDefs = setOf(
        MODAAnalyzerData(
            responseName  = "Worker Utilization",
            direction     = MetricIfc.Direction.BiggerIsBetter,
            domain        = Interval(0.0, 1.0)
        ),
        MODAAnalyzerData(
            responseName  = "System Time",
            direction     = MetricIfc.Direction.SmallerIsBetter
        ),
        MODAAnalyzerData(
            responseName  = "Total Processing Time",
            direction     = MetricIfc.Direction.SmallerIsBetter
        )
    )

    // ── Build and run the MODA analyzer ──────────────────────────────────────
    val myAnalyzer = MODAAnalyzer(myAlternatives, myResponseDefs, myDb.withinRepViewData())
    myAnalyzer.analyze()
    println("MODA analysis complete.")

    // ── Zero-code path ────────────────────────────────────────────────────────
    println("Opening MODA Analyzer report in browser...")
    myAnalyzer.toReport("Pallet Work Center — MODA Analysis").showInBrowser()

    // ── Also write to file formats ────────────────────────────────────────────
    myAnalyzer.toReport("Pallet Work Center — MODA Analysis").writeHtml()
    myAnalyzer.toReport("Pallet Work Center — MODA Analysis").writeMarkdown()

    // ── Console output ────────────────────────────────────────────────────────
    println("\n--- Average MODA rankings ---")
    myAnalyzer.averageMODA().sortedMultiObjectiveValuesByAlternative().forEach { (name, value) ->
        println("  %-20s  overall value = %.4f".format(name, value))
    }

    // ── Custom block — minimum-only MCB with tighter PCS ─────────────────────
    // Demonstrates fine-grained control: show only minimum-direction MCB for
    // raw performance (minimising wait times) with a higher PCS requirement.
    val myTopName = myAnalyzer.averageMODA()
        .sortedMultiObjectiveValuesByAlternative().firstOrNull()?.first ?: "—"

    myAnalyzer.toReport("Pallet Work Center — Custom Report") {
        modaAnalysis(
            myAnalyzer,
            caption           = "MODA Analysis (PCS = 0.99)",
            confidenceLevel   = 0.99
        )
    }.also { doc ->
        doc.writeMarkdown()
        println("Custom Markdown written (PCS = 0.99).")
    }

    // ── printText() console demo ──────────────────────────────────────────────
    println("\n--- Plain-text MODA Analyzer report (console) ---")
    myAnalyzer.toReport("Pallet Work Center Console Report").printText()
}

// ── Entry point ───────────────────────────────────────────────────────────────

fun main() {
    demoMODAModelReport()
    demoMODAAnalyzerReport()
}
