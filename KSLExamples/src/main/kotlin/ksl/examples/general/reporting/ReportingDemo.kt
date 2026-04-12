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

import ksl.examples.book.chapter4.DriveThroughPharmacyWithQ
import ksl.simulation.Model
import ksl.utilities.io.report.dsl.report
import ksl.utilities.io.report.extensions.*
import ksl.utilities.io.report.showInBrowser
import ksl.utilities.io.report.writeHtml
import ksl.utilities.io.report.writeMarkdown
import ksl.utilities.io.report.writeText
import ksl.utilities.statistic.BatchStatistic
import ksl.utilities.statistic.Histogram
import ksl.utilities.statistic.IntegerFrequency
import ksl.utilities.statistic.MultipleComparisonAnalyzer
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.WeightedStatistic

// ── Demo 1: Full simulation report ───────────────────────────────────────────

/**
 * Runs a Drive-Through Pharmacy simulation and opens the full report in a browser.
 * Also writes HTML, Markdown, and plain-text copies to kslOutput/.
 */
fun demoSimulationReport() {
    val myModel = Model("Drive-Through Pharmacy")
    myModel.numberOfReplications = 30
    myModel.lengthOfReplication = 480.0   // 8-hour shift
    myModel.lengthOfReplicationWarmUp = 60.0
    DriveThroughPharmacyWithQ(myModel)
    myModel.simulate()

    val myDoc = myModel.buildReport("Drive-Through Pharmacy — Simulation Results")
    myDoc.showInBrowser()
    myDoc.writeMarkdown()
    myDoc.writeText()
    println("Simulation report written to kslOutput/")
}

// ── Demo 2: Statistic detail card ─────────────────────────────────────────────

/**
 * Builds a detail-mode report for a single Statistic (all 18 diagnostic fields)
 * and opens it in a browser.
 */
fun demoStatisticReport() {
    // 100 observations drawn from Normal(10, 2)
    val myData = doubleArrayOf(
        9.57, 12.27, 9.58, 9.46, 10.74, 13.64, 14.40, 11.96, 6.27, 11.67,
        8.06,  9.15, 12.67,  5.56, 11.55,  8.09, 10.28, 11.88,  6.83,  7.76,
        8.07, 10.19,  6.61,  8.68, 10.29,  7.19, 13.73, 10.84, 11.20,  9.11,
        13.11, 11.46, 12.87, 11.61, 11.18,  9.97,  7.61, 10.40, 13.61, 10.19,
        11.05, 10.83, 11.35, 11.74,  7.87, 10.17,  7.19, 10.32, 11.88, 12.05,
        10.25, 12.36,  8.62, 10.88, 10.90,  9.79,  9.57, 10.67, 10.44, 11.71,
        10.68,  9.00, 11.15, 11.53, 12.66,  9.04,  8.34,  8.91,  8.95, 10.66,
         9.47, 11.91,  7.31, 10.35,  8.51, 15.06,  7.67,  9.63, 11.95,  8.75,
        10.59, 10.73, 11.61,  9.19, 11.78, 11.58,  8.77, 11.16,  9.87, 11.08,
        12.15,  8.17, 12.10, 10.79, 10.66, 10.78,  9.21, 13.04,  8.50,  7.77
    )
    val myStat = Statistic("Service Time (minutes)", myData)

    val myDoc = report("Service Time Analysis") {
        paragraph(
            "100 observations of customer service time. " +
            "True distribution: Normal(μ=10, σ=2)."
        )
        statistic(myStat, detail = true)
    }
    myDoc.showInBrowser()
    myDoc.writeHtml()
}

// ── Demo 3: Histogram ─────────────────────────────────────────────────────────

/**
 * Builds a histogram report for inter-arrival times sampled from Exponential(1)
 * and opens it in a browser.
 */
fun demoHistogramReport() {
    val myBreakpoints = doubleArrayOf(0.0, 0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0, Double.MAX_VALUE)
    val myHist = Histogram(myBreakpoints, name = "Inter-Arrival Time")
    // sample 500 Exponential(1) observations
    val myRv = ksl.utilities.random.rvariable.ExponentialRV(1.0)
    repeat(500) { myHist.collect(myRv.value) }

    val myDoc = report("Inter-Arrival Time Distribution") {
        paragraph("500 samples from Exponential(mean=1). Expected mean ≈ 1.0.")
        histogram(myHist)
    }
    myDoc.showInBrowser()
}

// ── Demo 4: Integer frequency distribution ────────────────────────────────────

/**
 * Builds a frequency report for a discrete distribution (number of customers
 * in queue on arrival, approximated with Poisson(2)) and opens it in a browser.
 */
fun demoFrequencyReport() {
    val myFreq = IntegerFrequency(name = "Customers in Queue on Arrival")
    val myRv = ksl.utilities.random.rvariable.PoissonRV(2.0)
    repeat(1000) { myFreq.collect(myRv.value.toInt()) }

    val myDoc = report("Queue Length Frequency Analysis") {
        paragraph("1 000 samples from Poisson(λ=2). Expected mean ≈ 2.0.")
        integerFrequency(myFreq)
    }
    myDoc.showInBrowser()
}

// ── Demo 5: Batch means ───────────────────────────────────────────────────────

/**
 * Builds a batch-means report for a long run of correlated observations and
 * opens it in a browser.
 */
fun demoBatchStatisticReport() {
    val myBs = BatchStatistic(theName = "Queue Waiting Time")
    // 2 000 positively correlated waiting-time observations (AR(1)-like)
    var myPrev = 5.0
    repeat(2000) {
        myPrev = 0.7 * myPrev + ksl.utilities.random.rvariable.NormalRV(0.0, 1.0).value
        myBs.collect(myPrev.coerceAtLeast(0.0))
    }

    val myDoc = report("Batch Means Analysis — Queue Waiting Time") {
        paragraph("2 000 correlated waiting-time observations, batch-means analysis.")
        batchStatistic(myBs)
    }
    myDoc.showInBrowser()
}

// ── Demo 6: Weighted statistic ────────────────────────────────────────────────

/**
 * Builds a weighted-statistic report for time-weighted server utilisation and
 * opens it in a browser.
 */
fun demoWeightedStatisticReport() {
    val myWs = WeightedStatistic("Server Utilisation (time-weighted)")
    // simulate time-weighted observations: (utilisation value, time in state)
    val myRv = ksl.utilities.random.rvariable.UniformRV(0.0, 1.0)
    val myTRv = ksl.utilities.random.rvariable.ExponentialRV(2.0)
    repeat(500) { myWs.collect(myRv.value, myTRv.value) }

    val myDoc = report("Server Utilisation Report") {
        paragraph("500 time-weighted observations of server utilisation.")
        weightedStatistic(myWs)
    }
    myDoc.showInBrowser()
}

// ── Demo 7: Multiple comparison analysis ─────────────────────────────────────

/**
 * Builds an MCA report for four simulated system alternatives and opens it
 * in a browser.
 *
 * Data taken from the existing DemoMCB example (throughput observations
 * across 10 replications for four system configurations).
 */
fun demoMcaReport() {
    val myData = mutableMapOf<String, DoubleArray>()
    myData["Config 1"] = doubleArrayOf(63.72, 32.24, 40.28, 36.94, 36.29, 56.94, 34.10, 63.36, 49.29, 87.20)
    myData["Config 2"] = doubleArrayOf(63.06, 31.78, 40.32, 37.71, 36.79, 57.93, 33.39, 62.92, 47.67, 80.79)
    myData["Config 3"] = doubleArrayOf(57.74, 29.65, 36.52, 35.71, 33.81, 51.54, 31.39, 57.24, 42.63, 67.27)
    myData["Config 4"] = doubleArrayOf(62.63, 31.56, 39.87, 37.35, 36.65, 57.15, 33.30, 62.21, 47.46, 79.60)
    val myMca = MultipleComparisonAnalyzer(myData, "System Throughput")

    val myDoc = report("Multiple Comparison Analysis — System Throughput") {
        paragraph(
            "Throughput (customers/hour) for 4 system configurations " +
            "across 10 replications. Goal: identify the configuration " +
            "with highest throughput."
        )
        multipleComparison(myMca, confidenceLevel = 0.95)
    }
    myDoc.showInBrowser()
    myDoc.writeMarkdown()
}

// ── Demo 8: Composite report ──────────────────────────────────────────────────

/**
 * Assembles several analyses into a single multi-section report document and
 * opens it in a browser.
 */
fun demoCompositeReport() {
    // --- simulation ---
    val myModel = Model("Composite Demo — Pharmacy")
    myModel.numberOfReplications = 20
    myModel.lengthOfReplication = 480.0
    myModel.lengthOfReplicationWarmUp = 60.0
    DriveThroughPharmacyWithQ(myModel)
    myModel.simulate()

    // --- standalone statistics ---
    val myData = doubleArrayOf(
        9.57, 12.27, 9.58, 9.46, 10.74, 13.64, 14.40, 11.96, 6.27, 11.67,
        8.06,  9.15, 12.67,  5.56, 11.55,  8.09, 10.28, 11.88,  6.83,  7.76
    )
    val myStat = Statistic("Historical Service Time (minutes)", myData)

    // --- MCA ---
    val myMcaData = mutableMapOf<String, DoubleArray>()
    myMcaData["Config 1"] = doubleArrayOf(63.72, 32.24, 40.28, 36.94, 36.29, 56.94, 34.10, 63.36, 49.29, 87.20)
    myMcaData["Config 2"] = doubleArrayOf(63.06, 31.78, 40.32, 37.71, 36.79, 57.93, 33.39, 62.92, 47.67, 80.79)
    myMcaData["Config 3"] = doubleArrayOf(57.74, 29.65, 36.52, 35.71, 33.81, 51.54, 31.39, 57.24, 42.63, 67.27)
    val myMca = MultipleComparisonAnalyzer(myMcaData, "Throughput Comparison")

    val myDoc = report("Drive-Through Pharmacy — Complete Analysis") {
        paragraph(
            "This composite report covers: (1) simulation output statistics, " +
            "(2) historical service-time data, and (3) a multiple comparison " +
            "of three system configurations."
        )

        // ── Simulation output ──────────────────────────────────────────────
        simulationResults(myModel.simulationReporter, myModel)

        // ── Historical data ────────────────────────────────────────────────
        section("Historical Service-Time Data") {
            paragraph("20 observations collected during a pilot study.")
            statistic(myStat, detail = false)
        }

        // ── Multiple comparison ────────────────────────────────────────────
        section("Configuration Comparison") {
            paragraph("Choosing among 3 alternative server configurations.")
            multipleComparison(myMca, confidenceLevel = 0.95)
        }
    }

    myDoc.showInBrowser()
    myDoc.writeHtml()
    myDoc.writeMarkdown()
    println("Composite report written to kslOutput/")
}

// ── Entry point ───────────────────────────────────────────────────────────────

fun main() {
    // Run each demo in sequence. Each opens a browser tab and writes files
    // to kslOutput/. Comment out any demos you don't want to run.
//    demoSimulationReport()
//    demoStatisticReport()
//    demoHistogramReport()
//    demoFrequencyReport()
//   demoBatchStatisticReport()
//    demoWeightedStatisticReport()
//    demoMcaReport()
    demoCompositeReport()
}
