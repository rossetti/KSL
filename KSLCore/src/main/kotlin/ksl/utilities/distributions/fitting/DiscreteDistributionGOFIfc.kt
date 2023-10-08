package ksl.utilities.distributions.fitting

import ksl.utilities.distributions.DiscretePMFInRangeDistributionIfc
import ksl.utilities.statistic.HistogramIfc

interface DiscreteDistributionGOFIfc {
    val numEstimatedParameters: Int

    val distribution: DiscretePMFInRangeDistributionIfc
    val histogram: HistogramIfc
    val breakPoints: DoubleArray
    val binProbabilities: DoubleArray
    val expectedCounts: DoubleArray
    val binCounts: DoubleArray
    val dof: Int
    val chiSquaredTestStatistic: Double
    val chiSquaredPValue: Double

    fun chiSquaredTestResults(type1Error: Double = 0.05): String {
        require((0.0 < type1Error) && (type1Error < 1.0)) { "Type 1 error must be in (0,1)" }
        val sb = StringBuilder()
        sb.appendLine("Chi-Squared Test Results:")
        sb.append(String.format("%-20s %-10s %10s %10s", "Bin Range", "P(Bin)", "Observed", "Expected"))
        sb.appendLine()
        for ((i, bin) in histogram.bins.withIndex()) {
            val r = bin.openIntRange
            val o = bin.count
            val e = expectedCounts[i]
            val p = binProbabilities[i]
            val s = String.format("%-20s %-10f %10d %10f", r, p, o, e)
            sb.append(s)
            if (e <= 5){
                sb.append("\t *** Warning: expected <= 5 ***")
            }
            sb.appendLine()
        }
        sb.appendLine()
        sb.appendLine("Number of estimate parameters = $numEstimatedParameters")
        sb.appendLine("Number of intervals = ${histogram.numberBins}")
        sb.appendLine("Degrees of Freedom = $dof")
        sb.appendLine("Chi-Squared Test Statistic = $chiSquaredTestStatistic")
        sb.appendLine("P-value = $chiSquaredPValue")
        sb.appendLine("Hypothesis test of ${distribution}, at $type1Error level: ")
        if (chiSquaredPValue >= type1Error){
            sb.appendLine("The p-value = $chiSquaredPValue is >= $type1Error : Do not reject hypothesis.")
        } else {
            sb.appendLine("The p-value = $chiSquaredPValue is < $type1Error : Insufficient evidence to reject the null hypothesis")
        }
        return sb.toString()
    }
}