package ksl.modeling.nhpp

import ksl.modeling.elements.GeneratorActionIfc
import ksl.simulation.ModelElement
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom

class NHPPPiecewiseRateFunctionEventGenerator(
    parent: ModelElement,
    rateFunction: PiecewiseRateFunction,
    generatorAction: GeneratorActionIfc,
    lastRate: Double = Double.NaN,
    stream: RNStreamIfc = KSLRandom.nextRNStream(),
    theName: String? = null
) : NHPPEventGenerator(parent, rateFunction, generatorAction, lastRate, stream, theName) {

    constructor(
        parent: ModelElement,
        rateFunction: PiecewiseRateFunction,
        generatorAction: GeneratorActionIfc,
        lastRate: Double = Double.NaN,
        streamNum: Int,
        name: String? = null
    ) : this(parent, rateFunction, generatorAction, lastRate, KSLRandom.rnStream(streamNum), name)

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
        val rv = myTBARV as NHPPPiecewiseRateFunctionTimeBtwEventRV
        rv.adjustRates(factor)
    }
}