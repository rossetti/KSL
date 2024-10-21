package ksl.utilities.distributions

import ksl.utilities.Interval
import ksl.utilities.isStrictlyIncreasing
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.GetRVariableIfc
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.PWCEmpiricalRV
import ksl.utilities.random.rvariable.RVariableIfc

class PWCEmpiricalCDF(
    breakPoints: DoubleArray,
    proportions: DoubleArray = DoubleArray(breakPoints.size - 1) { 1.0 / (breakPoints.size - 1) },
    name: String? = null
) : Distribution(name), ContinuousDistributionIfc, InverseCDFIfc, GetRVariableIfc {

    private lateinit var myProportions: DoubleArray
    private lateinit var myBreakPoints: DoubleArray
    private lateinit var myCDFPoints: DoubleArray
    private lateinit var mySlopes: DoubleArray

    init {
        setParameters(breakPoints, proportions)
    }

    private fun setParameters(breakPoints: DoubleArray, proportions: DoubleArray){
        require(proportions.isNotEmpty()) { "There must be at least 1 interval" }
        require(breakPoints.size >= 2) { "There must be at least 2 break points" }
        require(proportions.size == (breakPoints.size - 1)) {
            "Improper array sizes: proportions.size = " +
                    "${proportions.size} should be 1 less than the number of breakpoints, and breakPoints.size = ${breakPoints.size} "
        }
        for (i in breakPoints.indices) {
            require(breakPoints[i].isFinite()) { " breakpoint[$i] is ${breakPoints[i]}" }
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

    override fun instance(): PWCEmpiricalCDF {
        return PWCEmpiricalCDF(myBreakPoints, myProportions, name)
    }

    override fun cdf(x: Double): Double {
        TODO("Not yet implemented")
    }

    override fun pdf(x: Double): Double {
        TODO("Not yet implemented")
    }

    override fun domain(): Interval {
        return Interval(myBreakPoints.first(), myBreakPoints.last())
    }

    override fun invCDF(p: Double): Double {
        TODO("Not yet implemented")
    }

    override fun mean(): Double {
        TODO("Not yet implemented")
    }

    override fun variance(): Double {
        TODO("Not yet implemented")
    }

    override fun parameters(params: DoubleArray) {
        require(params.isNotEmpty()) {"The array of parameters was empty"}
        val n = params[0].toInt()
        val b = DoubleArray(n)
        val p = DoubleArray(n-1)
        params.copyInto(b, endIndex = n)

        TODO("Not yet implemented")
    }

    override fun parameters(): DoubleArray {
        val list = mutableListOf<Double>()
        list.add(myBreakPoints.size.toDouble())
        list.addAll(myBreakPoints.toTypedArray())
        list.addAll(myProportions.toTypedArray())
        return list.toDoubleArray()
    }

    override fun randomVariable(stream: RNStreamIfc): PWCEmpiricalRV {
        return PWCEmpiricalRV(myProportions, myBreakPoints, stream)
    }
}