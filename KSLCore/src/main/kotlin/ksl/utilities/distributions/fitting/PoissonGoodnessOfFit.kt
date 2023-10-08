package ksl.utilities.distributions.fitting

import ksl.utilities.distributions.ChiSquaredDistribution
import ksl.utilities.distributions.Poisson
import ksl.utilities.statistic.Histogram
import ksl.utilities.statistic.HistogramIfc
import ksl.utilities.statistic.Statistic

class PoissonGoodnessOfFit(
    data: DoubleArray,
    mean: Double,
    numEstimatedParameters: Int = 1,
    breakPoints: DoubleArray = PMFModeler.makeZeroToInfinityBreakPoints(data.size, Poisson(mean)),
) : DiscretePMFGoodnessOfFit(data, Poisson(mean), numEstimatedParameters, breakPoints) {

    //TODO other tests for "Poisson-ness"
    val fishersIndexOfDispersion: Double
        get() {
            if (data.size == 1) {
                return 0.0
            }
            // n > 1
            val v = histogram.variance
            val a = histogram.average
            val n = histogram.count
            return ((n - 1.0) * v / a)
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
    // rv.advanceToNextSubStream()
    val data = rv.sample(500)

    val pf = PoissonGoodnessOfFit(data, mean = 5.0)

    println()
    println(pf.chiSquaredTestResults())

}