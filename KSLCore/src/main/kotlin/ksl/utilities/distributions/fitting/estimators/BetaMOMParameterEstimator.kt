package ksl.utilities.distributions.fitting.estimators

import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.countGreaterThan
import ksl.utilities.countLessThan
import ksl.utilities.distributions.fitting.EstimationResult
import ksl.utilities.random.rvariable.parameters.BetaRVParameters
import ksl.utilities.statistic.MVBSEstimatorIfc
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.StatisticIfc

/**
 *  Estimates the parameters of the beta distribution via the method of moments.
 *  Formulas can be found in:
 *
 * Owen, C. B. (2008). Parameter estimation for the beta distribution (Order No. 28109613).
 * Available from ProQuest Dissertations & Theses Global. (2499457903).
 * Retrieved from https://www.proquest.com/dissertations-theses/parameter-estimation-beta-distribution/docview/2499457903/se-2
 *
 * There must be at least two observations and the observations must be with [0,1].
 * The sample average and sample variance of the observations must be strictly greater than zero.
 *
 */
class BetaMOMParameterEstimator(name: String? = "BetaMOMParameterEstimator") :
    ParameterEstimatorIfc, MVBSEstimatorIfc, IdentityIfc by Identity(name) {

    override val checkRange: Boolean = true

    override val names: List<String> = listOf("alpha", "beta")

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
            er.parameters.doubleParameter("alpha"),
            er.parameters.doubleParameter("beta")
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
                message = "Cannot fit beta distribution when some observations are less than 0.0",
                success = false,
                estimator = this
            )
        }
        if (data.countGreaterThan(1.0) > 0) {
            return EstimationResult(
                originalData = data,
                statistics = statistics,
                message = "Cannot fit beta distribution when some observations are greater than 1.0",
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
        if (statistics.variance == 0.0) {
            return EstimationResult(
                originalData = data,
                statistics = statistics,
                message = "The sample variance of the data was = 0.0",
                success = false,
                estimator = this
            )
        }
        val xb = statistics.average
        val xb1m = 1.0 - xb
        val xc = ((xb * xb1m) / statistics.variance) - 1.0
        val alphaMoM = xb * xc
        val betaMOM = xb1m * xc
        val parameters = BetaRVParameters()
        parameters.changeDoubleParameter("alpha", alphaMoM)
        parameters.changeDoubleParameter("beta", betaMOM)
        return EstimationResult(
            originalData = data,
            statistics = statistics,
            parameters = parameters,
            message = "The beta parameters were estimated successfully using a MOM technique",
            success = true,
            estimator = this
        )
    }
}