package ksl.examples.general.utilities.bsexamples

import ksl.utilities.Interval
import ksl.utilities.distributions.Exponential
import ksl.utilities.distributions.Geometric
import ksl.utilities.random.robj.DPopulation
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.StatisticIfc
import ksl.utilities.statistic.averages

fun main(){
    bsExample1(5)

    bsExample1(50)
}

fun bsExample1(sampleSize : Int = 5) {
    require(sampleSize >= 1){"The sample size must be >= 1"}
    // make a population for illustrating bootstrapping
    //val g = Geometric(0.2)
    val g = Exponential(10.0)
    println("mean = ${g.mean()}")
    val rv = g.randomVariable
    val population = DPopulation(rv.sample(10000))
    // view the first 10 elements of the population
    println("First 10 elements of original population.")
    println(population.elements.take(10).joinToString())
    println()
    val ps = Statistic("PopulationStats", population.elements)
    println(ps)
    println()
    // take a sample of size 5 from the population
    val mainSample = population.sample(sampleSize)
    println("Sample of size $sampleSize from original population")
    println(mainSample.joinToString())
    println()
    // compute statistics on main sample
    val mainSampleStats = Statistic(mainSample)
    println(mainSampleStats)
    println()
    // make the sample our pseudo-population
    val samplePopulation = DPopulation(mainSample)
    // illustrate 3 bootstrap samples
    for (i in 1..3){
        val bootStrapSample = samplePopulation.sample(sampleSize)
        println("bootstrap sample_$i  = ${bootStrapSample.joinToString()} with average = ${bootStrapSample.average()}")
    }
    println()
    // now make a large number of bootstrap samples and compute their statistics
    val nb = 1000
    val bootStrapStatistics = mutableListOf<StatisticIfc>()
    samplePopulation.resetStartStream()
    for (i in 1..nb){
        val bootStrapSample = samplePopulation.sample(sampleSize)
        bootStrapStatistics.add(Statistic("bs_$i", bootStrapSample))
    }
    // Get all the averages from all the bootstrap sample
    val averages = bootStrapStatistics.averages()
    println("First 10 elements of bootstrap sample averages.")
    println(averages.take(10).joinToString())
    println()

    val lcl = Statistic.percentile(averages, 0.025)
    val ucl = Statistic.percentile(averages, 0.975)
    val ci = Interval(lcl, ucl)
    println("percentile ci = $ci")

    println()

    println(Statistic("BootStrap Averages", averages))

}