package ksl.utilities.distributions.fitting

import ksl.utilities.*
import ksl.utilities.distributions.Weibull
import ksl.utilities.random.rvariable.parameters.WeibullRVParameters
import ksl.utilities.statistic.JackKnifeEstimator
import ksl.utilities.statistic.Statistic

/**
 *  Applies the percentile method to estimating the parameters of the Weibull distribution
 *  as outline in these papers:
 *
 *  Marks NB. Estimation of Weibull parameters from common percentiles.
 *  Journal of Applied Statistics. 2005 Jan;32(1):17–24.
 *
 *  Castillo E, Hadi AS. A method for estimating parameters and quantiles of distributions
 *  of continuous random variables. Computational Statistics & Data Analysis. 1995 Oct;20(4):421–39.
 *
 * The strategy is to apply the method described in Marks (2005) using multiple pairings of
 * complementary percentiles forming a sample of shape estimates as noted in Castillo and Hadi (1995).
 *
 * We then apply a jack knife estimation procedure on the generated shape estimates to form
 * the final estimated shape.  Once the shape is estimated, the scale parameter is estimated using
 * the recommended method described on pages 287-288 of Law (2007) Simulation Modeling and Analysis.
 *
 * The procedure requires that at least 10 observations are available. The user can
 * adjust the percentiles used through the fields reducedPercentileSet or expandedPercentileSet.
 * Alternative, the user can directly provide the lower half of the percentile set via the
 * property percentileSet. After executing the estimation process on some date, the
 * jackKnifeEstimator field can be used to access the computed shape estimates.
 *
 * The supplied data cannot be negative or zero and must not all be equal in value.
 */
object WeibullPercentileParameterEstimator : ParameterEstimatorIfc {

    val reducedPercentileSet = doubleArrayOf(0.1, 0.2, 0.3, 0.4)
    val expandedPercentileSet = doubleArrayOf(0.05, 0.1, 0.15, 0.2, 0.25, 0.30, 0.35, 0.40, 0.45)

    /**
     *   Use to specify the set of percentiles that will be used during the estimation
     *   process. This is the lower half of the percentiles. These will be matched
     *   with their complement values to form the pairs used to estimate shape and
     *   scale.  For example 0.1 and 0.9 are a common pair used.
     */
    var percentileSet = expandedPercentileSet

    /**
     *  Holds the estimated shape parameters from the percentile method.
     *  This field will be overwritten during the estimation process.
     */
    var jackKnifeEstimator: JackKnifeEstimator? = null

    /**
     *  If the size of the sample is less than this value, then use a reduced percentile set
     */
    var sampleSizeFactor = 20
        set(value) {
            require(value >= 1) { "The sample size factor must be >= 10: $value" }
            field = value
        }

    override fun estimate(data: DoubleArray, statistics: Statistic): EstimationResults {
        if (data.size <= 10) {
            return EstimationResults(
                statistics = statistics,
                message = "The percentile parameter estimation approach is not recommended with less than 10 observations",
                success = false
            )
        }
        if (data.countLessEqualTo(0.0) > 0) {
            return EstimationResults(
                statistics = statistics,
                message = "Cannot fit Weibull distribution when some observations are <= 0.0",
                success = false
            )
        }
        if (data.isAllEqual()) {
            return EstimationResults(
                statistics = statistics,
                message = "Cannot estimate parameters.  The observations were all equal.",
                success = false
            )
        }
        val sorted = data.orderStatistics()
        if (data.size < sampleSizeFactor) {
            percentileSet = reducedPercentileSet
        }
        // compute the complement of the percentile set
        val cPercentileSet = DoubleArray(percentileSet.size) { (1.0 - percentileSet[it]) }
        // compute the cartesian product of the pairs
        val cp = KSLArrays.cartesian(percentileSet, cPercentileSet)
        // compute the quantiles associated with each percentile
        val lower = Statistic.quantilesFromSortedData(sorted, percentileSet)
        val upper = Statistic.quantilesFromSortedData(sorted, cPercentileSet)
        // get the pairings of the quantiles associated with each percentile
        val cq = KSLArrays.cartesian(lower, upper)
        // compute the sample of estimates from quantiles and percentiles
        val shapeEstimates = computeShapeEstimates(cq, cp)
        // use jack knife to improve estimate
        jackKnifeEstimator = JackKnifeEstimator(shapeEstimates)
        val shape = jackKnifeEstimator!!.biasCorrectedJackKnifeEstimate
        val scale = Weibull.estimateScale(shape, data)
        val parameters = WeibullRVParameters()
        parameters.changeDoubleParameter("shape", shape)
        parameters.changeDoubleParameter("scale", scale)
        return EstimationResults(
            statistics = statistics,
            parameters = parameters,
            message = "The Weibull parameters were estimated successfully using the percentile technique",
            success = true
        )
    }

    private fun computeShapeEstimates(
        quantilePairs: List<Pair<Double, Double>>,
        percentilePairs: List<Pair<Double, Double>>
    ): DoubleArray {
        val shapes = DoubleArray(quantilePairs.size)
        for (i in shapes.indices) {
            val xq1 = quantilePairs[i].first
            val xq2 = quantilePairs[i].second
            val p1 = percentilePairs[i].first
            val p2 = percentilePairs[i].second
            val (shape, _) = Weibull.parametersFromPercentiles(xq1, xq2, p1, p2)
            shapes[i] = shape
        }
        return shapes
    }
}