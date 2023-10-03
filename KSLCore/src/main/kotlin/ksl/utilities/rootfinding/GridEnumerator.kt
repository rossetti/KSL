package ksl.utilities.rootfinding

import ksl.utilities.Interval
import ksl.utilities.math.FunctionIfc
//import java.util.*

class GridEnumerator(theFunction: FunctionIfc) {
    /**
     * Function that will be evaluated
     */
    protected val function: FunctionIfc = theFunction
    protected val points: MutableList<Evaluation> = ArrayList()

    /**
     * Evaluates the supplied function at the points in the array
     *
     * @param pointsArray the array of points to evaluate
     */
    fun evaluate(pointsArray: DoubleArray) {
        require(pointsArray.isNotEmpty()) { "The points array was empty" }
        points.clear()
        for (x in pointsArray) {
            points.add(Evaluation(x, function.f(x)))
        }
    }

    /** Evaluates the function at the end points and at n points equally
     * spaced within the interval.  If n = 0, then 2 evaluations occur
     * at both end points. If n = 1, then 3 evaluations occur
     * at mid-point and both end points. The grid division is determined
     * by the interval width divided by (n+1).
     *
     * @param theInterval the interval for evaluations, must not be null
     * @param numPoints the number of points within the interval to evaluate, must be greater than zero
     * @return the points that were evaluated
     */
    fun evaluate(theInterval: Interval, numPoints: Int): DoubleArray {
        require(numPoints >= 0) { "The number of points must be greater than zero" }
        val x = makePoints(theInterval, numPoints)
        evaluate(x)
        return x
    }

    /** Evaluates A set of points starting a lower limit and incrementing by delta
     *
     * @param lowerLimit the lower limit
     * @param delta the delta increment
     * @param numPoints the number of points after the lower limit, resulting in an upper limit
     * @return the points, including the lower limit at index 0
     */
    fun evaluate(lowerLimit: Double, delta: Double, numPoints: Int): DoubleArray {
        val x = makePoints(lowerLimit, delta, numPoints)
        evaluate(x)
        return x
    }

    /**
     *
     * @return an unmodifiable list of the evaluations in the order evaluated
     */
    val evaluations: List<Evaluation>
        get() = points

    /**
     *
     * @return a list of the evaluations sorted from smallest to largest
     */
    val sortedEvaluations: List<Evaluation>
        get() {
            val list: MutableList<Evaluation> = ArrayList()
            for (e in points) {
                list.add(e.newInstance())
            }
            list.sort()
            return list
        }

    /**
     *
     * @return the minimum evaluation
     */
    val minimum: Evaluation
        get() = points.min().newInstance()

    /**
     *
     * @return the maximum evaluation
     */
    val maximum: Evaluation
        get() = points.max().newInstance()

    override fun toString(): String {
        val sb = StringBuilder("GridEnumerator Evaluations")
        sb.append(System.lineSeparator())
        for (e in points) {
            sb.append(e.toString())
            sb.append(System.lineSeparator())
        }
        return sb.toString()
    }

    /**
     * An evaluation of the function at specific point
     */
    class Evaluation(val x: Double, val f: Double) : Comparable<Evaluation> {
        fun newInstance(): Evaluation {
            return Evaluation(x, f)
        }

        override operator fun compareTo(other: Evaluation): Int {
            return java.lang.Double.compare(f, other.f)
        }

        override fun toString(): String {
            val sb = StringBuilder("[")
            sb.append("x = ").append(x)
            sb.append(", f(x) = ").append(f)
            sb.append(']')
            return sb.toString()
        }
    }

    companion object {
        /** Makes a set of points for evaluation over the interval.  If n = 0, then 2 evaluations occur
         * at both end points. If n = 1, then 3 evaluations occur
         * at mid-point and both end points. The grid division is determined
         * by the interval width divided by (n+1).
         *
         * @param theInterval the interval for evaluations, must not be null
         * @param numPoints the number of points within the interval to evaluate, must be greater than zero
         */
        fun makePoints(theInterval: Interval, numPoints: Int): DoubleArray {
            require(numPoints >= 0) { "The number of points must be greater than zero" }
            val x = DoubleArray(numPoints + 2)
            val dx: Double = theInterval.width / (numPoints + 1)
            x[0] = theInterval.lowerLimit
            for (i in 1 until x.size) {
                x[i] = x[i - 1] + dx
            }
            return x
        }

        /** A set of points starting a lower limit and incrementing by delta
         *
         * @param lowerLimit the lower limit
         * @param delta the delta increment
         * @param numPoints the number of points after the lower limit, resulting in an upper limit
         * @return the points, including the lower limit at index 0
         */
        fun makePoints(lowerLimit: Double, delta: Double, numPoints: Int): DoubleArray {
            require(numPoints > 0) { "The number of points must be greater than zero" }
            require(!(delta <= 0)) { "The interval delta must be greater than zero" }
            val x = DoubleArray(numPoints + 1)
            x[0] = lowerLimit
            for (i in 1 until x.size) {
                x[i] = x[i - 1] + delta
            }
            return x
        }
    }
}
