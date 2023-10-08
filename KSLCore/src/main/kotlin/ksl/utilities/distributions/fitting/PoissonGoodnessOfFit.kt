package ksl.utilities.distributions.fitting

import ksl.utilities.distributions.ChiSquaredDistribution
import ksl.utilities.distributions.Poisson
import ksl.utilities.statistic.Histogram
import ksl.utilities.statistic.Statistic

class PoissonGoodnessOfFit(
    private val data: DoubleArray,
    val mean: Double,
    val numEstimatedParameters: Int = 1,
    breakPoints: DoubleArray? = null,
) {
    init {
        require(numEstimatedParameters >= 0) { "The number of estimated parameters must be >= 0" }
        require(mean > 0.0) { "The supplied mean must be > 0.0" }
    }

    private val myBreakPoints: DoubleArray
    private val myHistogram: Histogram
    private val myDistribution = Poisson(mean)
    private val myBinProb: DoubleArray
    private val myExpectedCounts: DoubleArray

    init {
        myBreakPoints = breakPoints?.copyOf() ?: PMFModeler.makeZeroToInfinityBreakPoints(data.size, myDistribution)
        myHistogram = Histogram.create(data, myBreakPoints)
        val (ec, bp) = PMFModeler.expectedCounts(myHistogram, myDistribution)
        myExpectedCounts = ec
        myBinProb = bp
    }

    val breakPoints = myBreakPoints.copyOf()
    val expectedCounts = myExpectedCounts.copyOf()
    val binCounts = myHistogram.binCounts
    val dof = myHistogram.numberBins - 1 - numEstimatedParameters
    val chiSquaredTestStatistic = Statistic.chiSqTestStatistic(binCounts, myExpectedCounts)
    val chiSquaredPValue: Double

    init {
        val chiDist = ChiSquaredDistribution(dof.toDouble())
        chiSquaredPValue = chiDist.complementaryCDF(chiSquaredTestStatistic)
    }

    fun chiSquaredTestResults(type1Error: Double = 0.05): String {
        require((0.0 < type1Error) && (type1Error < 1.0)) { "Type 1 error must be in (0,1)" }
        val sb = StringBuilder()
        sb.appendLine("Chi-Squared Test Results:")
        sb.append(String.format("%-20s %-10s %10s %10s", "Bin Range", "P(Bin)", "Observed", "Expected"))
        sb.appendLine()
        for ((i, bin) in myHistogram.bins.withIndex()) {
            val r = bin.openIntRange
            val o = bin.count
            val e = expectedCounts[i]
            val p = myBinProb[i]
            val s = String.format("%-20s %-10f %10d %10f %n", r, p, o, e)
            sb.append(s)
        }
        sb.appendLine()
        sb.appendLine("Number of estimate parameters = $numEstimatedParameters")
        sb.appendLine("Number of intervals = ${myHistogram.numberBins}")
        sb.appendLine("Degrees of Freedom = $dof")
        sb.appendLine("Chi-Squared Test Statistic = $chiSquaredTestStatistic")
        sb.appendLine("P-value = $chiSquaredPValue")
        sb.append("Hypothesis test at $type1Error level: ")
        if (chiSquaredPValue >= type1Error){
            sb.appendLine("Do not reject hypothesis that data is Poisson($mean)")
        } else {
            sb.appendLine("Insufficient evidence to conclude that the data is Poisson($mean)")
        }
        return sb.toString()
    }
}

fun main() {
    val dist = Poisson(5.0)

    println("pmf(${0..<1}) = ${dist.probIn(0..<1)}")

    for (i in 0..10) {
        val p = dist.cdf(i) - dist.cdf(i - 1)
        println("i = $i  p(i) = ${dist.pmf(i)}   cp(i) = ${dist.cdf(i)}   p = $p")
    }
    val rv = dist.randomVariable
    rv.advanceToNextSubStream()
    val data = rv.sample(200)

    val pf = PoissonGoodnessOfFit(data, mean = 5.0)

    println()
    println(pf.chiSquaredTestResults())

}