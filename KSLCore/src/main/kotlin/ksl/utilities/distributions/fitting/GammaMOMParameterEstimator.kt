package ksl.utilities.distributions.fitting

/**
 *  Estimates the shape and scale of a gamma distribution based on
 *  the method of moments. Observations must be greater than or
 *  equal to zero and must not all be equal. There must be
 *  at least two observations. The sample average and sample variance
 *  of the observations must be strictly greater than zero.
 */
object GammaMOMParameterEstimator : ParameterEstimatorIfc {

    override fun estimate(data: DoubleArray): EstimatedParameters {
        return ParameterEstimatorIfc.gammaMOMEstimator(data)
    }
}