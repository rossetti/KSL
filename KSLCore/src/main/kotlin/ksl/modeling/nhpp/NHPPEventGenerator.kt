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

import ksl.modeling.elements.EventGenerator
import ksl.modeling.elements.GeneratorActionIfc
import ksl.simulation.ModelElement
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.RVariableIfc

/**
 * @param parent the parent
 * @param timeBtwEvents the NHPP time between event random variable
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
 * @param name the name to assign to the model element
 */
open class NHPPEventGenerator private constructor(
    parent: ModelElement,
    protected val timeBtwEvents: NHPPTimeBtwEventRV,
    generatorAction: GeneratorActionIfc,
    maxNumberOfEvents: Long = Long.MAX_VALUE,
    timeOfTheLastEvent: Double = Double.POSITIVE_INFINITY,
    name: String? = null
) : ModelElement(parent, name) {

    /**
     * @param parent the parent
     * @param rateFunction the rate function. See [NHPPTimeBtwEventRV]
     * @param generatorAction   the listener for generation
     * @param lastRate  the last rate. See [NHPPTimeBtwEventRV]
     * @param streamNumber the stream number to use for the generation process
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
     * @param name the name to assign
     */
    constructor(
        parent: ModelElement,
        rateFunction: InvertibleCumulativeRateFunctionIfc,
        generatorAction: GeneratorActionIfc,
        lastRate: Double? = null,
        streamNumber: Int = 0,
        maxNumberOfEvents: Long = Long.MAX_VALUE,
        timeOfTheLastEvent: Double = Double.POSITIVE_INFINITY,
        name: String? = null
    ) : this(
        parent,
        NHPPTimeBtwEventRV(parent, rateFunction, lastRate, streamNumber, parent.streamProvider),
        generatorAction,
        maxNumberOfEvents,
        timeOfTheLastEvent,
        name
    )

    private val myEventGenerator: EventGenerator = EventGenerator(
        this, generatorAction, timeBtwEvents, timeBtwEvents,
        maxNumberOfEvents, timeOfTheLastEvent, "${this.name}:EventGenerator"
    )

    /**
     *  Can be used to change the rate function associated with the non-stationary
     *  Poisson process. Changing the rate function during a replication is not permitted.
     *
     * @param rateFunction the rate function. See [NHPPTimeBtwEventRV]
     * @param lastRate  the last rate. See [NHPPTimeBtwEventRV]
     * @param streamNumber the stream number to use for the generation process
     * @param maxNumberOfEvents A long that supplies the maximum number of events to
     * generate. Each time an event is to be scheduled the maximum number of
     * events is checked. If the maximum has been reached, then the generator is
     * turned off. The default is Long.MAX_VALUE. This parameter cannot be
     * Long.MAX_VALUE when the time until next always returns a value of 0.0
     */
    fun setRateFunction(
        rateFunction: InvertibleCumulativeRateFunctionIfc,
        lastRate: Double? = null,
        streamNumber: Int = 0,
        maxNumberOfEvents: Long = Long.MAX_VALUE
    ) {
        require(model.isNotRunning){"The rate function cannot be changed while the model is running."}
        val nhpp = NHPPTimeBtwEventRV(this, rateFunction, lastRate, streamNumber, streamProvider)
        myEventGenerator.setInitialTimeBetweenEventsAndMaxNumEvents(nhpp, maxNumberOfEvents)
    }

    override fun initialize() {
        super.initialize()
        timeBtwEvents.initialize()
    }

    fun turnOnGenerator(t: Double) {
        myEventGenerator.turnOnGenerator(t)
    }

    fun turnOnGenerator(r: RVariableIfc) {
        myEventGenerator.turnOnGenerator(r)
    }

    fun turnOffGenerator() {
        myEventGenerator.turnOffGenerator()
    }

    val isStarted: Boolean
        get() = myEventGenerator.isStarted

    var startOnInitializeOption: Boolean
        get() = myEventGenerator.startOnInitializeOption
        set(value) {
            myEventGenerator.startOnInitializeOption = value
        }

    fun suspend() {
        myEventGenerator.suspend()
    }

    val isSuspended: Boolean
        get() = myEventGenerator.isSuspended

    fun resume() {
        myEventGenerator.resume()
    }

    val isDone: Boolean
        get() = myEventGenerator.isDone

    val maximumNumberOfEvents: Long
        get() = myEventGenerator.maximumNumberOfEvents

    val endingTime: Double
        get() = myEventGenerator.endingTime

    var initialEndingTime: Double
        get() = myEventGenerator.initialEndingTime
        set(value) {
            myEventGenerator.initialEndingTime = value
        }

    val eventCount: Long
        get() = myEventGenerator.eventCount

    val initialMaximumNumberOfEvents: Long
        get() = myEventGenerator.initialMaximumNumberOfEvents

    val isEventPending: Boolean
        get() = myEventGenerator.isEventPending
}