package ksl.examples.book.chapter9.bsexamples

import ksl.utilities.Interval
import ksl.utilities.random.robj.DPopulation
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.NormalRV
import ksl.utilities.statistic.*

fun main(){
 //   bsExample1()
 //   bsExample2()
 //   bsExample3()
    bsExample4()
}

/**
 *  This example illustrates how to do simple bootstrapping "by hand"
 */
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

/**
 *  This example illustrates how to use the Bootstrap class to perform the same
 *  analysis has in example 1.
 */
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
    val bs = Bootstrap(mainSample, estimator = BSEstimatorIfc.Average(), streamNumber = 3)
    bs.generateSamples(400, numBootstrapTSamples = 399)
    println(bs)
}

/**
 *  This example illustrated how to use the BootstrapSampler class to bootstrap multiple
 *  statistical quantities from a single sample.
 */
fun bsExample3() {
    val ed = ExponentialRV(10.0)
    val data = ed.sample(50)
    val stat = Statistic(data)
    println(stat)
    println()
    val bss = BootstrapSampler(data, BasicStatistics())
    val estimates = bss.bootStrapEstimates(300)
    for(e in estimates){
        println(e.asString())
    }
}

/**
 *  This example illustrates how to use CaseBootStrapSampler to bootstrap a OLS
 *  regression model.
 */
fun bsExample4(){
    // first make some data for the example
    val n1 = NormalRV(10.0, 3.0)
    val n2 = NormalRV(5.0, 1.5)
    val e = NormalRV()
    // make a simple linear model with some error
    val data = Array<DoubleArray>(100) {
        val x1 = n1.value
        val x2 = n2.value
        val y = 10.0 + 2.0*x1 + 5.0*x2 + e.value
        doubleArrayOf(y, x1, x2)
    }
    //data.write()
    // apply bootstrapping to get bootstrap confidence intervals on regression parameters
    val cbs = CaseBootstrapSampler(MatrixBootEstimator(data, OLSBootEstimator))
    val estimates = cbs.bootStrapEstimates(399)
    // print out the bootstrap estimates
    for(be in estimates){
        println(be)
    }

}