package ksl.utilities.distributions.fitting

import ksl.utilities.*
import ksl.utilities.distributions.Weibull
import ksl.utilities.random.rvariable.parameters.WeibullRVParameters
import ksl.utilities.statistic.JackKnifeEstimator
import ksl.utilities.statistic.Statistic

object WeibullPercentileParameterEstimator : ParameterEstimatorIfc {

    val reducedPercentileSet = doubleArrayOf(0.1, 0.2, 0.3, 0.4)
    val expandedPercentileSet = doubleArrayOf(0.05, 0.1, 0.15, 0.2, 0.25, 0.30, 0.35, 0.40, 0.45)

    var percentileSet = expandedPercentileSet

    var jackKnifeEstimator: JackKnifeEstimator? = null

    /**
     *  If the size of the sample is less than this value, then use a reduced percentile set
     */
    var sampleSizeFactor = 20
        set(value) {
            require(value >= 1) { "The sample size factor must be >= 10: $value" }
            field = value
        }

    override fun estimate(data: DoubleArray): EstimatedParameters {
        if (data.size <= 10) {
            return EstimatedParameters(
                message = "The percentile parameter estimation approach is not recommended with less than 10 observations",
                success = false
            )
        }
        if (data.countLessEqualTo(0.0) > 0) {
            return EstimatedParameters(
                message = "Cannot fit Weibull distribution when some observations are <= 0.0",
                success = false
            )
        }
        if (data.isAllEqual()) {
            return EstimatedParameters(
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
        return EstimatedParameters(
            parameters,
            statistics = data.statistics(),
            message = "Percentile based estimates for Weibull distribution were successfully estimated.",
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