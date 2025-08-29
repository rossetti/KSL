package ksl.examples.general.variables

import ksl.utilities.io.plotting.ScatterPlot
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.LatinHyperCubeSampler
import ksl.utilities.transpose


fun main() {

//    val results = KSLRandom.rLatinHyperCube(100, 2)
//    val data = results.transpose()
//    val plot = ScatterPlot(data[0], data[1])
//
//    plot.showInBrowser()

   val sampler = LatinHyperCubeSampler(10, 2)
    val d2 = sampler.sampleByColumn(1000)

    val plot2 = ScatterPlot(d2[0], d2[1])
    plot2.showInBrowser()
}