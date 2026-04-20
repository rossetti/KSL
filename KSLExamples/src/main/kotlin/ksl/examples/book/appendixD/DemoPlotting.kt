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

package ksl.examples.book.appendixD

import ksl.examples.book.chapter4.DriveThroughPharmacyWithQ
import ksl.examples.book.chapter5.PalletWorkCenter
import ksl.observers.ExperimentDataCollector
import ksl.observers.ReplicationDataCollector
import ksl.observers.ResponseTrace
import ksl.observers.welch.WelchFileObserver
import ksl.simulation.Model
import ksl.utilities.Interval
import ksl.utilities.io.dbutil.KSLDatabaseObserver
import ksl.utilities.io.plotting.*
import ksl.utilities.random.rvariable.BivariateNormalRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.NormalRV
import ksl.utilities.statistic.BoxPlotSummary
import ksl.utilities.statistic.Statistic

/**
 * This file demonstrates how to create many of the plots that
 * are available within the ksl.utilities.io.plotting package.
 * The KSL uses the [lets-plot library](https://github.com/JetBrains/lets-plot-kotlin)
 * as its underlying plotting platform.
 *
 * The KSL wraps the lets-plot functionality into a set of classes to create commonly
 * used plots within simulation. The KSL has classes that facilitate the construction of
 * the following plots.
 *
 *  - ACFPlot
 *  - BoxPlot
 *  - CDFDiffPlot
 *  - ConfidenceIntervalsPlot
 *  - DensityPlot
 *  - DiscreteCDFPlot
 *  - DotPlot
 *  - ECDFPlot
 *  - FitDistPlot
 *  - FunctionPlot
 *  - HistogramDensityPlot
 *  - HistogramPlot
 *  - IntegerFrequencyPlot
 *  - MultiBoxPlot
 *  - MultiSeriesScatterPlot
 *  - MultiSeriesStateVariablePlot
 *  - ObservationsPlot
 *  - PartialSumsPlot
 *  - PMFComparisonsPlot
 *  - PMFPlot
 *  - PPPlot
 *  - QQPlot
 *  - ScatterPlot
 *  - StateFrequencyPlot
 *  - StateVariablePlot
 *  - WelchPlot
 */
fun main() {
    demoResponseTraceNewAPI()
    demoMultiSeriesStateVariablePlot()
    demoMultiSeriesScatterPlot()
//    demoScatterPlot()
//    demoMultiBoxPlot()
//    demoConfidenceIntervalPlots()
//    demoStateVariablePlot()
//    demoStateVariablePlot2()
//    demoWelchPlotting()
}

fun demoScatterPlot() {
    val bvn = BivariateNormalRV(0.0, 1.0, 0.0, 1.0, 0.8)
    val data = bvn.sampleByColumn(1000)
    val plot = ScatterPlot(data[0], data[1])
    plot.showInBrowser(plotTitle =  "Scatter Plot of Bivariate Normal RV")
    plot.saveToFile("ScatterPlotDemo", plotTitle = "Scatter Plot of Bivariate Normal RV")
}

fun demoMultiBoxPlot() {
    val n = NormalRV()
    val m = mutableMapOf<String, BoxPlotSummary>()
    for (i in 1..5) {
        val bps = BoxPlotSummary(n.sample(200), "BPS$i")
        m[bps.name] = bps
    }
    val plot = MultiBoxPlot(m)
    plot.showInBrowser(plotTitle =  "Box Plots")
    plot.saveToFile("BoxPlotDemo", plotTitle = "Box Plots")
}

fun demoConfidenceIntervalPlots() {
    val n = NormalRV()
    val m = mutableMapOf<String, Interval>()
    for (i in 1..5) {
        val s = Statistic(n.sample(200))
        m[s.name] = s.confidenceInterval
    }
    val plot = ConfidenceIntervalsPlot(m, referencePoint = 0.0)
    plot.showInBrowser(plotTitle = "Confidence Intervals")
    plot.saveToFile("ConfidenceIntervalsPlot", plotTitle = "Confidence Intervals")
}

fun demoStateVariablePlot() {
    val t = doubleArrayOf(0.0, 2.0, 5.0, 11.0, 14.0, 17.0, 22.0, 26.0, 28.0, 31.0, 35.0, 36.0)
    val n = doubleArrayOf(0.0, 1.0, 0.0, 1.0, 2.0, 3.0, 4.0, 3.0, 2.0, 1.0, 0.0, 0.0)
    val plot = StateVariablePlot(n, t, "Response")
    plot.showInBrowser()
    plot.saveToFile("StateVariableDemo", plotTitle = "State Variable Plot")
}

fun demoStateVariablePlot2() {
    val model = Model("Pallet Processing", autoCSVReports = true)
    model.numberOfReplications = 2
    val palletWorkCenter = PalletWorkCenter(model)
    // demonstrate how to capture a trace of a response variable
    val trace = ResponseTrace(palletWorkCenter.numInSystem)
    // simulate the model
    model.simulate()
    val plot = StateVariablePlot(trace, repNum = 1, time = 50.0)
    plot.showInBrowser()
}

fun demoWelchPlotting() {
    val model = Model("Drive Through Pharmacy")
    // add DriveThroughPharmacy to the main model
    val dtp = DriveThroughPharmacyWithQ(model, 1)
    dtp.arrivalGenerator.initialTimeBtwEvents = ExponentialRV(1.0, 1)
    dtp.serviceRV.initialRandomSource = ExponentialRV(0.7, 2)
    val rvWelch = WelchFileObserver(dtp.systemTime, 1.0)
    val twWelch = WelchFileObserver(dtp.numInSystem, 10.0)
    model.numberOfReplications = 5
    model.lengthOfReplication = 50000.0
    model.simulate()
    println(rvWelch)
    println(twWelch)
    val rvFileAnalyzer = rvWelch.createWelchDataFileAnalyzer()
    val twFileAnalyzer = twWelch.createWelchDataFileAnalyzer()
    rvFileAnalyzer.createCSVWelchPlotDataFile()
    twFileAnalyzer.createCSVWelchPlotDataFile()
    val wp = WelchPlot(analyzer = rvFileAnalyzer)
    wp.defaultPlotDir = model.outputDirectory.plotDir
    wp.showInBrowser()
    val psp = PartialSumsPlot(rvFileAnalyzer)
    psp.showInBrowser()
}

/**
 * Demonstrates the step-1 [ResponseTrace] API additions:
 * - [ResponseTrace.isTimeWeighted] — distinguishes TWResponse from Response traces
 * - [ResponseTrace.replicationNumbers] — lists the replication numbers present in the trace
 * - [ResponseTrace.traceDataMap] (Int overload) — retrieves data for one replication
 *   within a specified time window
 * - [ResponseTrace.traceDataMaps] — retrieves data for a subset of replications at once
 *
 * Two traces are attached before simulation — one on a [ksl.modeling.variable.TWResponse]
 * (`numInSystem`) and one on a [ksl.modeling.variable.Response] (`systemTime`) — so that
 * both values of [ResponseTrace.isTimeWeighted] can be observed.
 */
fun demoResponseTraceNewAPI() {
    val myModel = Model("Drive Through Pharmacy — Trace API Demo")
    val myDtp = DriveThroughPharmacyWithQ(myModel, 1)
    myModel.numberOfReplications = 5
    myModel.lengthOfReplication = 200.0

    // Attach a trace to each response type before simulating
    val myTWTrace = ResponseTrace(myDtp.numInSystem)  // TWResponse
    val myRVTrace = ResponseTrace(myDtp.systemTime)   // Response
    myModel.simulate()

    // isTimeWeighted — should be true for numInSystem, false for systemTime
    println("numInSystem.isTimeWeighted = ${myTWTrace.isTimeWeighted}")
    println("systemTime.isTimeWeighted  = ${myRVTrace.isTimeWeighted}")
    println()

    // replicationNumbers — should list [1, 2, 3, 4, 5] for both traces
    println("TWTrace replication numbers : ${myTWTrace.replicationNumbers}")
    println("RVTrace replication numbers : ${myRVTrace.replicationNumbers}")
    println()

    // traceDataMap(Int, startTime, endTime) — windowed slice of replication 2
    val myWindow = myTWTrace.traceDataMap(repNum = 2, startTime = 50.0, endTime = 150.0)
    println("numInSystem rep 2  [50, 150] : ${myWindow["times"]!!.size} state changes")
    println()

    // traceDataMaps — non-consecutive subset of replications
    val myMaps = myRVTrace.traceDataMaps(repNums = listOf(1, 3, 5))
    println("systemTime observations per selected replication:")
    for ((myRep, myData) in myMaps) {
        println("  Rep $myRep : ${myData["values"]!!.size} observations")
    }
}

/**
 * Demonstrates [MultiSeriesStateVariablePlot] for a time-weighted response
 * ([ksl.modeling.variable.TWResponse]) using a [ResponseTrace] on `numInSystem`.
 *
 * **Plot A — all replications, full time range:**
 * Uses the [ResponseTrace] convenience constructor with default parameters so
 * that all five recorded replications are shown on one plot.
 *
 * **Plot B — selected replications with a time window:**
 * Replications 1, 3, and 5 are shown over the window t = 20 to t = 150,
 * demonstrating non-consecutive replication selection and time-window filtering.
 */
fun demoMultiSeriesStateVariablePlot() {
    val myModel = Model("Drive Through Pharmacy — Multi State Variable")
    val myDtp = DriveThroughPharmacyWithQ(myModel, 1)
    myModel.numberOfReplications = 5
    myModel.lengthOfReplication = 200.0
    val myTrace = ResponseTrace(myDtp.numInSystem)
    myModel.simulate()

    // Plot A: all replications, full time range (convenience constructor defaults)
    val myPlot1 = MultiSeriesStateVariablePlot(myTrace)
    myPlot1.title = "Number in System — All 5 Replications"
    myPlot1.showInBrowser()
    myPlot1.saveToFile("MultiSeriesStateVariablePlot_AllReps")

    // Plot B: selected replications with a time window
    val myPlot2 = MultiSeriesStateVariablePlot(
        responseTrace = myTrace,
        repNums       = listOf(1, 3, 5),
        startTime     = 20.0,
        endTime       = 150.0
    )
    myPlot2.title = "Number in System \u2014 Reps 1, 3, 5  (t = 20 to 150)"
    myPlot2.showInBrowser()
    myPlot2.saveToFile("MultiSeriesStateVariablePlot_SelectedReps")
}

/**
 * Demonstrates [MultiSeriesScatterPlot] for an observation-based response
 * ([ksl.modeling.variable.Response]) using a [ResponseTrace] on `systemTime`.
 *
 * Simulation time is shown on the x-axis so the temporal distribution of
 * observations is visible and the warm-up period can be seen directly.
 *
 * **Plot A — all replications, full time range:**
 * Uses the [ResponseTrace] convenience constructor with default parameters so
 * that all five recorded replications are shown on one plot.
 *
 * **Plot B — two replications, post warm-up only:**
 * Replications 2 and 4 are shown with `startTime = 20.0`, excluding observations
 * recorded before simulation time 20.  This is the most common practical use of
 * the time-window feature for observation-based responses: filtering out the
 * initial transient (warm-up) period.
 */
fun demoMultiSeriesScatterPlot() {
    val myModel = Model("Drive Through Pharmacy — Multi Scatter")
    val myDtp = DriveThroughPharmacyWithQ(myModel, 1)
    myModel.numberOfReplications = 5
    myModel.lengthOfReplication = 200.0
    val myTrace = ResponseTrace(myDtp.systemTime)
    myModel.simulate()

    // Plot A: all replications, full time range (convenience constructor defaults)
    val myPlot1 = MultiSeriesScatterPlot(myTrace)
    myPlot1.title = "System Time \u2014 All 5 Replications"
    myPlot1.showInBrowser()
    myPlot1.saveToFile("MultiSeriesScatterPlot_AllReps")

    // Plot B: two replications, post warm-up (startTime = 20.0)
    val myPlot2 = MultiSeriesScatterPlot(
        responseTrace = myTrace,
        repNums       = listOf(2, 4),
        startTime     = 20.0
    )
    myPlot2.title = "System Time \u2014 Reps 2 & 4, post warm-up (t \u2265 20)"
    myPlot2.showInBrowser()
    myPlot2.saveToFile("MultiSeriesScatterPlot_PostWarmup")
}