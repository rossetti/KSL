package ksl.examples.general.utilities

import ksl.utilities.distributions.ContinuousDistributionIfc
import ksl.utilities.distributions.Exponential
import ksl.utilities.distributions.MixtureDistribution
import ksl.utilities.distributions.Normal
import ksl.utilities.distributions.fitting.scoring.BayesianInfoCriterionScoringModel

fun main() {
  //testMixtureDistribution1()

    testMixtureBICScore()
}

fun testMixtureDistribution1() {
    // test mixture
    val n = Normal()
    val e = Exponential()
    val list = listOf<ContinuousDistributionIfc>(n, e)
    val cdf = doubleArrayOf(0.5, 1.0)
    val mixtureDistribution = MixtureDistribution(list, cdf)
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

fun testMixtureBICScore(){
    val n = Normal()
    val e = Exponential(1.5)
    val list = listOf<ContinuousDistributionIfc>(n, e)
    val cdf = doubleArrayOf(0.3, 1.0)
    val mixtureDistribution = MixtureDistribution(list, cdf)
    val mixingRV = mixtureDistribution.randomVariable()
    val data = mixingRV.sample(200)
    val bicScoringModel = BayesianInfoCriterionScoringModel()
    val score = bicScoringModel.score(data, mixtureDistribution)
    println("Score:")
    println(score)
}