package ksl.utilities.statistic

import ksl.simopt.evaluator.EstimatedResponseIfc
import org.hipparchus.distribution.continuous.FDistribution

class WelchANOVA(
    val groups: List<EstimatedResponseIfc>
) {
    init {
        require(groups.isNotEmpty()) { "The list of groups must not be empty" }
        require(groups.size > 1) { "The list of groups must have at least 2 elements" }
        for (i in groups.indices) {
            require(groups[i].count >= 2) { "The count for group $i must be greater than or equal to 2" }
        }
    }

    private val myWeights = DoubleArray(groups.size) { 1.0 }
    private val factorA: Double
    private val factorB: Double
    val dof1 = groups.size - 1.0
    val dof2: Double
    val fValue: Double
    private val fDistribution: FDistribution
    val pValue: Double
    var sumOfWeights: Double = 0.0
        private set
    var weightedMean: Double = 0.0
        private set

    init {
        for (i in groups.indices) {
            myWeights[i] = groups[i].count / groups[i].variance
            sumOfWeights = sumOfWeights + myWeights[i]
            weightedMean = weightedMean + myWeights[i] * groups[i].average
        }
        weightedMean = weightedMean / sumOfWeights
        var sA = 0.0
        var sB = 0.0
        for (i in groups.indices) {
            sA = sA + myWeights[i] * (groups[i].average - weightedMean) * (groups[i].average - weightedMean)
            var d = 1.0 - (myWeights[i] / sumOfWeights)
            sB = sB + (d * d) / (groups[i].count - 1.0)
        }
        val k = groups.size
        factorA = sA / (k - 1)
        factorB = (2.0 * (k - 2.0) / (k * k - 1.0)) * sB
        dof2 = (k * k - 1.0) / (3.0 * sB)
        fValue = factorA / (1.0 + factorB)
        fDistribution = FDistribution(dof1, dof2)
        pValue = 1.0 - fDistribution.cumulativeProbability(fValue)
    }

    fun printSummary() {
        println("Welch's ANOVA")
        println("==========================")
        println("Groups: ${groups.size}")
        println("Sum of weights: $sumOfWeights")
        println("Weighted mean: $weightedMean")
        println("Weights: ${myWeights.contentToString()}")
        println("Factor A: $factorA")
        println("Factor B: $factorB")
        println("DOF1: $dof1")
        println("DOF2: $dof2")
        println("F Value: $fValue")
        println("P Value: $pValue")
        println("==========================")
    }

}