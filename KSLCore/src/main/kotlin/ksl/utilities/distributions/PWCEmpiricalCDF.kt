package ksl.utilities.distributions

import ksl.utilities.Interval
import ksl.utilities.isStrictlyIncreasing
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.GetRVariableIfc
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.PWCEmpiricalRV
import ksl.utilities.toCSVString

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

    private fun setParameters(breakPoints: DoubleArray, proportions: DoubleArray) {
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
        if (x < myBreakPoints.first())
            return 0.0
        if (x >= myBreakPoints.last()){
            return 1.0
        }
        var i = 1
        while (myBreakPoints[i] <= x) {
            i += 1
        }
        return ((x - myBreakPoints[i-1])/mySlopes[i]) + myCDFPoints[i - 1]
    }

    override fun pdf(x: Double): Double {
        if (x < myBreakPoints.first())
            return 0.0
        if (x > myBreakPoints.last()){
            return 0.0
        }
        var i = 1
        while (myBreakPoints[i] <= x) {
            i += 1
        }
        return myProportions[i]
    }

    override fun domain(): Interval {
        return Interval(myBreakPoints.first(), myBreakPoints.last())
    }

    override fun invCDF(p: Double): Double {
        require(!(p < 0.0 || p > 1.0)) { "Supplied probability was $p Probability must be [0,1]" }
        if (p <= 0.0) {
            return myBreakPoints.first()
        }
        if (p >= 1.0){
            return myBreakPoints.last()
        }
        val i = findInterval(p)
        // i = 1, 2, ..., n for number of intervals
        // generate from the selected portion of the F curve
        return myBreakPoints[i - 1] + mySlopes[i] * (p - myCDFPoints[i - 1])
    }

    private fun findInterval(u: Double): Int {
        var i = 1
        while (myCDFPoints[i] <= u) {
            i += 1
        }
        return i
    }

    override fun mean(): Double {
        var sum = 0.0
        for (i in 1..< myProportions.size) {
            sum =
                sum + myProportions[i] * ((myBreakPoints[i] * myBreakPoints[i]) - (myBreakPoints[i - 1] * myBreakPoints[i - 1]))
        }
        return 0.5 * sum
    }

    override fun variance(): Double {
        val m = mean()
        return secondMoment() - m * m
    }

    fun secondMoment(): Double {
        var sum = 0.0
        for (i in 1..< myProportions.size) {
            val xi3 = myBreakPoints[i] * myBreakPoints[i] * myBreakPoints[i]
            val xim13 = myBreakPoints[i - 1] * myBreakPoints[i - 1] * myBreakPoints[i - 1]
            sum = sum + myProportions[i] * (xi3 - xim13)
        }
        return sum / 3.0
    }

    override fun parameters(params: DoubleArray) {
        require(params.isNotEmpty()) { "The array of parameters was empty" }
        val n = params[0].toInt()
        val b = DoubleArray(n)
        val p = DoubleArray(n - 1)
        params.copyInto(b, endIndex = n)
        params.copyInto(p, startIndex = n+1, endIndex = 2*n)
        println("n = $n")
        println("b = ${b.toCSVString()}")
        println("p = ${b.toCSVString()}")
 //       TODO("Not yet implemented")
    }

    /**
     *  n = number of break points
     *  k = number of proportions k = n - 1
     *  param[0] = n
     *  param[1..n]
     *  param[(n+1)..(n+1+k)]
     */
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

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("Distribution: $name")
        sb.appendLine("mean = ${mean()}")
        sb.appendLine("2nd moment = ${secondMoment()}")
        sb.appendLine("variance = ${variance()}")
        sb.append("--------------------------------------------")
        sb.appendLine()
        sb.append(String.format("%8s %8s %8s %8s %8s", "LL", "UL", "P", "F(x)", "Slope"))
        sb.appendLine()
        for (i in 1..< myProportions.size) {
            val s = String.format("%5f %5f %5f %5f %5f", myBreakPoints[i-1], myBreakPoints[i], myProportions[i], myCDFPoints[i], mySlopes[i])
            sb.appendLine(s)
        }
        sb.append("-------------------------------------------")
        return sb.toString()
    }
}

fun main() {
    val b = doubleArrayOf(0.25, 0.5, 1.0, 1.5, 2.0)
    val p = doubleArrayOf(0.31, 0.10, 0.25, 0.34)
    val pwc =  PWCEmpiricalCDF(b, p)
    println(pwc)
    println()
    for (i in 1..30){
        val x = i/10.0
        val p = pwc.cdf(x)
        val iCDF = pwc.invCDF(p)
        println("F($x) = $p and invF($p) = $iCDF")
    }

    val params = pwc.parameters()
    println(params.joinToString())
    pwc.parameters(params)

}