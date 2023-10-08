package ksl.utilities.distributions.fitting

import ksl.utilities.distributions.*
import ksl.utilities.removeAt
import ksl.utilities.statistic.Histogram
import ksl.utilities.statistic.HistogramIfc
import ksl.utilities.statistic.Statistic

open class DiscretePMFGoodnessOfFit(
    private val data: DoubleArray,
    final override val distribution : DiscretePMFInRangeDistributionIfc,
    final override val numEstimatedParameters: Int = 1,
    breakPoints: DoubleArray = PMFModeler.makeZeroToInfinityBreakPoints(data.size, distribution),
) : DiscreteDistributionGOFIfc {
    init {
        require(numEstimatedParameters >= 0) { "The number of estimated parameters must be >= 0" }
    }

    private val myBreakPoints: DoubleArray = breakPoints.copyOf()
    private val myHistogram: Histogram = Histogram.create(data, myBreakPoints)
    private val myBinProb: DoubleArray
    private val myExpectedCounts: DoubleArray

    init {
        val (ec, bp) = PMFModeler.expectedCounts(myHistogram, distribution)
        myExpectedCounts = ec
        myBinProb = bp
    }

    override val histogram: HistogramIfc
        get() = myHistogram
    override val breakPoints = myBreakPoints.copyOf()
    override val binProbabilities: DoubleArray = myBinProb.copyOf()
    override val expectedCounts = myExpectedCounts.copyOf()
    final override val binCounts = myHistogram.binCounts
    final override val dof = myHistogram.numberBins - 1 - numEstimatedParameters
    final override val chiSquaredTestStatistic = Statistic.chiSqTestStatistic(binCounts, myExpectedCounts)
    final override val chiSquaredPValue: Double

    init {
        val chiDist = ChiSquaredDistribution(dof.toDouble())
        chiSquaredPValue = chiDist.complementaryCDF(chiSquaredTestStatistic)
    }

}

fun main() {
   // testPoisson()

   // tesNegBinomial()

    testBinomial()
}

fun testPoisson(){
    val dist = Poisson(5.0)
    val rv = dist.randomVariable
//    rv.advanceToNextSubStream()
    val data = rv.sample(200)
    var breakPoints = PMFModeler.makeZeroToInfinityBreakPoints(data.size, dist)
//    breakPoints = breakPoints.removeAt(1)
//    breakPoints = breakPoints.removeAt(1)
    val pf = DiscretePMFGoodnessOfFit(data, dist, breakPoints = breakPoints)
    println()
    println(pf.chiSquaredTestResults())
}

fun tesNegBinomial(){
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

fun testBinomial(){
    val dist = Binomial(0.2, nTrials = 100)
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