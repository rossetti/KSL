package ksl.utilities.rootfinding

import ksl.utilities.Interval
import ksl.utilities.math.FunctionIfc
import ksl.utilities.math.FunctionalIterator
import ksl.utilities.math.KSLMath

/**
 *  @param func the function to search. The function must contain a root in the interval
 *  @param interval the interval to search
 *  @param initialPoint the initial point for the search, defaults to mid-point of the interval
 *  @param maxIter the maximum number of iterations allowed for the search, default = 100
 *  @param desiredPrec the desired precision of the search, default is KSLMath.defaultNumericalPrecision
 */
abstract class RootFinder(
    func: FunctionIfc,
    interval: Interval,
    initialPoint: Double = (interval.lowerLimit + interval.upperLimit) / 2.0,
    maxIter: Int = 100,
    desiredPrec: Double = KSLMath.defaultNumericalPrecision
) :
    FunctionalIterator(func, maxIter, desiredPrec) {

    // check the constructor parameters
    init {
        require(Companion.hasRoot(func, interval)) {
            "The function does not have a root in the supplied interval $interval"
        }
        require(interval.contains(initialPoint)) {
            "The initial point $initialPoint was not in the supplied initial interval $interval"
        }
    }

    // make initial assignments

    /**
     * The initial point for the search
     *
     */
    protected var initialPt = initialPoint

    /**
     *  The interval to search for the root
     */
    protected var interval: Interval = interval

    /**
     * Value at which the function's value is negative.
     */
    protected var xNeg = 0.0

    /**
     * Value at which the function's value is positive.
     */
    protected var xPos = 0.0

    /**
     * The value of the function at xNeg
     */
    protected var fNeg = 0.0

    /**
     * The value of the function at xPos
     */
    protected var fPos = 0.0

    // set up the initial interval
    init {
        setupInterval(interval, initialPoint)
    }

    /**
     *  The interval must have a root for the function and the initial point must be within the interval
     *
     *  @param anInterval the interval to change to
     *  @param initialPoint the initial point within the interval, default is the mid-point of the interval
     */
    fun setupInterval(
        anInterval: Interval,
        initialPoint: Double = (anInterval.lowerLimit + anInterval.upperLimit) / 2.0
    ): Unit {
        require(Companion.hasRoot(func, anInterval)) {
            "The function does not have a root in the supplied interval $anInterval"
        }
        require(anInterval.contains(initialPt)) {
            "The initial point $initialPt was not in the supplied initial interval $anInterval"
        }
        val fL = func.f(anInterval.lowerLimit)
        val fU = func.f(anInterval.upperLimit)
        if (fL < 0.0) {
            fNeg = fL
            fPos = fU
            xNeg = anInterval.lowerLimit
            xPos = anInterval.upperLimit
        } else {
            fNeg = fU
            fPos = fL
            xPos = anInterval.lowerLimit
            xNeg = anInterval.upperLimit
        }
        initialPt = initialPoint
        interval = anInterval
    }

    companion object {
        /**
         * Used in the methods for finding intervals
         *
         */
        const val numIterations = 50

        /**
         * used in the methods for finding intervals
         *
         */
        const val searchFactor = 1.6

        /**
         * Returns true if the supplied interval contains a root
         *
         * @param func the function to check
         * @param xLower the lower limit of the interval to check
         * @param xUpper the upper limit of the interval to check
         * @return true if there is a root for the function in the interval
         */
        fun hasRoot(func: FunctionIfc, xLower: Double, xUpper: Double): Boolean {
            if (xLower >= xUpper) {
                return false
            }
            val fL = func.f(xLower)
            val fU = func.f(xUpper)
            return fL * fU <= 0
        }

        /**
         * Returns true if the supplied interval contains a root
         *
         * @param func the function to check
         * @param interval the interval to check
         * @return true if there is a root for the function in the interval
         */
        fun hasRoot(func: FunctionIfc, interval: Interval): Boolean {
            return hasRoot(func, interval.lowerLimit, interval.upperLimit)
        }

        /**
         * Using the supplied function and the initial interval provided, try to
         * find a bracketing interval by expanding the interval outward
         *
         * @param func the function to evaluate
         * @param interval the starting interval, which is mutated
         * @param numIter the number of iterations outward
         * @param searchFact the expansion factor
         * @return true if one is found
         */
        fun findInterval(
            func: FunctionIfc, interval: Interval,
            numIter: Int = numIterations,
            searchFact: Double = searchFactor
        ): Boolean {
            var x1 = interval.lowerLimit
            var x2 = interval.upperLimit
            var f1 = func.f(x1)
            var f2 = func.f(x2)
            for (j in 1..numIter) {
                if (f1 * f2 < 0) {
                    interval.setInterval(x1, x2)
                    return true
                } else {
                    if (Math.abs(f1) < Math.abs(f2)) {
                        x1 = x1 + searchFact * (x1 - x2)
                        f1 = func.f(x1)
                    } else {
                        x2 = x2 + searchFact * (x2 - x1)
                        f2 = func.f(x2)
                    }
                }
            }
            return false
        }

        /**
         * Given a function and a starting interval, subdivide the interval into n
         * subintervals and attempt to find nmax bracketing intervals that contain
         * roots
         *
         * @param func the function to evaluate
         * @param interval the starting (main) interval
         * @param n number of subdivisions of the main interval
         * @param nmax max number of bracketing intervals
         * @return The list of bracketing intervals
         */
        fun findInterval(
            func: FunctionIfc, interval: Interval, n: Int, nmax: Int
        ): List<Interval> {
            val intervals: MutableList<Interval> = ArrayList()
            val x1 = interval.lowerLimit
            val x2 = interval.upperLimit
            val dx = (x2 - x1) / n
            var x = x1
            var fp = func.f(x1)
            var fc: Double // = 0.0;
            for (j in 1..n) {
                x = x + dx
                fc = func.f(x)
                if (fc * fp <= 0.0) {
                    val i = Interval(x, x - dx)
                    intervals.add(i)
                    if (intervals.size == nmax) {
                        return intervals
                    }
                }
                fp = fc
            }
            return intervals
        }
    }

    /**
     * Returns true if the supplied interval contains a root
     *
     * @param xLower the lower limit of the interval to check
     * @param xUpper the upper limit of the interval to check
     * @return true if there is a root for the function in the interval
     */
    fun hasRoot(xLower: Double, xUpper: Double): Boolean {
        return Companion.hasRoot(func, xLower, xUpper)
    }

    /**
     * Returns true if the supplied interval contains a root
     *
     * @param interval the interval to check
     * @return true if there is a root for the function in the interval
     */
    fun hasRoot(interval: Interval): Boolean {
        return Companion.hasRoot(func, interval)
    }

    /**
     * Checks to see if the supplied point is within the search interval
     *
     * @param x the x to check
     * @return true of the search interval contains x
     */
    operator fun contains(x: Double): Boolean {
        return interval.contains(x)
    }

    override fun toString(): String {
        var sb = StringBuilder()
        sb.append("Root Finder").append(System.lineSeparator())
        sb.append("Search Interval = $interval").append(System.lineSeparator())
        sb.append("Initial point = $initialPt").append(System.lineSeparator())
        sb.append("Final root = $result").append(System.lineSeparator())
        sb.append("Maximum number of iterations allowed = $maximumIterations").append(System.lineSeparator())
        sb.append("Number of iterations executed = $iterationsExecuted").append(System.lineSeparator())
        sb.append("Converged? = ${hasConverged()}").append(System.lineSeparator())
        sb.append("Desired Precision = $desiredPrecision").append(System.lineSeparator())
        sb.append("Actual Precision = $achievedPrecision").append(System.lineSeparator())
        sb.append("Last negative f(x) = $fNeg").append(System.lineSeparator())
        sb.append("x at last negative f(x) = $xNeg").append(System.lineSeparator())
        sb.append("Last positive f(x) = $fPos").append(System.lineSeparator())
        sb.append("x at last positive f(x) = $xPos").append(System.lineSeparator())
        return sb.toString()
    }

    /**
     * The lower limit for the search interval
     */
    val intervalLowerLimit: Double
        get() = interval.lowerLimit

    /**
     * The upper limit for the search interval
     */
    val intervalUpperLimit: Double
        get() = interval.upperLimit
}