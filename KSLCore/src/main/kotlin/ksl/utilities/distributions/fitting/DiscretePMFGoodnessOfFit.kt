package ksl.utilities.distributions.fitting

import ksl.utilities.distributions.*
import ksl.utilities.multiplyConstant
import ksl.utilities.removeAt
import ksl.utilities.statistic.Histogram
import ksl.utilities.statistic.HistogramIfc
import ksl.utilities.statistic.Statistic

open class DiscretePMFGoodnessOfFit(
    data: DoubleArray,
    final override val distribution: DiscretePMFInRangeDistributionIfc,
    numEstimatedParameters: Int = 1,
    breakPoints: DoubleArray = PMFModeler.makeZeroToInfinityBreakPoints(data.size, distribution),
) : DistributionGOF(data, numEstimatedParameters, breakPoints), DiscreteDistributionGOFIfc {

    final override val binProbabilities = histogram.binProbabilities(distribution)

    final override val expectedCounts = binProbabilities.multiplyConstant(histogram.count)

    override fun toString(): String {
        val sb = StringBuilder().apply {
            append(chiSquaredTestResults())
        }
        return sb.toString()
    }
}

fun main() {
     testPoisson()

 //    tesNegBinomial()

 //   testBinomial()
}

fun testPoisson() {
    val dist = Poisson(5.0)
    val rv = dist.randomVariable
    rv.advanceToNextSubStream()
    val data = rv.sample(200)
    var breakPoints = PMFModeler.makeZeroToInfinityBreakPoints(data.size, dist)
//    breakPoints = breakPoints.removeAt(1)
//    breakPoints = breakPoints.removeAt(1)
    val pf = DiscretePMFGoodnessOfFit(data, dist, breakPoints = breakPoints)
    println()
    println(pf.chiSquaredTestResults())
}

fun tesNegBinomial() {
    val dist = NegativeBinomial(0.2, theNumSuccesses = 4.0)
    val rv = dist.randomVariable
    rv.advanceToNextSubStream()
    val data = rv.sample(200)
    var breakPoints = PMFModeler.makeZeroToInfinityBreakPoints(data.size, dist)
//    breakPoints = breakPoints.removeAt(1)
//    breakPoints = breakPoints.removeAt(1)
    val pf = DiscretePMFGoodnessOfFit(data, dist, breakPoints = breakPoints)
    println()
    println(pf.chiSquaredTestResults())
}

fun testBinomial() {
    val dist = Binomial(0.2, nTrials = 100)
    val rv = dist.randomVariable
    rv.advanceToNextSubStream()
    val data = rv.sample(200)
    var breakPoints = PMFModeler.makeZeroToInfinityBreakPoints(data.size, dist)
    println("break points: ")
    println(breakPoints.joinToString())
//    breakPoints = breakPoints.removeAt(1)
//    breakPoints = breakPoints.removeAt(1)
    val pf = DiscretePMFGoodnessOfFit(data, dist, breakPoints = breakPoints)
    println("constructed goodness of fit")
    println(pf.chiSquaredTestResults())
}