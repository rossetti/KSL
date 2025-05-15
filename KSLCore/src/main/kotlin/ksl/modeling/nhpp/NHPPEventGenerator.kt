/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package ksl.modeling.nhpp

import ksl.modeling.elements.*
import ksl.simulation.ModelElement

/**
 * @param parent the parent
 * @param generatorAction   the listener for generation
 * @param nhppTimeBtwEventRV the non-homogeneous Poisson process random variable
 * @param baseEventGenerator the base event generator for generating events
 * @param name the name to assign
 */
open class NHPPEventGenerator protected constructor(
    parent: ModelElement,
    generatorAction: GeneratorActionIfc,
    protected val nhppTimeBtwEventRV: NHPPTimeBtwEventRV,
    protected val baseEventGenerator: BaseEventGenerator = BaseEventGenerator(parent, generatorAction),
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
        generatorAction: GeneratorActionIfc,
        rateFunction: InvertibleCumulativeRateFunctionIfc,
        streamNum: Int = 0,
        lastRate: Double = Double.NaN,
        maxNumberOfEvents: Long = Long.MAX_VALUE,
        timeOfTheLastEvent: Double = Double.POSITIVE_INFINITY,
        name: String? = null
    ) : this(
        parent, generatorAction,
        NHPPTimeBtwEventRV(parent, rateFunction, lastRate, streamNum),
        name = name
    ) {
        baseEventGenerator.initialEndingTime = timeOfTheLastEvent
//        baseEventGenerator.endingTime = timeOfTheLastEvent
        baseEventGenerator.timeUntilFirstEvent = nhppTimeBtwEventRV
        baseEventGenerator.initialTimeBtwEvents = nhppTimeBtwEventRV
//        baseEventGenerator.setTimeBetweenEvents(nhppTimeBtwEventRV, maxNumberOfEvents)
        baseEventGenerator.setInitialTimeBetweenEventsAndMaxNumEvents(nhppTimeBtwEventRV, maxNumberOfEvents)
    }


}