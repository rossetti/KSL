package ksl.utilities.random.rvariable

import ksl.utilities.countGreaterThan
import ksl.utilities.isStrictlyIncreasing
import ksl.utilities.random.rng.RNStreamIfc

class PWCEmpiricalRV(
    proportions: DoubleArray,
    breakPoints: DoubleArray,
    stream: RNStreamIfc = KSLRandom.nextRNStream()
) : RVariable(stream) {
    private val myProportions: DoubleArray
    private val myBreakPoints: DoubleArray
    private val myCDFPoints: DoubleArray
    private val mySlopes: DoubleArray

    constructor(proportions: DoubleArray, breakPoints: DoubleArray, streamNum: Int) : this(
        proportions,
        breakPoints,
        KSLRandom.rnStream(streamNum)
    )

    init {
        // check for valid inputs, constants must be > 0, constants.length = breakPoints.length - 1
        require(proportions.countGreaterThan(0.0) == proportions.size) { "Supplied proportions were not all > 0.0" }
        require(proportions.size == (breakPoints.size - 1)) {
            "Improper array sizes: proportions.size = " +
                    "${proportions.size} should be 1 less than, and breakPoints.size =${breakPoints.size} "
        }
        // breakpoints must be strictly increasing
        require(breakPoints.isStrictlyIncreasing()) { "The break points must be strictly increasing" }
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