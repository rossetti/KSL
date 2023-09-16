package ksl.utilities.distributions.fitting

import ksl.utilities.countLessThan
import ksl.utilities.distributions.Gamma
import ksl.utilities.random.rvariable.parameters.GammaRVParameters
import ksl.utilities.statistics


/**
 *  Estimates the shape and scale of a gamma distribution based on
 *  the method of moments. Observations must be greater than or
 *  equal to zero and must not all be equal. There must be
 *  at least two observations. The sample average and sample variance
 *  of the observations must be strictly greater than zero.
 */
object GammaMOMParameterEstimator : ParameterEstimatorIfc {

    override fun estimate(data: DoubleArray): EstimatedParameters {
        if (data.size < 2){
            return EstimatedParameters(
                message = "There must be at least two observations",
                success = false
            )
        }
        if (data.countLessThan(0.0) > 0) {
            return EstimatedParameters(
                null,
                message = "Cannot fit gamma distribution when some observations are less than 0.0",
                success = false
            )
        }
        val s = data.statistics()
        if (s.average <= 0.0){
            return EstimatedParameters(
                message = "The sample average of the data was <= 0.0",
                success = false
            )
        }
        if (s.variance <= 0.0){
            return EstimatedParameters(
                message = "The sample variance of the data was <= 0.0",
                success = false
            )
        }
        val params = Gamma.parametersFromMeanAndVariance(s.average, s.variance)
        val parameters = GammaRVParameters()
        parameters.changeDoubleParameter("shape", params[0])
        parameters.changeDoubleParameter("scale", params[1])
        return EstimatedParameters(parameters, success = true)
    }
}