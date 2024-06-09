package ksl.utilities.distributions.fitting.estimators

import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.countLessThan
import ksl.utilities.distributions.fitting.EstimationResult
import ksl.utilities.random.rvariable.RVParametersTypeIfc
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.parameters.NegativeBinomialRVParameters
import ksl.utilities.statistic.MVBSEstimatorIfc
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.StatisticIfc

/**
 *  Estimates the probability of success (p) and number of successes (r) until
 *  the trials stop for the negative binomial distribution based on
 *  the method of moments. The data must not contain negative values. The estimation
 *  process assumes that the supplied data are integer valued counts
 *  over the range {0,1,2,...}.  That is this parameterization represents
 *  the number of failures until the rth success.
 *  To ensure moment matching, the estimation process does not ensure that r is integer valued.
 */
object NegBinomialMOMParameterEstimator : ParameterEstimatorIfc,
    MVBSEstimatorIfc, IdentityIfc by Identity("NegBinomialMOMParameterEstimator")  {

    override val rvType: RVParametersTypeIfc
        get() = RVType.NegativeBinomial

    override val checkRange: Boolean = true

    override val names: List<String> = listOf("probOfSuccess", "numSuccesses")

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
            er.parameters.doubleParameter("probOfSuccess"),
            er.parameters.doubleParameter("numSuccesses")
        )
    }

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
        if (data.countLessThan(0.0) > 0) {
            return EstimationResult(
                originalData = data,
                statistics = statistics,
                message = "Cannot fit negative binomial distribution when some observations are less than 0.0",
                success = false,
                estimator = this
            )
        }
        if (statistics.average <= 0.0) {
            return EstimationResult(
                originalData = data,
                statistics = statistics,
                message = "The sample average of the data was <= 0.0",
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
        if (statistics.variance <= statistics.average) {
            return EstimationResult(
                originalData = data,
                statistics = statistics,
                message = "Cannot match moments when sample variance <= sample average",
                success = false,
                estimator = this
            )
        }
        val p = statistics.average / statistics.variance
        val r = (statistics.average * statistics.average) / (statistics.variance - statistics.average)
        val parameters = NegativeBinomialRVParameters()
        parameters.changeDoubleParameter("probOfSuccess", p)
        parameters.changeDoubleParameter("numSuccesses", r)
        return EstimationResult(
            originalData = data,
            statistics = statistics,
            parameters = parameters,
            message = "The negative binomial parameters were estimated successfully using a MOM technique",
            success = true,
            estimator = this
        )
    }
}