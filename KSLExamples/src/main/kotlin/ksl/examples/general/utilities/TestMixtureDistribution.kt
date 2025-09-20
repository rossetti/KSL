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
    // test p = 0.75
    val p = 0.75
    val quantile = mixtureDistribution.invCDF(p)
    println("quantile = $quantile")
    val mean = mixtureDistribution.mean()
    val variance = mixtureDistribution.variance()
    println("mean = $mean")
    println("variance = $variance")
    val parameters = mixtureDistribution.parameters()
    println("parameters = ${parameters.joinToString(",")}")
}