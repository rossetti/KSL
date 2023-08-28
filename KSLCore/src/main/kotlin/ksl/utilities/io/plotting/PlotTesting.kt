package ksl.utilities.io.plotting

import ksl.utilities.random.rvariable.BivariateNormalRV


class PlotTesting {
}

fun main(){
    val bvn = BivariateNormalRV(0.0, 1.0, 0.0, 1.0, 0.8)
    val sample = Array(100){DoubleArray(2)}

    val data = bvn.sampleByColumn(1000)

    val plot = ScatterPlot(data[0], data[1])

    plot.showInBrowser()

    plot.saveToFile("ScatterPlotTest", plotTitle = "This is a test of scatter plot")

    plot.showInBrowser(plotTitle = "This is a test of scatter plot")
}