package ksl.utilities.distributions.fitting

import ksl.utilities.distributions.DiscreteDistributionIfc
import ksl.utilities.distributions.Poisson
import ksl.utilities.statistic.Histogram

class PoissonGoodnessOfFit(
    private val data: DoubleArray,
    theMean: Double,
    val numEstimatedParameters: Int = 1,
    breakPoints: DoubleArray? = null,
) {
    init {
        require(numEstimatedParameters >= 0) { "The number of estimated parameters must be >= 0" }
    }
    init {
        require(theMean > 0.0) { "The supplied mean must be > 0.0" }
    }
    var mean: Double = theMean
        set(value) {
            require(value > 0.0) { "The supplied mean must be > 0.0" }
            field = value
        }

    private val myBreakPoints: DoubleArray
    private val histogram: Histogram
    private val distribution = Poisson(mean)

    init {
        myBreakPoints = if (breakPoints == null){
            var bp = PMFModeler.equalizedCDFBreakPoints(data.size, distribution)
            // start the intervals at 0
            bp = Histogram.addLowerLimit(0.0, bp)
            // this ensures that 0 is the 2nd breakpoint
            bp = Histogram.addNegativeInfinity(bp)
            // this ensures that the last interval captures all remaining data
            bp = Histogram.addPositiveInfinity(bp)
            bp
        } else {
            breakPoints.copyOf()
        }
        histogram = Histogram(myBreakPoints)
        histogram.collect(data)

        for(bin in histogram.bins){
            val range = bin.openIntRange
            print(range)
            print("  ")
            println("P{${range}} = ${distribution.pmf(range)}")
        }

        println()

        for(bin in histogram.bins){
            val u = bin.upperLimit
            val l = bin.lowerLimit
            val p = distribution.cdf(u) - distribution.cdf(l)
            //val p = distribution.strictlyLessCDF(u) - distribution.cdf(l)
            println("$bin   p(bin) = $p     l = $l, u = $u")
        }

        println(histogram)
    }

}

fun main(){
    val dist = Poisson(5.0)

    println("pmf(${0..<1}) = ${dist.pmf(0..<1)}")

    for (i in 0..10){
        val p = dist.cdf(i) - dist.cdf(i-1)
        println("i = $i  p(i) = ${dist.pmf(i)}   cp(i) = ${dist.cdf(i)}   p = $p")
    }
    val rv = dist.randomVariable
    rv.advanceToNextSubStream()
    val data = rv.sample(200)

    PoissonGoodnessOfFit(data, theMean = 5.0)

}