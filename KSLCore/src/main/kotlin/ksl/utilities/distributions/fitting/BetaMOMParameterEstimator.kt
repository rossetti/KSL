package ksl.utilities.distributions.fitting

import ksl.utilities.countGreaterThan
import ksl.utilities.countLessThan
import ksl.utilities.random.rvariable.parameters.BetaRVParameters
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistics

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
object BetaMOMParameterEstimator : ParameterEstimatorIfc {
    override fun estimate(data: DoubleArray, statistics: Statistic): EstimatedParameters {
        if (data.size < 2){
            return EstimatedParameters(
                message = "There must be at least two observations",
                success = false
            )
        }
        if (data.countLessThan(0.0) > 0) {
            return EstimatedParameters(
                null,
                message = "Cannot fit beta distribution when some observations are less than 0.0",
                success = false
            )
        }
        if (data.countGreaterThan(1.0) > 0) {
            return EstimatedParameters(
                null,
                message = "Cannot fit beta distribution when some observations are greater than 1.0",
                success = false
            )
        }
        if (statistics.average <= 0.0){
            return EstimatedParameters(
                message = "The sample average of the data was <= 0.0",
                success = false
            )
        }
        if (statistics.variance <= 0.0){
            return EstimatedParameters(
                message = "The sample variance of the data was <= 0.0",
                success = false
            )
        }
        val xb = statistics.average
        val xb1m = 1.0 - xb
        val xc = ((xb*xb1m)/statistics.variance) - 1.0
        val alphaMoM = xb*xc
        val betaMOM = xb1m*xc
        val parameters = BetaRVParameters()
        parameters.changeDoubleParameter("alpha1", alphaMoM)
        parameters.changeDoubleParameter("alpha2", betaMOM)
        return EstimatedParameters(parameters, statistics = statistics, success = true)
    }
}