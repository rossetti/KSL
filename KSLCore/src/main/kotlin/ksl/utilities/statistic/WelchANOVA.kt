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

    constructor(dataMap: Map<String, DoubleArray>) : this(statisticalSummaries(dataMap))
 //   constructor(statistics: List<StatisticIfc>) : this(statistics.map { it as EstimatedResponseIfc })

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

    companion object {

        /**
         *  Computes the statistical summaries for the data within the map
         */
        @JvmStatic
        fun statisticalSummaries(dataMap: Map<String, DoubleArray>): List<EstimatedResponseIfc> {
            require(dataMap.isNotEmpty()) { "The map of data must not be empty" }
            val stats = Statistic.statisticalSummaries(dataMap)
            val list = mutableListOf<EstimatedResponseIfc>()
            for ((_, stat) in stats) {
                list.add(stat as EstimatedResponseIfc)
            }
            return list
        }
    }

}

fun main() {
    val dataMap = mapOf(
        "Group1" to doubleArrayOf(0.49959, 0.23457, 0.26505, 0.27910, 0.00000, 0.00000, 0.00000, 0.14109, 0.00000, 1.34099),
        "Group2" to doubleArrayOf(0.24792, 0.00000, 0.00000, 0.39062, 0.34841, 0.00000, 0.20690, 0.44428, 0.00000, 0.31802),
        "Group3" to doubleArrayOf(0.25089, 0.00000, 0.00000, 0.00000, 0.11459, 0.79480, 0.17655, 0.00000, 0.15860, 0.00000),
        "Group4" to doubleArrayOf(0.37667, 0.43561, 0.72968, 0.26285, 0.22526, 0.34903, 0.24482, 0.41096, 0.08679, 0.87532)
    )
    val anova = WelchANOVA(dataMap)
    anova.printSummary()
}