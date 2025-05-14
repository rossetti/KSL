package ksl.modeling.nhpp

import ksl.modeling.elements.BaseEventGenerator
import ksl.modeling.elements.EventGeneratorIfc
import ksl.modeling.elements.EventGeneratorInitializationCIfc
import ksl.modeling.elements.GeneratorActionIfc
import ksl.simulation.ModelElement


/**
 * @param parent the parent
 * @param generatorAction   the listener for generation
 * @param nhppTimeBtwEventRV the non-homogeneous Poisson process random variable
 * @param baseEventGenerator the base event generator for generating events
 * @param name the name to assign
 */
class NHPPPiecewiseRateFunctionEventGenerator private constructor(
    parent: ModelElement,
    generatorAction: GeneratorActionIfc,
    private val nhppTimeBtwEventRV: NHPPPiecewiseRateFunctionTimeBtwEventRV,
    private val baseEventGenerator: BaseEventGenerator = BaseEventGenerator(parent, generatorAction),
    name: String? = null
) : ModelElement(parent, name), EventGeneratorIfc by baseEventGenerator,
    EventGeneratorInitializationCIfc by baseEventGenerator {

    /**
     * @param parent the parent
     * @param rateFunction the rate function
     * @param generatorAction   the listener for generation
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
        rateFunction: PiecewiseRateFunction,
        generatorAction: GeneratorActionIfc,
        streamNum: Int = 0,
        lastRate: Double = Double.NaN,
        maxNumberOfEvents: Long = Long.MAX_VALUE,
        timeOfTheLastEvent: Double = Double.POSITIVE_INFINITY,
        name: String? = null
    ) : this(
        parent, generatorAction,
        NHPPPiecewiseRateFunctionTimeBtwEventRV(parent, rateFunction, lastRate, streamNum),
        name = name
    ) {
        baseEventGenerator.endingTime = timeOfTheLastEvent
        baseEventGenerator.timeUntilFirstEvent = nhppTimeBtwEventRV
        baseEventGenerator.setTimeBetweenEvents(nhppTimeBtwEventRV, maxNumberOfEvents)
    }

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
        nhppTimeBtwEventRV.adjustRates(factor)
    }

}