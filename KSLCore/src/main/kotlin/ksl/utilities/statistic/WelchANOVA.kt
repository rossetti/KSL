package ksl.utilities.statistic

import ksl.simopt.evaluator.EstimatedResponseIfc
import org.hipparchus.distribution.continuous.FDistribution
import org.jetbrains.kotlinx.dataframe.api.move
import org.jetbrains.kotlinx.dataframe.api.to
import org.jetbrains.kotlinx.dataframe.api.toDataFrame

/**
 * [Welch's ANOVA](https://real-statistics.com/one-way-analysis-of-variance-anova/welchs-procedure/) is a statistical test used to compare the means of two or more groups
 * when the assumption of equal variances between the groups is not met.
 * It is a robust alternative to the classic one-way ANOVA, which assumes equal variances.
 * Welch's ANOVA produces an F-statistic and a p-value.
 * The p-value indicates the probability of observing the differences in means (or more extreme differences)
 * if the null hypothesis (that all group means are equal) is true.
 * If the p-value is below a predetermined significance level (e.g., 0.05),
 * the null hypothesis is rejected, suggesting that at least one group mean is significantly different from the others.
 * @param groups the groups to be compared as a list of estimated responses. The list must have 2 or more elements,
 * and each group must have two or more observations.
 */
class WelchANOVA(
    val groups: List<EstimatedResponseIfc>,
) {
    init {
        require(groups.isNotEmpty()) { "The list of groups must not be empty" }
        require(groups.size > 1) { "The list of groups must have at least 2 elements" }
        for (i in groups.indices) {
            require(groups[i].count >= 2) { "The count for group $i must be greater than or equal to 2" }
        }
    }

    var type1ErrorCriteria = 0.05
        set(value) {
            require((0.0 < value) && (value < 1.0)) { "The type 1 error criteria must be between 0 and 1" }
            field = value
        }

    constructor(dataMap: Map<String, DoubleArray>) : this(EstimatedResponseIfc.statisticalSummaries(dataMap))

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
    val rejectH0: Boolean
        get() = pValue <= type1ErrorCriteria

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

    fun print() {
        print(toString())
    }

    override fun toString(): String {
        val sb = StringBuilder("Welch's ANOVA\n")
        sb.appendLine("==========================")
        sb.appendLine("Groups: ${groups.size}")
        sb.appendLine("Sum of weights: $sumOfWeights")
        sb.appendLine("Weighted mean: $weightedMean")
        sb.appendLine("Weights: ${myWeights.contentToString()}")
        sb.appendLine("Factor A: $factorA")
        sb.appendLine("Factor B: $factorB")
        sb.appendLine("DOF1: $dof1")
        sb.appendLine("DOF2: $dof2")
        sb.appendLine("Type 1 Error Criteria: $type1ErrorCriteria")
        sb.appendLine("F Value: $fValue")
        sb.appendLine("P Value: $pValue")
        sb.appendLine("Reject H0(all means equal): $rejectH0")
        sb.appendLine("==========================")
        sb.appendLine("Group Summary")
        sb.appendLine("==========================")
        var df = groups.toDataFrame()
        df = df.move { cols(2..3) }.to(0)
        df = df.move { col(3) }.to(2)
        sb.append(df.toString())
        return sb.toString()
    }

}