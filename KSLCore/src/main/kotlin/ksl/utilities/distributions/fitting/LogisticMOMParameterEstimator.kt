package ksl.utilities.distributions.fitting

import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.random.rvariable.parameters.GammaRVParameters
import ksl.utilities.random.rvariable.parameters.LogisticRVParameters
import ksl.utilities.statistic.MVBSEstimatorIfc
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.StatisticIfc
import kotlin.math.PI
import kotlin.math.sqrt

/**
 *  Estimates the location and scale of a logistic distribution based on
 *  the method of moments. Observations must not all be equal. There must be
 *  at least two observations. The moment matching is based on an unbiased
 *  estimate of the variance.
 */
object LogisticMOMParameterEstimator : ParameterEstimatorIfc,
    MVBSEstimatorIfc, IdentityIfc by Identity("LogisticMOMParameterEstimator") {

    override val names: List<String> = listOf("location", "scale")

    /**
     *  If the estimation process is not successful, then an
     *  empty array is returned.
     */
    override fun estimate(data: DoubleArray): DoubleArray {
        val er = estimateParameters(data, Statistic(data))
        if (!er.success || er.parameters == null) {
            return doubleArrayOf()
        }
        return doubleArrayOf(
            er.parameters.doubleParameter("location"),
            er.parameters.doubleParameter("scale")
        )
    }

    override val checkRange: Boolean = true

    override fun estimateParameters(data: DoubleArray, statistics: StatisticIfc): EstimationResult {
        if (data.size < 2) {
            return EstimationResult(
                originalData = data,
                statistics = statistics,
                message = "There must be at least two observations",
                success = false,
                estimator = this
            )
        }
        if (statistics.variance <= 0.0) {
            return EstimationResult(
                originalData = data,
                statistics = statistics,
                message = "The sample variance of the data was <= 0.0",
                success = false,
                estimator = this
            )
        }
        val sd = statistics.standardDeviation
        val scale = sd * sqrt(3.0) / PI
        val parameters = LogisticRVParameters()
        parameters.changeDoubleParameter("location", statistics.average)
        parameters.changeDoubleParameter("scale", scale)
        return EstimationResult(
            originalData = data,
            statistics = statistics,
            parameters = parameters,
            message = "The logistic parameters were estimated successfully using a MOM technique",
            success = true,
            estimator = this
        )
    }
}