package ksl.modeling.nhpp

import ksl.utilities.GetTimeIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.KSLRandom

/**
 *  Models non-homogenous Poisson processes based on piecewise rate functions.
 */
class NHPPPiecewiseRateFunctionTimeBtwEventRVV2(
    timeGetter: GetTimeIfc,
    rateFunction: PiecewiseRateFunction,
    lastRate: Double? = null,
    streamNumber: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : NHPPTimeBtwEventRVV2(timeGetter, rateFunction, lastRate, streamNumber, streamProvider, name) {

    /**
     *  This function can be used to adjust the rates within the piecewise rate function
     *  up or down by the provided factor. Each rate is multiplied by the factor for its
     *  new setting. For example, to increase all rates by 20 percent, the factor should be 1.2.
     *  The previous rate function will be replaced by a new rate function with the adjusted rates.
     *
     * @param factor The factor must be positive
     */
    fun adjustRates(factor: Double) {
        require(factor > 0.0) { "factor must be positive: $factor" }
        val rf = rateFunction as PiecewiseRateFunction
        rateFunction = rf.instance(factor)
    }
}