package ksl.examples.general.rvariable

import ksl.utilities.distributions.Gamma
import ksl.utilities.io.StatisticReporter
import ksl.utilities.io.plotting.ACFPlot
import ksl.utilities.io.plotting.ObservationsPlot
import ksl.utilities.io.plotting.PPPlot
import ksl.utilities.io.plotting.QQPlot
import ksl.utilities.io.toStatDataFrame
import ksl.utilities.io.writeToFile
import ksl.utilities.random.rvariable.NormalRV
import ksl.utilities.statistic.BoxPlotSummary
import ksl.utilities.statistic.CachedHistogram
import ksl.utilities.statistic.Statistic
import org.jetbrains.kotlinx.dataframe.io.toStandaloneHTML

fun main() {
    val normalRV = NormalRV()
    val dof = 5
    val sampleSize = 1000
    val data = sampleChiSquared(sampleSize, dof, normalRV)
    val statistics = Statistic(data)
    println(statistics)
    val sr = StatisticReporter(mutableListOf(statistics))
    println(sr.halfWidthSummaryReport())
    statistics.toStatDataFrame().toStandaloneHTML().openInBrowser()
//    makeAndDisplayPlots(data, dof)
    data.writeToFile("TheChiSquares.txt")
}

fun generateChiSquared(normalRV: NormalRV, dof: Int): Double {
    var sum = 0.0
    for (i in 1..dof){
        val z  = normalRV.value
        sum = sum + z*z
    }
    return sum
}

fun sampleChiSquared(sampleSize: Int, dof: Int, normalRV: NormalRV) : DoubleArray {
    val sample = mutableListOf<Double>()
    for (i in 1..sampleSize) {
        sample.add(generateChiSquared(normalRV, dof))
    }
    return sample.toDoubleArray()
}

fun makeAndDisplayPlots(data: DoubleArray, dof: Int) {
    val histogram = CachedHistogram(data)
    println(histogram)
    val hp = histogram.histogramPlot()
    hp.showInBrowser()
    val boxPlotSummary = BoxPlotSummary(data)
    println(boxPlotSummary)
    val boxPlot = boxPlotSummary.boxPlot()
    boxPlot.showInBrowser()
    val obsPlot = ObservationsPlot(data)
    obsPlot.showInBrowser()
    val acfPlot = ACFPlot(data)
    acfPlot.showInBrowser()
    val gDist = Gamma(shape = dof/2.0, scale = 2.0)
    val ppPlot = PPPlot(data, gDist)
    ppPlot.showInBrowser()
    val qqPlot = QQPlot(data, gDist)
    qqPlot.showInBrowser()
}