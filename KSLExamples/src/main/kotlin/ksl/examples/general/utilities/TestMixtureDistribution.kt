package ksl.examples.general.utilities

import ksl.utilities.distributions.ContinuousDistributionIfc
import ksl.utilities.distributions.Exponential
import ksl.utilities.distributions.MixtureDistribution
import ksl.utilities.distributions.Normal

fun main() {
  testMixtureDistribution1()
}

fun testMixtureDistribution1() {
    // test mixture
    val list = listOf<ContinuousDistributionIfc>(Normal(), Exponential())
    val cdf = doubleArrayOf(0.5, 1.0)
    val mixtureDistribution = MixtureDistribution(list, cdf)
    //val e = Exponential()
    // test p = 0.75
    //val p = 0.75
    for (i in 0..10){
        val p = i/10.0
        val quantile = mixtureDistribution.invCDF(p)
        //val quantile = e.invCDF(p)
        println("p = $p, quantile = $quantile")
    }

    val mean = mixtureDistribution.mean()
    val variance = mixtureDistribution.variance()
    println("mean = $mean")
    println("variance = $variance")
    val parameters = mixtureDistribution.parameters()
    println("parameters = ${parameters.joinToString(",")}")
}