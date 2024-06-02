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
//    demoScatterPlot()
//    demoMultiBoxPlot()
//    demoConfidenceIntervalPlots()
//    demoStateVariablePlot()
//    demoStateVariablePlot2()
    demoWelchPlotting()
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
    dtp.arrivalRV.initialRandomSource = ExponentialRV(1.0, 1)
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