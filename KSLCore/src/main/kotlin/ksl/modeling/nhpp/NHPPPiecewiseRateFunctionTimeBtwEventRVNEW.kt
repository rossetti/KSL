package ksl.modeling.nhpp

import ksl.simulation.ModelElement

/**
 *  Models non-homogenous Poisson processes based on piecewise rate functions.
 */
class NHPPPiecewiseRateFunctionTimeBtwEventRVNEW(
    parent: ModelElement,
    rateFunction: PiecewiseRateFunction,
    lastRate: Double = Double.NaN,
    streamNum: Int = 0,
    name: String? = null
) : NHPPTimeBtwEventRVNEW(parent, rateFunction, lastRate, streamNum, name) {

    /**
     *  This function can be used to adjust the rates within the piecewise rate function
     *  up or down by the provided factor. Each rate is multiplied by the factor for its
     *  new setting. For example, to increase all rates by 20 percent, the factor should be 1.2.
     *  The previous rate function will be replaced by a new rate function with the adjusted rates.
     *
     * @param factor The factor must be positive
     */
    fun adjustRates(factor: Double) {
        //TODO need to revisit this
        require(factor > 0.0) { "factor must be positive: $factor" }
        val rf = rateFunction as PiecewiseRateFunction
        rateFunction = rf.instance(factor)
    }
}