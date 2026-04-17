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

import ksl.utilities.io.report.extensions.regressionDiagnostics
import ksl.utilities.io.report.extensions.regressionParameters
import ksl.utilities.io.report.extensions.regressionSummary
import ksl.utilities.io.report.extensions.toReport
import ksl.utilities.io.report.dsl.report
import ksl.utilities.io.report.showInBrowser
import ksl.utilities.io.report.writeHtml
import ksl.utilities.statistic.OLSRegression
import ksl.utilities.statistic.RegressionData
import ksl.utilities.transpose

/**
 * Demonstrates use of the regression reporting framework extensions.
 *
 * Four demos:
 * 1. [demoRegressionFullReport]     — zero-code full report via [OLSRegression.toReport]
 * 2. [demoRegressionSummaryOnly]    — ANOVA and model fit only via [regressionSummary]
 * 3. [demoRegressionCustomBlock]    — custom DSL block replacing the default report content
 * 4. [demoRegressionGranularReport] — all three granular functions composed into a single document
 *
 * Data: delivery-time study from Montgomery, Peck & Vining (2012) §3.1.
 * Response Y = delivery time (minutes); X₁ = number of cases; X₂ = distance (feet).
 */
fun main() {
    demoRegressionFullReport()
//    demoRegressionSummaryOnly()
//    demoRegressionCustomBlock()
//    demoRegressionGranularReport()
}

// ── Shared test data ──────────────────────────────────────────────────────────

/**
 * Delivery-time data (n = 25).
 * Source: Montgomery, Peck & Vining, *Introduction to Linear Regression Analysis*, §3.1.
 */
private fun regressionDeliveryData(): OLSRegression {
    val myY = doubleArrayOf(
        9.95, 24.45, 31.75, 35.00, 25.02, 16.86, 14.38,  9.60, 24.35, 27.50,
        17.08, 37.00, 41.95, 11.66, 21.65, 17.89, 69.00, 10.30, 34.93, 46.59,
        44.88, 54.12, 56.63, 22.13, 21.15
    )
    val myX1 = doubleArrayOf(
         2.0,  8.0, 11.0, 10.0,  8.0,  4.0,  2.0,  2.0,  9.0,  8.0,
         4.0, 11.0, 12.0,  2.0,  4.0,  4.0, 20.0,  1.0, 10.0, 15.0,
        15.0, 16.0, 17.0,  6.0,  5.0
    )
    val myX2 = doubleArrayOf(
         50.0, 110.0, 120.0, 550.0, 295.0, 200.0, 375.0,  52.0, 100.0, 300.0,
        412.0, 400.0, 500.0, 360.0, 205.0, 400.0, 600.0, 585.0, 540.0, 250.0,
        290.0, 510.0, 590.0, 100.0, 400.0
    )
    val myData = arrayOf(myX1, myX2).transpose()
    val myRd = RegressionData(
        response       = myY,
        data           = myData,
        responseName   = "DeliveryTime",
        predictorNames = listOf("NumCases", "Distance")
    )
    return OLSRegression(myRd)
}

// ── Demo 1: Full report (zero-code entry point) ───────────────────────────────

/**
 * Demo 1 — zero-code full report.
 *
 * [OLSRegression.toReport] produces a document with three sections:
 * - Regression Summary (ANOVA table + model fit measures)
 * - Parameter Estimates (coefficient table with CIs and significance codes)
 * - Regression Diagnostics (residual summary table + three diagnostic plots)
 */
fun demoRegressionFullReport() {
    val myOls = regressionDeliveryData()

    println("=== Demo 1: Full Regression Report ===")
    println(myOls.results())

    myOls.toReport(title = "Delivery Time Study — OLS Regression").showInBrowser()
}

// ── Demo 2: Summary only ──────────────────────────────────────────────────────

/**
 * Demo 2 — ANOVA table and model fit measures only.
 *
 * Calls [regressionSummary] directly inside [report] to produce a focused
 * document without parameter detail or diagnostic plots.
 */
fun demoRegressionSummaryOnly() {
    val myOls = regressionDeliveryData()

    println("=== Demo 2: Summary Only ===")

    report("Delivery Time — ANOVA Summary") {
        regressionSummary(myOls)
    }.showInBrowser()
}

// ── Demo 3: Custom block ──────────────────────────────────────────────────────

/**
 * Demo 3 — custom DSL block that replaces the default report content.
 *
 * The custom block uses the captured local variable [myOls] (not `this@toReport`)
 * and selectively includes only summary and parameter sections, then appends an
 * interpretation paragraph.
 */
fun demoRegressionCustomBlock() {
    val myOls = regressionDeliveryData()

    println("=== Demo 3: Custom Block ===")

    myOls.toReport(title = "Delivery Time — Custom Report") {
        regressionSummary(myOls)
        regressionParameters(myOls, confidenceLevel = 0.99)
        paragraph(
            "Both NumCases and Distance are statistically significant (p < 0.001). " +
            "The model explains ${"%,.1f".format(myOls.rSquared * 100.0)}% of the " +
            "variance in delivery time (R\u00b2 = ${
                "%.4f".format(myOls.rSquared)
            }, Adj R\u00b2 = ${
                "%.4f".format(myOls.adjustedRSquared)
            })."
        )
    }.showInBrowser()
}

// ── Demo 4: Granular functions in a standalone document ───────────────────────

/**
 * Demo 4 — all three granular functions composed into a single document
 * using the [report] DSL directly, with an explicit section structure.
 *
 * This illustrates how the granular functions can be assembled freely to
 * produce a layout that differs from the default [toReport] order.
 */
fun demoRegressionGranularReport() {
    val myOls = regressionDeliveryData()

    println("=== Demo 4: Granular Functions ===")

    val myDoc = report("Delivery Time Study — Detailed Analysis") {

        section("Model Fit and ANOVA") {
            regressionSummary(myOls)
        }

        section("Coefficient Inference") {
            regressionParameters(myOls, confidenceLevel = 0.95)
        }

        section("Residual Diagnostics") {
            regressionDiagnostics(myOls)
        }
    }

    myDoc.showInBrowser()
    myDoc.writeHtml()
}
