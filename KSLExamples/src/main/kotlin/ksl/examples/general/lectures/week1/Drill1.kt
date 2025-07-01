package ksl.examples.general.lectures.week1

import ksl.utilities.distributions.Gamma
import ksl.utilities.distributions.fitting.PDFModeler
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
import org.jetbrains.kotlinx.dataframe.io.toStandaloneHtml

fun main(){
    val normalRV = NormalRV(mean = 0.0, variance = 1.0)
    val dof = 5
    val sampleSize = 1000
    val data: DoubleArray = sampleChiSquared(sampleSize, dof, normalRV)
    val statistic = Statistic(name = "Chi-Squared Data", values = data)
    println(statistic)
    val sr = StatisticReporter(mutableListOf(statistic, statistic))
    println(sr.halfWidthSummaryReport())
    statistic.toStatDataFrame().toStandaloneHtml().openInBrowser()
    makeAndDisplayPlots(data, dof)
    fitDistribution(data)
    data.writeToFile("TheData.txt")
}

fun generateChiSquared(normalRV: NormalRV, dof: Int): Double {
    var sum = 0.0
    for (i in 1..dof){
        val z = normalRV.value
     //   sum = sum + normalRV.value*normalRV.value
        sum = sum + z*z
    }
    return sum
}

fun sampleChiSquared(sampleSize: Int, dof: Int, normalRV: NormalRV): DoubleArray {
    val sample = mutableListOf<Double>()
    for (i in 1..sampleSize){
        sample.add(generateChiSquared(normalRV, dof))
    }
    return sample.toDoubleArray()
}

fun makeAndDisplayPlots(data: DoubleArray, dof: Int){
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
    println(gDist)
    println()
    val ppPlot = PPPlot(data, gDist)
    ppPlot.showInBrowser()
    val qqPlot = QQPlot(data, gDist)
    qqPlot.showInBrowser()
}

fun fitDistribution(data: DoubleArray){
    val analysis = PDFModeler(data)
    analysis.showAllResultsInBrowser(automaticShifting = false)
}