package ksl.simopt.evaluator

import kotlinx.serialization.Serializable
import ksl.utilities.Interval
import ksl.utilities.distributions.StudentT
import ksl.utilities.statistic.DEFAULT_CONFIDENCE_LEVEL
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistics
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator
import kotlin.math.sqrt

/**
 *  An interface to define basic statistics for something that is estimated.
 */
interface EstimatedResponseIfc {
    /**
     * Gets the name of the Statistic
     *
     * @return The name as a String
     */
    val name: String

    /**
     *  @return a changeable label associated with the object
     */
    var label: String?

    /**
     * Gets the unweighted average of the observations.
     *
     * @return A double representing the average or Double.NaN if no
     * observations.
     */
    val average: Double

    /**
     * Gets the sample variance of the observations.
     *
     * @return A double representing the computed variance or Double.NaN if 1 or
     * fewer observations.
     */
    val variance: Double

    /**
     * Gets the count for the number of the observations.
     *
     * @return A double representing the count
     */
    val count: Double

    /**
     * Gets the sample standard deviation of the observations. Simply
     * the square root of variance
     *
     * @return A double representing the computed standard deviation or Double.NaN
     * if 1 or fewer observations.
     */
    val standardDeviation: Double
        get() = sqrt(variance)

    /**
     * Gets the standard error of the observations. Simply the generate standard
     * deviation divided by the square root of the number of observations
     *
     * @return A double representing the standard error or Double.NaN if &lt; 1
     * observation
     */
    val standardError: Double
        get() = if (count < 1.0) {
            Double.NaN
        } else standardDeviation / sqrt(count)

    /**
     * Gets the confidence interval half-width. Simply the standard error
     * times the confidence coefficient as determined by an appropriate sampling
     * distribution
     *
     * @param level the confidence level
     * @return A double representing the half-width or Double.NaN if &lt; 1
     * observation
     */
    fun halfWidth(level: Double): Double {
        require(!(level <= 0.0 || level >= 1.0)) { "Confidence Level must be (0,1)" }
        if (count <= 1.0) {
            return Double.NaN
        }
        val dof = count - 1.0
        val alpha = 1.0 - level
        val p = 1.0 - (alpha / 2.0)
        val t = StudentT.invCDF(dof, p)
        return t * standardError
    }

    /**
     *  Computes the pair-wise screening width assuming that the estimates are independent. This width is
     *  used to specify screening intervals within screening procedures. This quantity is the square root
     *  of the sum of squared half-widths of the estimates.
     *
     *  Based on:
     *   Boesel, Justin, Barry L. Nelson, and Seong-Hee Kim. 2003. “Using Ranking and Selection to ‘Clean Up’ after
     *   Simulation Optimization.” Operations Research 51 (5): 814–25. https://doi.org/10.1287/opre.51.5.814.16751.
     *
     * @param estimate the estimate to pair with
     * @param level the confidence level for the Student-T distribution computation of the half-width
     */
    @Suppress("unused")
    fun screeningWidth(estimate: EstimatedResponseIfc, level: Double = DEFAULT_CONFIDENCE_LEVEL): Double {
        val hw1 = halfWidth(level)
        val hw2 = estimate.halfWidth(level)
        return sqrt(hw1 * hw1 + hw2 * hw2)
    }

    companion object {

        /**
         *  Computes the statistical summaries for the data within the map
         */
        @JvmStatic
        fun statisticalSummaries(dataMap: Map<String, DoubleArray>): List<EstimatedResponseIfc> {
            require(dataMap.isNotEmpty()) { "The map of data must not be empty" }
            val stats = Statistic.statisticalSummaries(dataMap)
            val list = mutableListOf<EstimatedResponseIfc>()
            for ((_, stat) in stats) {
                list.add(stat as EstimatedResponseIfc)
            }
            return list
        }

        /**
         *  Constructs an approximate confidence interval on the difference between estimate 1 and estimate 2.
         *  The interval assumes normally distributed independent samples with unequal variances.
         *  @param estimate1 the first estimate
         *  @param estimate2 the second estimate
         *  @param level the confidence level. Must be between 0 and 1.
         *  @return the confidence interval
         */
        fun differenceConfidenceInterval(
            estimate1: EstimatedResponseIfc,
            estimate2: EstimatedResponseIfc,
            level: Double = DEFAULT_CONFIDENCE_LEVEL
        ) : Interval {
            require((0.0 < level) && (level < 1.0)) { "The confidence level must be between 0 and 1" }
            //   require( indifferenceZone >= 0.0) {"The indifference zone parameter must be >= 0.0"}
            val d = estimate1.average - estimate2.average
            val n1 = estimate1.count
            val n2 = estimate2.count
            val v1 = estimate1.variance / n1
            val v2 = estimate2.variance / n2
            val v = v1 + v2
            val dofNumerator = v * v
            val dofDenominator = ((v1 * v1) / (n1 + 1.0)) + ((v2 * v2) / (n2 + 1.0))
            val dof = (dofNumerator / dofDenominator) - 2.0
            val alpha = 1.0 - level
            val p = 1.0 - (alpha / 2.0)
            val t = StudentT.invCDF(dof, p)
            val se = sqrt(v)
            val ll = d - t * se
            val ul = d + t * se
            return Interval(ll, ul)
        }
    }
}

class EstimateResponseComparator(
    level: Double = DEFAULT_CONFIDENCE_LEVEL,
    indifferenceZone: Double = 0.0
) : Comparator<EstimatedResponseIfc> {
    init {
        require((0.0 < level) && (level < 1.0)) { "The confidence level must be between 0 and 1" }
        require( indifferenceZone >= 0.0) {"The indifference zone parameter must be >= 0.0"}
    }

    var confidenceLevel : Double = level
        set(value) {
            require((0.0 < value) && (value < 1.0)) { "The confidence level must be between 0 and 1" }
            field = value
        }

    var indifferenceZone: Double = indifferenceZone
        set(value) {
            require( value >= 0.0) {"The indifference zone parameter must be >= 0.0"}
            field = value
        }

    override fun compare(
        estimate1: EstimatedResponseIfc,
        estimate2: EstimatedResponseIfc
    ): Int {
        // make the confidence interval on the difference
        val ci = EstimatedResponseIfc.differenceConfidenceInterval(estimate1, estimate2, confidenceLevel)
        println("ci on difference: $ci")
        if (ci.upperLimit < indifferenceZone){
            return -1
        }
        if (ci.lowerLimit > indifferenceZone){
            return 1
        }
        if (ci.contains(indifferenceZone)){
            return 0
        }
        return 0
    }

}

/**
 *  Represents an estimated response based on an independent sample. For the case of sample size 1 (count equals 1),
 *  the variance will be undefined (Double.NaN).
 *
 *  @param name the name of the response that was estimated
 *  @param average the sample average of the sample
 *  @param variance the sample variance of the sample
 *  @param count the number of observations in the sample
 */
@Serializable
data class EstimatedResponse(
    override val name: String,
    override val average: Double,
    override val variance: Double,
    override val count: Double
) : EstimatedResponseIfc {
    init {
        require(name.isNotBlank()) { "The name of the response cannot be blank" }
        require(!average.isNaN() || average.isFinite()) { "The average was not a number or was infinite." }
        require(!count.isNaN() || count.isFinite()) { "The count was not a number or was infinite." }
        require((variance >= 0.0) || variance.isNaN()) { "The variance must be >= 0.0 or NaN" }
        require(count >= 1) { "The count must be >= 1" }
    }

    constructor(name: String, data: DoubleArray) : this(
        name, data.statistics().average, data.statistics().variance, data.statistics().count
    )

    constructor(name: String, data: List<Double>) : this(name, data.toDoubleArray())

    override var label: String? = null

    /**
     * Combine this estimate with another independent estimate
     * @param e the estimate to merge with this estimate
     * @return the merged estimate
     */
    fun merge(e: EstimatedResponse): EstimatedResponse {
        require(this.name == e.name) { "The names of the responses to merge must be the same" }
        val n = count + e.count
        val avg = ((average * count) + (e.average * e.count)) / n
        val v = pooledVariance(e)
        return EstimatedResponse(this.name, avg, v, n)
    }

    /**
     *  Computes the pooled variance when combining samples from the same population.
     *  The pooling process assumes a weighted average of the variances. In the case
     *  where each estimate only has count equal to 1, the variance is computed from the two data points.
     *  In the cases where the pooled sample will have 3 elements, the variance associated with the sample
     *  size of 2 is used. In the cases where both samples have 2 or more elements, a weighted
     *  pooled variance is computed.
     */
    fun pooledVariance(e: EstimatedResponse): Double {
        if ((count == 1.0) && (e.count == 1.0)) {
            // we have a sample of size 2 now, just average them
            val avg = (average + e.average) / 2.0
            val v = (average - avg) * (average - avg) + (e.average - avg) * (e.average - avg)
            return v
        } else if (count == 1.0 && e.count >= 2.0) {
            // Since count == 1, it is like a new data point. Use Welford's algorithm.
            var m2 = e.variance * (e.count - 1)// base m2 on e
            val n = count + e.count
            // e.average is the current mean, average is the new value
            val delta = (average - e.average)
            val mean = e.average + delta / n
            val delta2 = (average - mean)
            m2 = m2 + delta * delta2
            return m2 / (n - 1.0)
        } else if ((count >= 2.0) && (e.count == 1.0)) {
            // Since e.count == 1, "e" is new data point. Use Welford's algorithm.
            var m2 = variance * (count - 1)
            val n = count + e.count
            // average is the current mean, e.average is the new value
            val delta = (e.average - average)
            val mean = average + delta / n
            val delta2 = (e.average - mean)
            m2 = m2 + delta * delta2
            return m2 / (n - 1.0)
        } else {
            // both counts must be > 2
            val n = count + e.count
            val v = (count - 1.0) * variance + (e.count - 1.0) * e.variance
            return v / (n - 2.0)
        }
    }

    fun instance(): EstimatedResponse {
        return EstimatedResponse(this.name, average, variance, count)
    }

}