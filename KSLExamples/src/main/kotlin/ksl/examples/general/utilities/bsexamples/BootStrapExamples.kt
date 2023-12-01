package ksl.examples.general.utilities.bsexamples

import ksl.utilities.Interval
import ksl.utilities.distributions.Exponential
import ksl.utilities.distributions.Geometric
import ksl.utilities.random.robj.DPopulation
import ksl.utilities.random.rvariable.EmpiricalRV
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.statistic.*
import ksl.utilities.statistics

fun main(){
 //   bsExample1()

    bsExample2()
}

fun bsExample1() {
    // make a population for illustrating bootstrapping
    val mainSample = doubleArrayOf(6.0, 7.0, 5.0, 1.0, 0.0, 4.0, 6.0, 0.0, 6.0, 1.0)
    println("Sample of size 10 from original population")
    println(mainSample.joinToString())
    println()
    // compute statistics on main sample
    val mainSampleStats = Statistic(mainSample)
    println("Main Sample")
    println("average = ${mainSampleStats.average}")
    println("90% CI = ${mainSampleStats.confidenceInterval(.90)}")
    println()
    // make the sample our pseudo-population
    val samplePopulation = DPopulation(mainSample)
    val bootStrapAverages = mutableListOf<Double>()
    // illustrate 10 bootstrap samples
    println("BootStrap Samples:")
    for (i in 1..10){
        val bootStrapSample = samplePopulation.sample(10)
        val avg = bootStrapSample.average()
        println("sample_$i  = (${bootStrapSample.joinToString()}) with average = $avg")
        bootStrapAverages.add(avg)
    }
    println()
    println("Bootstrap sample averages")
    println(bootStrapAverages.joinToString())
    println()
    val lcl = Statistic.percentile(bootStrapAverages.toDoubleArray(), 0.05)
    val ucl = Statistic.percentile(bootStrapAverages.toDoubleArray(), 0.95)
    val ci = Interval(lcl, ucl)
    println("Percentile based 90% ci = $ci")
}


fun bsExample2() {
    // make a population for illustrating bootstrapping
    val mainSample = doubleArrayOf(6.0, 7.0, 5.0, 1.0, 0.0, 4.0, 6.0, 0.0, 6.0, 1.0)
    println("Sample of size 10 from original population")
    println(mainSample.joinToString())
    println()
    // compute statistics on main sample
    val mainSampleStats = Statistic(mainSample)
    println("Main Sample")
    println("average = ${mainSampleStats.average}")
    println("90% CI = ${mainSampleStats.confidenceInterval(.90)}")
    println()
    // now to the bootstrapping
    val bs = Bootstrap(mainSample, estimator = BSEstimatorIfc.Average(), KSLRandom.rnStream(3))
    bs.generateSamples(400, numBootstrapTSamples = 399)
    println(bs)
}