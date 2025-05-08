package ksl.modeling.nhpp

import ksl.modeling.elements.GeneratorActionIfc
import ksl.simulation.ModelElement

class NHPPPiecewiseRateFunctionEventGenerator(
    parent: ModelElement,
    rateFunction: PiecewiseRateFunction,
    generatorAction: GeneratorActionIfc,
    lastRate: Double? = null,
    streamNumber: Int = 0,
    maxNumberOfEvents: Long = Long.MAX_VALUE,
    timeOfTheLastEvent: Double = Double.POSITIVE_INFINITY,
    name: String? = null
) : NHPPEventGenerator(parent, rateFunction, generatorAction, lastRate,
    streamNumber, maxNumberOfEvents, timeOfTheLastEvent, name) {

    /**
     *  This function can be used to adjust the rates within the piecewise rate function
     *  up or down by the provided factor. Each rate is multiplied by the factor for its
     *  new setting. For example, to increase all rates by 20 percent, the factor should be 1.2.
     *  The previous rate function will be replaced by a new rate function with the adjusted rates.
     *
     * @param factor The factor must be positive
     */
    fun adjustRates(factor: Double) {
        require(model.isNotRunning){"The rate function cannot be changed while the model is running."}
        require(factor > 0.0) { "factor must be positive: $factor" }
        val rv = timeBtwEvents as NHPPPiecewiseRateFunctionTimeBtwEventRV
        rv.adjustRates(factor)
    }
}