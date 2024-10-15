package ksl.utilities.random.rvariable

import ksl.utilities.isStrictlyIncreasing
import ksl.utilities.random.rng.RNStreamIfc

/**
 *  Represents a piece-wise continuous empirical random variable specified via
 *  intervals defined by breakpoints and probabilities associated with each interval.
 *  A piecewise linear approximation forms the basis for the CDF where the breakpoints
 *  form the linear segments. There must be at least 1 interval (and two breakpoints).
 *
 *  @param breakPoints The break points defining the intervals such that p[j] is
 *  associated with breakpoints b[j] and b[j+1] for j = 0, 1,..., n-1, where n
 *  is the number of break points. The number of breakpoints should be 1 more than
 *  the number of proportions. The breakpoints must be strictly increasing.
 *  @param proportions A double array holding the proportion associated with the intervals defined
 *  by the breakpoints. All proportions must be strictly greater than 0 and strictly
 *  less than 1. If not supplied, the default is intervals with equal probability. That is,
 *  with proportion equal to 1.0/(n - 1).
 *  @param stream The random number stream.
 */
class PWCEmpiricalRV(
    breakPoints: DoubleArray,
    proportions: DoubleArray = DoubleArray(breakPoints.size - 1) { 1.0 / (breakPoints.size - 1) },
    stream: RNStreamIfc = KSLRandom.nextRNStream()
) : RVariable(stream) {
    private val myProportions: DoubleArray
    private val myBreakPoints: DoubleArray
    private val myCDFPoints: DoubleArray
    private val mySlopes: DoubleArray

    /**
     *  @param streamNum supplied the number of the stream instead of the stream
     */
    constructor(
        breakPoints: DoubleArray,
        proportions: DoubleArray = DoubleArray(breakPoints.size - 1) { 1.0 / (breakPoints.size - 1) },
        streamNum: Int) : this(
        breakPoints, proportions, KSLRandom.rnStream(streamNum)
    )

    init {
        require(proportions.isNotEmpty()) { "There must be at least 1 interval" }
        require(breakPoints.size >= 2) { "There must be at least 2 break points" }
        require(proportions.size == (breakPoints.size - 1)) {
            "Improper array sizes: proportions.size = " +
                    "${proportions.size} should be 1 less than the number of breakpoints, and breakPoints.size = ${breakPoints.size} "
        }
        // breakpoints must be strictly increasing
        require(breakPoints.isStrictlyIncreasing()) { "The break points must be strictly increasing" }
        for (i in proportions.indices) {
            require(0.0 < proportions[i]) { " proportion[$i] is <= 0.0" }
            require(proportions[i] < 1.0) { " proportion[$i] is >= 1.0" }
        }
        require(KSLRandom.isValidPMF(proportions)) { "The proportions do not represent a valid probability distribution" }
        // number of intervals/points
        val n = proportions.size
        // use 1-based indexing, 1 = 1st interval
        myProportions = DoubleArray(n + 1)
        proportions.copyInto(myProportions, 1)
        // x[0] is the left end point of the first interval, x[1] is the right end point of the first interval
        myBreakPoints = breakPoints.copyOf()
        myCDFPoints = DoubleArray(n + 1)
        mySlopes = DoubleArray(n + 1)
        // set up the cdf points
        myCDFPoints[0] = 0.0
        for (i in 1..n) {
            myCDFPoints[i] = myCDFPoints[i - 1] + myProportions[i]
            mySlopes[i] = (myBreakPoints[i] - myBreakPoints[i - 1]) / (myCDFPoints[i] - myCDFPoints[i - 1])
        }
    }

    override fun generate(): Double {
        // randomly pick the interval
        val u: Double = rnStream.randU01()
        val i = findInterval(u)
        // i = 1, 2, ..., n for number of intervals
        // generate from the selected portion of the F curve
        return myBreakPoints[i - 1] + mySlopes[i] * (u - myCDFPoints[i - 1])
    }

    private fun findInterval(u: Double): Int {
        var i = 1
        while (myCDFPoints[i] <= u) {
            i += 1
        }
        return i
    }

    override fun instance(stream: RNStreamIfc): RVariableIfc {
        return PWCEmpiricalRV(myProportions, myBreakPoints, stream)
    }
}