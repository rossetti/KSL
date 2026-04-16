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

import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.io.report.extensions.allGoodnessOfFit
import ksl.utilities.io.report.extensions.dataStatisticalSummary
import ksl.utilities.io.report.extensions.dataVisualization
import ksl.utilities.io.report.extensions.goodnessOfFit
import ksl.utilities.io.report.extensions.moda
import ksl.utilities.io.report.extensions.toReport
import ksl.utilities.io.report.printText
import ksl.utilities.io.report.showInBrowser
import ksl.utilities.io.report.writeHtml
import ksl.utilities.io.report.writeMarkdown
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.GammaRV
import ksl.utilities.random.rvariable.WeibullRV

// ── Demo 1: Data Exploration — zero-code EDA before fitting ──────────────────

/**
 * Demonstrates [PDFModeler.toReport] as a zero-code entry point for exploratory
 * data analysis before any distribution fitting is performed.
 *
 * Uses 1 000 observations sampled from a Gamma(shape = 2, scale = 5) distribution
 * so the data has a recognisable right-skewed shape.
 *
 * **Illustrates:**
 * - `PDFModeler.toReport().showInBrowser()` — single call, no fitting required
 * - `PDFModeler.toReport().writeMarkdown()` — same report as Markdown
 * - `PDFModeler.toReport().printText()` — plain-text console output
 * - Statistical summary: sample statistics, box-plot summary, histogram bins,
 *   shift parameter analysis
 * - Visualization section: histogram, box plot, observations, ACF plots
 */
fun demoDataExploration() {
    println("--- Demo 1: Data Exploration (EDA) Report ---")

    val myData = GammaRV(shape = 2.0, scale = 5.0, streamNum = 3).sample(1000)
    val myModeler = PDFModeler(myData)

    // ── Zero-code path ────────────────────────────────────────────────────────
    println("Opening EDA report in browser...")
    myModeler.toReport("Gamma Data — EDA").showInBrowser()

    // ── Also write to other formats ───────────────────────────────────────────
    myModeler.toReport("Gamma Data — EDA").writeMarkdown()

    // ── Console text output ───────────────────────────────────────────────────
    println("\n--- Plain-text statistical summary (console) ---")
    myModeler.toReport("Gamma Data — EDA").printText()
}

// ── Demo 2: Full fitting report — top-distribution GOF only ──────────────────

/**
 * Demonstrates [PDFModelingResults.toReport] as a zero-code entry point for a
 * complete distribution-fitting report.
 *
 * Uses 500 observations from an Exponential(mean = 10) distribution. This is a
 * common input-modelling scenario: service times or inter-arrival times.
 *
 * **Illustrates:**
 * - `results.toReport(modeler).showInBrowser()` — one document covering all four
 *   analytical stages (statistics, visualization, MODA scoring, GOF for top distribution)
 * - Console summary of the recommended distribution
 */
fun demoFullFittingReport() {
    println("\n--- Demo 2: Full Fitting Report (top distribution GOF) ---")

    val myData    = ExponentialRV(mean = 10.0, streamNum = 5).sample(500)
    val myModeler = PDFModeler(myData)

    val myEstimationResults = myModeler.estimateParameters(PDFModeler.allEstimators)
    val myScoringResults    = myModeler.scoringResults(myEstimationResults)
    val myResults           = myModeler.evaluateScores(myEstimationResults)

    // ── Zero-code path ────────────────────────────────────────────────────────
    println("Opening full fitting report in browser...")
    myResults.toReport(myModeler, "Exponential Service Times — Fitting Report")
        .showInBrowser()

    myResults.toReport(myModeler, "Exponential Service Times — Fitting Report")
        .writeHtml()

    // ── Console: recommended distribution ────────────────────────────────────
    println("\nTop distribution by MODA score: ${myResults.topResultByScore.name}")
    println("Top distribution by avg ranking: ${myResults.topResultByRanking.name}")
    println("\nTop 3 by MODA value:")
    myResults.resultsSortedByScoring.take(3).forEachIndexed { i, sr ->
        println("  ${i + 1}. ${sr.name}  (value = ${"%.4f".format(sr.weightedValue)})")
    }
}

// ── Demo 3: Full fitting report — GOF for all distributions ──────────────────

/**
 * Demonstrates [PDFModelingResults.toReport] with `allGOF = true`, producing a
 * comprehensive report that includes goodness-of-fit analysis for every fitted
 * distribution, not just the top-ranked one.
 *
 * Uses 800 observations from a Weibull(shape = 1.5, scale = 8) distribution.
 *
 * **Illustrates:**
 * - `results.toReport(modeler, allGOF = true)` — GOF section covers all distributions
 * - Writing to HTML for archiving
 */
fun demoAllGOFReport() {
    println("\n--- Demo 3: Full Report with GOF for all Distributions ---")

    val myData    = WeibullRV(shape = 1.5, scale = 8.0, streamNum = 7).sample(800)
    val myModeler = PDFModeler(myData)
    val myResults = myModeler.estimateAndEvaluateScores()

    println("Opening full report (all distributions GOF) in browser...")
    myResults.toReport(
        modeler = myModeler,
        title   = "Weibull Processing Times — Full Fitting Study",
        allGOF  = true
    ).showInBrowser()

    myResults.toReport(
        modeler = myModeler,
        title   = "Weibull Processing Times — Full Fitting Study",
        allGOF  = true
    ).writeHtml()

    println("Fitted ${myResults.scoringResults.size} distributions.")
    println("Recommended: ${myResults.topResultByScore.name}")
}

// ── Demo 4: Custom block — selective report composition ───────────────────────

/**
 * Demonstrates using a custom DSL block with [PDFModelingResults.toReport] to
 * compose a report that shows only the sections relevant to the user's goal:
 * statistical summary, MODA scoring, and GOF for the second-best distribution.
 *
 * This shows that the granular DSL functions can be mixed freely inside any
 * `report {}` block.
 *
 * **Illustrates:**
 * - Custom block replacing the default content
 * - [dataStatisticalSummary], [moda], [goodnessOfFit] used individually
 * - Targeting a specific [ScoringResult] (not just the top one)
 */
fun demoCustomBlock() {
    println("\n--- Demo 4: Custom Block — Selective Report ---")

    val myData    = ExponentialRV(mean = 15.0, streamNum = 11).sample(600)
    val myModeler = PDFModeler(myData)
    val myResults = myModeler.estimateAndEvaluateScores()

    // Second-best distribution (index 1 of sorted-by-score list)
    val mySecondBest = myResults.resultsSortedByScoring
        .getOrElse(1) { myResults.topResultByScore }

    myResults.toReport(myModeler, "Custom Selective Report") {
        dataStatisticalSummary(myModeler)
        moda(myResults.evaluationModel, caption = "MODA Scoring Results")
        goodnessOfFit(
            result    = mySecondBest,
            modeler   = myModeler,
            caption   = "GOF for Second-Best Distribution: ${mySecondBest.name}"
        )
    }.also { doc ->
        doc.showInBrowser()
        doc.writeMarkdown()
        println("Custom report written.")
    }
}

// ── Demo 5: Granular DSL functions used standalone ────────────────────────────

/**
 * Demonstrates calling the four granular DSL extension functions independently
 * via the low-level [ksl.utilities.io.report.dsl.report] builder, without using
 * [PDFModelingResults.toReport].
 *
 * **Illustrates:**
 * - [dataStatisticalSummary] — stats-only document
 * - [dataVisualization] — plots-only document
 * - [allGoodnessOfFit] — GOF-only document covering all distributions
 * - Each renders to a separate browser tab, matching the old `showAllResultsInBrowser()` pattern
 */
fun demoGranularFunctions() {
    println("\n--- Demo 5: Granular DSL Functions Used Individually ---")

    val myData    = GammaRV(shape = 3.0, scale = 4.0, streamNum = 13).sample(750)
    val myModeler = PDFModeler(myData)
    val myResults = myModeler.estimateAndEvaluateScores()

    // Stats-only document
    ksl.utilities.io.report.dsl.report("Gamma Data — Statistics") {
        dataStatisticalSummary(myModeler)
    }.showInBrowser()

    // Visualization-only document
    ksl.utilities.io.report.dsl.report("Gamma Data — Plots") {
        dataVisualization(myModeler)
    }.showInBrowser()

    // Scoring-only document (reuses existing moda() extension directly)
    ksl.utilities.io.report.dsl.report("Gamma Data — MODA Scoring") {
        moda(myResults.evaluationModel)
    }.showInBrowser()

    // All-GOF document
    ksl.utilities.io.report.dsl.report("Gamma Data — All GOF") {
        allGoodnessOfFit(myResults, myModeler)
    }.showInBrowser()

    println("Four separate browser tabs opened (stats, plots, scoring, all GOF).")
}

// ── Entry point ───────────────────────────────────────────────────────────────

fun main() {
    demoDataExploration()
    demoFullFittingReport()
    demoAllGOFReport()
    demoCustomBlock()
    demoGranularFunctions()
}
