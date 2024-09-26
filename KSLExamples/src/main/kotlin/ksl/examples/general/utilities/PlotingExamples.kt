package ksl.examples.general.utilities

import ksl.examples.book.chapter4.DriveThroughPharmacyWithQ
import ksl.observers.ResponseTrace
import ksl.observers.welch.WelchDataCollectorIfc
import ksl.observers.welch.WelchFileObserver
import ksl.simulation.Model
import ksl.utilities.Interval
import ksl.utilities.KSLArrays
import ksl.utilities.distributions.Binomial
import ksl.utilities.distributions.DEmpiricalCDF
import ksl.utilities.distributions.Normal
import ksl.utilities.io.KSL
import ksl.utilities.io.KSLFileUtil
import ksl.utilities.io.plotting.*
import ksl.utilities.multiplyConstant
import ksl.utilities.random.rvariable.BivariateNormalRV
import ksl.utilities.random.rvariable.DEmpiricalRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.NormalRV
import ksl.utilities.statistic.*
import org.jetbrains.kotlinx.dataframe.impl.asList
import org.jetbrains.letsPlot.*
import org.jetbrains.letsPlot.core.util.PlotHtmlExport
import org.jetbrains.letsPlot.core.util.PlotHtmlHelper
import org.jetbrains.letsPlot.export.VersionChecker
import org.jetbrains.letsPlot.geom.geomBar
import org.jetbrains.letsPlot.geom.geomPoint
import org.jetbrains.letsPlot.geom.geomRect
import org.jetbrains.letsPlot.geom.geomSegment
import org.jetbrains.letsPlot.intern.toSpec
import org.jetbrains.letsPlot.label.labs
import org.jetbrains.letsPlot.pos.positionDodge
import org.jetbrains.letsPlot.pos.positionNudge
import org.jetbrains.letsPlot.themes.theme
import java.awt.Desktop
import java.io.File
import java.io.FileWriter
import kotlin.math.exp

fun main() {
//    testPlot()
//    testScatterPlot()
    testBoxPlot()
//    testMultiBoxPlot()
//    testConfidenceIntervalPlots()
//    testFrequencyPlot()
//    testStateFrequencyPlot()
//    testPPandQQ_Plots()
//    testFunctionPlot()
//    testHistogramPlot()
//    testStateVariablePlot()
//    testWelchPlotting()
//          testObservationPlot()
//    testACFPlot()
//    testPMFPlot()
//    testCDFPlot()
//
//    testECDFPlot()
//
//    testFitDistPlot()
//
//    testCDFDiffPlot()
//
//    testComparePMFPlot()

//    temp()

//    temp2()

//    testShowPlot()
}

fun testShowPlot(){
    // only works if this dependency is added, plots can be shown in a plot viewer
    // https://mvnrepository.com/artifact/org.jetbrains.lets-plot/lets-plot-batik
   // implementation("org.jetbrains.lets-plot:lets-plot-batik:4.3.3")
    val n = NormalRV()
    val h = CachedHistogram(n.sample(1000))
    val hp = h.histogramPlot()
    val p = hp.buildPlot()
 //   p.show()
}

fun testPlot() {
    val data = mapOf<String, DoubleArray>(
        "xbar" to doubleArrayOf(0.2, 1.0, 1.5, 2.1, 2.6, 3.0, 3.5, 4.0),
        "ybar" to doubleArrayOf(1.0, 6.0, 2.0, 3.0, 4.0, 2.0, 2.0, 1.0),
    )
    val rect = mapOf<String, DoubleArray>(
        "xmin" to doubleArrayOf(0.0, 1.5, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0),
        "xmax" to doubleArrayOf(1.5, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0),
        "ymin" to doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
        "ymax" to doubleArrayOf(1.0, 6.0, 2.0, 3.0, 4.0, 2.0, 2.0, 1.0),
    )
//    val rect = mapOf<String, List<Double>>(
//        "xmin" to listOf(0.0),
//        "xmax" to listOf(1.5),
//        "ymin" to listOf(0.0),
//        "ymax" to listOf(1.0),
//    )
//    val p = ggplot(data) +
//            geomBar(stat = Stat.identity) {
//                x = "xbar"
//                y = "ybar"
//            }
    val p2 = ggplot(data) +
            geomBar(stat = Stat.identity, width = 0.1) {
                x = "xbar"
                y = "ybar"
            }
    val p = ggplot() +
            geomRect(rect) {
                xmin = "xmin"
                xmax = "xmax"
                ymin = "ymin"
                ymax = "ymax"
            }

//    val p = ggplot() +
//            geomRect(xmin = 0.0, xmax = 1.5, ymin = 0.0, ymax = 1.0) +
//            geomRect(xmin = 1.5, xmax = 2.0, ymin = 0.0, ymax = 2.0)

    PlotIfc.showPlotInBrowser(p)
    PlotIfc.showPlotInBrowser(p2)

}

fun testScatterPlot() {
    val bvn = BivariateNormalRV(0.0, 1.0, 0.0, 1.0, 0.8)
    val sample = Array(100) { DoubleArray(2) }

    val data = bvn.sampleByColumn(1000)

    val plot = ScatterPlot(data[0], data[1])

    plot.showInBrowser()

    plot.saveToFile("ScatterPlotTest", plotTitle = "This is a test of scatter plot")

    plot.showInBrowser(plotTitle = "This is a test of scatter plot")
}

fun testBoxPlot() {
    val boxPlotSummary = BoxPlotSummary(testData, "Some Data")

    val plot = BoxPlot(boxPlotSummary)
    plot.showInBrowser()
    plot.saveToFile("The boxplot")

    println(boxPlotSummary)
}

fun testMultiBoxPlot() {
    val n = NormalRV()
    val m = mutableMapOf<String, BoxPlotSummary>()
    for (i in 1..5) {
        val bps = BoxPlotSummary(n.sample(200), "BPS$i")
        m[bps.name] = bps
    }
    val plot = MultiBoxPlot(m)
    plot.showInBrowser()
    plot.saveToFile("The boxplots")
}

fun testConfidenceIntervalPlots() {
    val n = NormalRV()
    val m = mutableMapOf<String, Interval>()
    for (i in 1..5) {
        val s = Statistic(n.sample(200))
        m[s.name] = s.confidenceInterval
    }
    val plot = ConfidenceIntervalsPlot(m, referencePoint = 0.0)
    plot.showInBrowser()
    plot.saveToFile("The CI Plots")
}

fun testFrequencyPlot() {
    val freq = IntegerFrequency()
    val rv = DEmpiricalRV(doubleArrayOf(1.0, 2.0, 3.0), doubleArrayOf(0.2, 0.7, 1.0))
    for (i in 1..10000) {
        freq.collect(rv.value)
    }

    val fPlot = IntegerFrequencyPlot(freq)
    fPlot.showInBrowser()
    fPlot.saveToFile("The Frequency Plot")

    val pPlot = IntegerFrequencyPlot(freq, proportions = true)
    pPlot.showInBrowser()
    pPlot.saveToFile("The Proportion Plot")

    println(freq)
}

fun testStateFrequencyPlot() {
    val rv = DEmpiricalRV(doubleArrayOf(1.0, 2.0, 3.0), doubleArrayOf(0.2, 0.7, 1.0))
    val sf = StateFrequency(3)
    val states = sf.states
    for (i in 1..20000) {
        val x: Int = rv.value().toInt()
        sf.collect(states[x - 1])
    }

    val fPlot = StateFrequencyPlot(sf)
    fPlot.showInBrowser()
    fPlot.saveToFile("The State Frequency Plot")

    val pPlot = StateFrequencyPlot(sf, proportions = true)
    pPlot.showInBrowser()
    pPlot.saveToFile("The State Proportion Plot")

    println(sf)
}

fun testPPandQQ_Plots() {
    val nd = Normal(10.0, 1.0)
    val nRV = nd.randomVariable
    val data = nRV.sample(200)
    val qqPlot = QQPlot(data, nd)
    qqPlot.showInBrowser()
    qqPlot.saveToFile("The QQ Plot")

    val ppPlot = PPPlot(data, nd)
    ppPlot.showInBrowser()
    ppPlot.saveToFile("The PP Plot")
}

fun testFunctionPlot() {
    val fn = { x: Double -> x * x }
    val r = Interval(-1.0, 1.0)
    val fPlot = FunctionPlot(fn, r)
    fPlot.showInBrowser()
    fPlot.saveToFile("A function plot")

    fPlot.numPoints = 10
    fPlot.showInBrowser()
    fPlot.saveToFile("A function plot")
}

fun testHistogramPlot() {
    val d = ExponentialRV(2.0)

    val data = d.sample(1000)
    val h1 = Histogram.create(data)
    println(h1)
    val hp = HistogramPlot(h1, proportions = true)
    hp.showInBrowser()
    hp.saveToFile("The First Histogram Plot")

    val points = Histogram.createBreakPoints(0.0, 20, 0.25)
    val h2: HistogramIfc = Histogram(Histogram.addPositiveInfinity(points))
    h2.collect(data)
    println(h2)
    val hp2 = HistogramPlot(h2)
    hp2.showInBrowser()
    hp2.saveToFile("The Second Histogram Plot")

    val d2 = data.multiplyConstant(-1.0)
    val pts = points.multiplyConstant(-1.0)
    pts.sort()
    val h3: HistogramIfc = Histogram(Histogram.addNegativeInfinity(pts))
    h3.collect(d2)
    println(h3)
    val hp3 = HistogramPlot(h3)
    hp3.showInBrowser()
    hp3.saveToFile("The Third Histogram Plot")

}

fun testStateVariablePlot() {
    val t = doubleArrayOf(0.0, 2.0, 5.0, 11.0, 14.0, 17.0, 22.0, 26.0, 28.0, 31.0, 35.0, 36.0)
    val n = doubleArrayOf(0.0, 1.0, 0.0, 1.0, 2.0, 3.0, 4.0, 3.0, 2.0, 1.0, 0.0, 0.0)
    val plot = StateVariablePlot(n, t, "Response")
    plot.showInBrowser()
    plot.saveToFile("StateVariableDemo", plotTitle = "State Variable Plot")
}

fun testWelchPlotting() {
    val model = Model("Drive Through Pharmacy")
    // add DriveThroughPharmacy to the main model
    val dtp = DriveThroughPharmacyWithQ(model, 1)
    dtp.arrivalRV.initialRandomSource = ExponentialRV(1.0, 1)
    dtp.serviceRV.initialRandomSource = ExponentialRV(0.7, 2)

    val rt = ResponseTrace(dtp.numInSystem)

    rt.maxNumReplications = 2
    rt.maxRepObsTime = 100.0

    val rvWelch = WelchFileObserver(dtp.systemTime, 1.0)
    val twWelch = WelchFileObserver(dtp.numInSystem, 10.0)
    model.numberOfReplications = 5
    model.lengthOfReplication = 50000.0

    model.simulate()
    model.print()
    println(rvWelch)
    println(twWelch)

    val rvFileAnalyzer = rvWelch.createWelchDataFileAnalyzer()
    val twFileAnalyzer = twWelch.createWelchDataFileAnalyzer()

    rvFileAnalyzer.createCSVWelchPlotDataFile()
    twFileAnalyzer.createCSVWelchPlotDataFile()

    val wp = WelchPlot(analyzer = rvFileAnalyzer)
    wp.defaultPlotDir = model.outputDirectory.plotDir
    wp.showInBrowser()
    wp.saveToFile("SystemTimeWelchPlot")

//    val n = rvFileAnalyzer.minNumObservationsInReplications.toInt()
//    val wa = rvFileAnalyzer.batchWelchAverages()
//    val ps = Statistic.partialSums(wa)

    val psp = PartialSumsPlot(rvFileAnalyzer)
    psp.showInBrowser()
    psp.saveToFile("SystemTimePartialSumsPlot")

//    val traceValues = rt.traceDataMap(1.0, 100.0)
//    val v = traceValues["values"]!!
//    val t = traceValues["times"]!!
//    val plot = StateVariablePlot(v, t, "response")
    val plot = StateVariablePlot(rt, 1, 100.0)
    plot.showInBrowser()
    plot.saveToFile("StateVariablePlot", plotTitle = "This is a test of StateVariablePlot plot")

    println(rt.asDataFrame())
}

fun testObservationPlot() {
    val d = ExponentialRV(2.0)
    val data = d.sample(100)
//    val s = Statistic(data)
//    val me = 3.0 * s.standardError
//    val interval = Interval(s.average - me, s.average + me)
    val plot = ObservationsPlot(data)
    plot.showInBrowser()
    plot.saveToFile("ObservationsPlot", plotTitle = "This is a test of ObservationsPlot plot")

    plot.confidenceInterval()
    plot.showInBrowser()
}

fun testACFPlot() {
    val plot = ACFPlot(testData, 10)
    plot.showInBrowser()
    plot.saveToFile("ACFPlot", plotTitle = "This is a test of ACFPlot plot")
}

fun testPMFPlot() {
    val values = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
    val cdf = doubleArrayOf(1.0 / 6.0, 3.0 / 6.0, 5.0 / 6.0, 1.0)
    val de = DEmpiricalCDF(values, cdf)
    val plot = PMFPlot(de)
    plot.showInBrowser()
    plot.saveToFile("PMFPlot", plotTitle = "This is a test of PMFPlot plot")

}

fun testCDFPlot() {
    val values = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
    val cdf = doubleArrayOf(1.0 / 6.0, 3.0 / 6.0, 5.0 / 6.0, 1.0)
    val de = DEmpiricalCDF(values, cdf)
    val plot = DiscreteCDFPlot(de)
    plot.showInBrowser()
    plot.saveToFile("CDFPlot", plotTitle = "This is a test of CDFPlot plot")

}

fun testECDFPlot() {
    val n = Normal(10.3, 3.484)
    val cdf: ((Double) -> Double) = n::cdf
    val plot = ECDFPlot(testData, cdf)
    plot.showInBrowser()
    plot.saveToFile("ECDFPlot", plotTitle = "This is a test of ECDF plot")
}

fun testFitDistPlot() {
    val n = Normal(10.3, 3.484)
    val plot = FitDistPlot(testData, n, n)
    plot.showInBrowser()
    plot.saveToFile("FitDistPlot")
}

fun testCDFDiffPlot() {
    val n = Normal(10.3, 3.484)
    val plot = CDFDiffPlot(testData, n)
    plot.showInBrowser()
    plot.saveToFile("CDFDiffPlot")
}

fun testComparePMFPlot() {
    val bd = Binomial(0.8, 20)
    val rv = bd.randomVariable
    val sample = rv.sample(1000)
    val data = IntArray(sample.size) { sample[it].toInt() }
    val plot = PMFComparisonPlot(data, bd)
    plot.showInBrowser()
    plot.saveToFile("PMF Comparison Plot")
}

fun temp(){
    val dataMap = mapOf(
        "estimated" to listOf(0.2, 0.3, 0.4, 0.1),
        "observed" to listOf(1, 2, 3, 4),
        "probability" to listOf(0.19, 0.31, 0.42, 0.08),
        "values" to listOf(1, 2, 3, 4),
    )
    val pd = positionNudge(.1)
    var p = ggplot(dataMap) + theme().legendPositionRight() +
            geomPoint(color = "red", position = pd) {
                x = "observed"
                y = "estimated"
            } +
            geomPoint(color = "black") {
                x = "values"
                y = "probability"
            }
    for (i in 1..4) {
        p = p + geomSegment(yend = 0, color = "red", position = pd) {
            x = "observed"
            y = "estimated"
            xend = "observed"
        }
    }
    for (i in 1..4) {
        p = p + geomSegment(yend = 0, color = "black") {
            x = "values"
            y = "probability"
            xend = "values"
        }
    }
    p = p + labs(title = "some title", x = "xLabel", y = "yLabel") +
            ggsize(500, 350)

    val spec = p.toSpec()
    // Export: use PlotHtmlExport utility to generate dynamic HTML (optionally in iframe).
    val html = PlotHtmlExport.buildHtmlFromRawSpecs(
        spec, iFrame = true,
        scriptUrl = PlotHtmlHelper.scriptUrl(VersionChecker.letsPlotJsVersion)
    )
    val tmpDir = File("someDirectory")
    if (!tmpDir.exists()) {
        tmpDir.mkdir()
    }
    val file = File.createTempFile("someFileName", ".html", tmpDir)
    FileWriter(file).use {
        it.write(html)
    }
    val desktop = Desktop.getDesktop()
    desktop.browse(file.toURI())
}

fun temp2(){
    val dataMap = mapOf(
        "probability" to listOf(0.2, 0.3, 0.4, 0.1, 0.19, 0.31, 0.42, 0.08),
        "values" to listOf(1, 2, 3, 4,1, 2, 3, 4),
        "type" to listOf("t","t","t","t", "e", "e", "e", "e")
    )
    //val pd = positionNudge(.1)
    val pd = positionDodge(0.1)
    var p = ggplot(dataMap) + theme().legendPositionRight() +
            geomPoint(position = pd) {
                x = "values"
                y = "probability"
                color = "type"
            }
//    +
//            geomPoint(color = "black") {
//                x = "values"
//                y = "probability"
//            }
    for (i in 1..8) {
        p = p + geomSegment(yend = 0, position = pd) {
            x = "values"
            y = "probability"
            xend = "values"
            color = "type"
        }
    }
//    for (i in 1..4) {
//        p = p + geomSegment(yend = 0, color = "black") {
//            x = "values"
//            y = "probability"
//            xend = "values"
//        }
//    }
    p = p + labs(title = "some title", x = "xLabel", y = "yLabel") +
            ggsize(500, 350)

    val spec = p.toSpec()
    // Export: use PlotHtmlExport utility to generate dynamic HTML (optionally in iframe).
    val html = PlotHtmlExport.buildHtmlFromRawSpecs(
        spec, iFrame = true,
        scriptUrl = PlotHtmlHelper.scriptUrl(VersionChecker.letsPlotJsVersion)
    )
    val tmpDir = File("someDirectory")
    if (!tmpDir.exists()) {
        tmpDir.mkdir()
    }
    val file = File.createTempFile("someFileName", ".html", tmpDir)
    FileWriter(file).use {
        it.write(html)
    }
    val desktop = Desktop.getDesktop()
    desktop.browse(file.toURI())
}