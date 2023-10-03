package ksl.utilities.distributions

import ksl.utilities.Interval

abstract class MVCDF(nDim: Int) {
    /**
     * the dimension of the MV distribution
     */
    val dimension: Int

    // stores the lower limits for cdf computation
    protected val a: DoubleArray

    // stores the upper limits for cdf computation
    protected val b: DoubleArray

    init {
        require(nDim > 1) { "The dimension must be >= 2" }
        dimension = nDim
        a = DoubleArray(nDim)
        b = DoubleArray(nDim)
        for (i in 0 until nDim) {
            a[i] = Double.NEGATIVE_INFINITY
            b[i] = Double.POSITIVE_INFINITY
        }
    }

    /** Implementors must provide computation for computing the
     * value of the CDF across whatever domain limits as specified
     * by the integration limits supplied to the cdf() method
     *
     * @return the computed value of the CDF
     */
    protected abstract fun computeCDF(): Double

    /**
     * Evaluation of the integral. Accuracy should be about 7 decimal places
     *
     * @param integrands the integrands for the computation, must not be null
     * @return the computed value
     */
    fun cdf(integrands: List<Interval>): Double {
        val limits = createIntegrationLimits(integrands)
        return cdf(limits[0], limits[1])
    }

    /** Computes the CDF over the rectangular region
     *
     * @param lower (common) lower limit
     * @param upper (common) upper limit
     * @return the computed probability
     */
    fun cdf(lower: Double, upper: Double): Double {
        setIntegrationLimits(lower, upper)
        return computeCDF()
    }

    /** The probability from -infinity to the upper limit, with
     * the upper limit being the same for all dimensions
     *
     * @param upperLimit the (common) upper limit
     * @return the computed probability
     */
    fun cdf(upperLimit: Double): Double {
        setIntegrationLimits(Double.NEGATIVE_INFINITY, upperLimit)
        return computeCDF()
    }

    /**
     * Evaluation of the integral. Accuracy should be about 7 decimal places
     *
     * @param lowerLimits the lower limits for the computation, must not be null
     * @param upperLimits the upper limits for the computation, must not be null
     * @return the computed value
     */
    fun cdf(lowerLimits: DoubleArray, upperLimits: DoubleArray): Double {
        setIntegrationLimits(lowerLimits, upperLimits)
        return computeCDF()
    }

    /**
     *
     * @param integrands the list of integrands for each dimension, must not be null
     * @return the limits in a 2-D array with row 0 as the lower limits and row 1 as the upper limits
     */
    fun createIntegrationLimits(integrands: List<Interval>): Array<DoubleArray> {
        require(integrands.size == dimension) { "The number of integrand intervals does not match the dimension of the distribution" }
        val limits = Array(2) { DoubleArray(dimension) }
        for (i in 0 until dimension) {
            limits[0][i] = integrands[i].lowerLimit
            limits[1][i] = integrands[i].upperLimit
        }
        return limits
    }

    protected fun setIntegrationLimits(lower: DoubleArray, upper: DoubleArray) {
        require(lower.size == upper.size) { "The integration limit arrays do not have the same length" }
        require(lower.size == dimension) { "The integration limit arrays are not of size = $dimension" }
        for (i in 0 until dimension) {
            require(lower[i] < upper[i]) {"The integration limit lower[$i] = ${lower[i]} was bigger than upper[$i] = ${upper[i]}"}
            a[i] = lower[i]
            b[i] = upper[i]
        }
    }

    /** Sets upper and lower limits to the supplied values
     *
     * @param lower the lower limits
     * @param upper the upper limits
     */
    protected fun setIntegrationLimits(lower: Double, upper: Double) {
        require(lower < upper) {"The integration limit lower = $lower was bigger than upper = $upper"}
        a.fill(lower)
        b.fill(upper)
    }

    /**
     * The upper limit will be Double.POSITIVE_INFINITY
     *
     * @param lowerLimit the (common) lower integration limit
     * @return a set of intervals for the computation of the CDF
     */
    fun createLowerIntervals(lowerLimit: Double): List<Interval> {
        return createIntervals(lowerLimit, Double.POSITIVE_INFINITY)
    }

    /**
     * The lower limit will be Double.NEGATIVE_INFINITY
     *
     * @param upperLimit the (common) upper integration limit
     * @return a set of intervals for the computation of the CDF
     */
    fun createUpperIntervals(upperLimit: Double): List<Interval> {
        return createIntervals(Double.NEGATIVE_INFINITY, upperLimit)
    }

    /**
     * @param lowerLimit the (common) lower limit, must be less than the upper limit
     * @param upperLimit the (common) upper integration limit
     * @return a set of intervals for the computation of the CDF
     */
    fun createIntervals(lowerLimit: Double, upperLimit: Double): List<Interval> {
        require(lowerLimit < upperLimit) { "The lower limit must be < upper limit" }
        val list: MutableList<Interval> = ArrayList<Interval>()
        for (i in 0 until dimension) {
            list.add(Interval(lowerLimit, upperLimit))
        }
        return list
    }
}
