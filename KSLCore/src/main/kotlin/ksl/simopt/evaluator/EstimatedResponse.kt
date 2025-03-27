package ksl.simopt.evaluator

import ksl.utilities.statistics
import kotlin.math.sqrt

data class EstimatedResponse(
    val average: Double,
    val variance: Double,
    val count: Double
) {
    init {
        require(!average.isNaN()) {"The average was not a number."}
        require(!variance.isNaN()) {"The variance was not a number."}
        require(!count.isNaN()) {"The count was not a number."}
        require(variance >= 0.0 ) {"The variance must be >= 0.0"}
        require( count >= 1) { "The count must be >= 1" }
    }

    constructor(data: DoubleArray): this(
        data.statistics().average, data.statistics().variance, data.statistics().count)
    constructor(data: List<Double>): this(data.toDoubleArray())

    /**
     * Gets the sample standard deviation of the observations. Simply
     * the square root of variance
     *
     * @return A double representing the computed standard deviation or Double.NaN
     * if 1 or fewer observations.
     */
    val standardDeviation: Double
        get() = sqrt(variance)

    val standardError: Double
        get() = standardDeviation/sqrt(count)

    /**
     * Combine this estimate with another independent estimate
     * @param e
     * @return the merged estimate
     */
    fun merge(e: EstimatedResponse) : EstimatedResponse {
        val n = count + e.count
        val avg = ((average * count) + (e.average * e.count)) / n
        val v = variance + e.variance
        return EstimatedResponse(avg, v, n)
    }

    fun instance() : EstimatedResponse {
        return EstimatedResponse(average, variance, count)
    }
}