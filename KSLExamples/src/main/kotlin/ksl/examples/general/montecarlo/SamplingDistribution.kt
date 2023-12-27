package ksl.examples.general.montecarlo

import ksl.utilities.distributions.Normal
import ksl.utilities.io.plotting.FitDistPlot
import ksl.utilities.random.rvariable.NormalRV
import ksl.utilities.random.rvariable.UniformRV
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.averages
import ksl.utilities.statisticsByRow


/**
 * The purpose of these examples is to illustrate the generation
 * of a sampling distribution.
 */
fun main(){
    normalExample()
    uniformExample()
    confIntervalCoverage()
}

fun normalExample(){
    val x = NormalRV(5.0, 4.0)
    // create 50 samples of size 10
    val samples = x.sampleAsRows(10, 50)
    val stats = samples.statisticsByRow()
    val averages = stats.averages()
    val n = Normal(5.0, 4.0/10.0)
    val plot = FitDistPlot(averages, n, n)
    plot.showInBrowser()
    plot.saveToFile("SamplingDistForNorm")
}

fun uniformExample(){
    val u = UniformRV(10.0, 60.0)
    // create 50 samples of size 10
    val samples = u.sampleAsRows(10, 50)
    val stats = samples.statisticsByRow()
    val averages = stats.averages()
    val n = Normal(35.0, 20.83333)
    val plot = FitDistPlot(averages, n, n)
    plot.showInBrowser()
    plot.saveToFile("SamplingDistForUniform")
}

fun confIntervalCoverage(){
    val x = NormalRV(5.0, 4.0)
    // create 1000 samples of size 10
    val samples = x.sampleAsRows(10, 1000)
    val stats = samples.statisticsByRow()
    val ciStat = Statistic("Coverage Probability")
    for(stat in stats){
        ciStat.collect(stat.confidenceInterval.contains(5.0))
    }
    println(ciStat)

}