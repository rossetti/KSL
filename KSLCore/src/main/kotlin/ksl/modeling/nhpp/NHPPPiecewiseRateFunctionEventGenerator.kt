package ksl.modeling.nhpp

import ksl.modeling.elements.GeneratorActionIfc
import ksl.simulation.ModelElement


/**
 * @param parent the parent
 * @param generatorAction   the listener for generation
 * @param nhpp the non-homogeneous Poisson process
 * @param maxNumberOfEvents A long that supplies the maximum number of events to
 * generate. Each time an event is to be scheduled the maximum number of
 * events is checked. If the maximum has been reached, then the generator is
 * turned off. The default is Long.MAX_VALUE. This parameter cannot be
 * Long.MAX_VALUE when the time until next always returns a value of 0.0
 * @param timeOfTheLastEvent A double that supplies a time to stop generating
 * events. When the generator is created, this variable is used to set the
 * ending time of the generator. Each time an event is to be scheduled the
 * ending time is checked. If the time of the next event is past this time,
 * then the generator is turned off and the event won't be scheduled. The
 * default is Double.POSITIVE_INFINITY.
 * @param name the name of the generator
 */
class NHPPPiecewiseRateFunctionEventGenerator private constructor(
    parent: ModelElement,
    generatorAction: GeneratorActionIfc,
    nhpp: NHPPPiecewiseRateFunctionTimeBtwEventRV,
    maxNumberOfEvents: Long = Long.MAX_VALUE,
    timeOfTheLastEvent: Double = Double.POSITIVE_INFINITY,
    name: String? = null
) : NHPPEventGenerator(parent, generatorAction, nhpp, maxNumberOfEvents, timeOfTheLastEvent, name) {

    /**
     * @param parent the parent
     * @param generatorAction   the listener for generation
     * @param rateFunction the rate function
     * @param streamNum the stream number for the underlying NHPP
     * @param lastRate  the last rate
     * @param maxNumberOfEvents A long that supplies the maximum number of events to
     * generate. Each time an event is to be scheduled the maximum number of
     * events is checked. If the maximum has been reached, then the generator is
     * turned off. The default is Long.MAX_VALUE. This parameter cannot be
     * Long.MAX_VALUE when the time until next always returns a value of 0.0
     * @param timeOfTheLastEvent A double that supplies a time to stop generating
     * events. When the generator is created, this variable is used to set the
     * ending time of the generator. Each time an event is to be scheduled the
     * ending time is checked. If the time of the next event is past this time,
     * then the generator is turned off and the event won't be scheduled. The
     * default is Double.POSITIVE_INFINITY.
     * @param name the name of the generator
     */
    constructor(
        parent: ModelElement,
        generatorAction: GeneratorActionIfc,
        rateFunction: PiecewiseRateFunction,
        streamNum: Int = 0,
        lastRate: Double = Double.NaN,
        maxNumberOfEvents: Long = Long.MAX_VALUE,
        timeOfTheLastEvent: Double = Double.POSITIVE_INFINITY,
        name: String? = null
    ) : this(
        parent, generatorAction,
        NHPPPiecewiseRateFunctionTimeBtwEventRV(parent, rateFunction, lastRate, streamNum),
        maxNumberOfEvents, timeOfTheLastEvent, name
    )

    /**
     *  This function can be used to adjust the rates within the piecewise rate function
     *  up or down by the provided factor. Each rate is multiplied by the factor for its
     *  new setting. For example, to increase all rates by 20 percent, the factor should be 1.2.
     *  The previous rate function will be replaced by a new rate function with the adjusted rates.
     *  Allows the changing of the rate function only if the model is not running.
     * @param factor The factor must be positive
     */
    fun adjustRates(factor: Double) {
        require(model.isNotRunning) {"The rates of the rate function cannot be adjusted while the model is running."}
        require(factor > 0.0) { "factor must be positive: $factor" }
        val nhpp = myNHPPRV as NHPPPiecewiseRateFunctionTimeBtwEventRV
        nhpp.adjustRates(factor)
    }

}