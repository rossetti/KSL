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

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.modeling.elements.*
import ksl.modeling.elements.EventGenerator.Companion.EVENT_PRIORITY
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.GetValueIfc
import ksl.utilities.random.rvariable.ConstantRV

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
open class NHPPEventGenerator (
    parent: ModelElement,
    override var generatorAction: GeneratorActionIfc?,
    nhpp: NHPPTimeBtwEventRV,
    maxNumberOfEvents: Long = Long.MAX_VALUE,
    timeOfTheLastEvent: Double = Double.POSITIVE_INFINITY,
    name: String? = null
) : ModelElement(parent, name), EventGeneratorIfc, EventGeneratorInitializationCIfc {

    init {
        require(maxNumberOfEvents >= 0) { "The maximum number of events to generate was < 0!" }
        require(timeOfTheLastEvent >= 0) { "The time of the last event was < 0!" }
    }

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
        generatorAction: GeneratorActionIfc?,
        rateFunction: InvertibleCumulativeRateFunctionIfc,
        streamNum: Int = 0,
        lastRate: Double = Double.NaN,
        maxNumberOfEvents: Long = Long.MAX_VALUE,
        timeOfTheLastEvent: Double = Double.POSITIVE_INFINITY,
        name: String? = null
    ) : this(parent, generatorAction,
        NHPPTimeBtwEventRV(parent, rateFunction, lastRate, streamNum),
        maxNumberOfEvents, timeOfTheLastEvent, name
    )

    private var myMaxNumEvents: Long = maxNumberOfEvents

    override var maximumNumberOfEvents: Long
        get() = myMaxNumEvents
        set(value) {
            require(value >= 0) { "The maximum number of events to generate was < 0!" }
            myMaxNumEvents = value
        }

    private var myInitialMaxNumEvents: Long = maxNumberOfEvents

    override var initialMaximumNumberOfEvents: Long
        get() = myInitialMaxNumEvents
        set(value) {
            require(model.isNotRunning) {"The model must not be running when changing the initial maximum number of events."}
            require(value >= 0) { "The initial maximum number of events to generate was < 0!" }
            myInitialMaxNumEvents = value
        }

    protected var myNHPPRV : NHPPTimeBtwEventRV = nhpp

    protected val myTimeBtwEvents: GetValueIfc
        get() = myNHPPRV

    /**
     *  Allows the changing of the rate function only if the
     *  model is not running.
     */
    var rateFunction: InvertibleCumulativeRateFunctionIfc
        get() = myNHPPRV.rateFunction
        set(value) {
            myNHPPRV.rateFunction = value
        }

    /**
     *  Can be used to supply logic to invoke when the generator's
     *  ending time is finite and the generator is turned off.
     */
    override var endGeneratorAction: EndGeneratorActionIfc? = null

    /**
     * Determines the priority of the event generator's events The default is
     * DEFAULT_PRIORITY - 1 A lower number implies higher priority.
     */
    var eventPriority : Int = EVENT_PRIORITY

    /**
     * The time to stop generating for the current replication
     */
    override var endingTime: Double = timeOfTheLastEvent
        set(value) {
            require(value >= 0.0) { "The ending time was < 0.0!" }
            if (value < time) {
                turnOffGenerator()
            } else {// now set the time to turn off
                field = value
            }
        }

    /**
     * Used to set the ending time when the generator is initialized
     * at the start of each replication.
     */
    @set:KSLControl(
        controlType = ControlType.DOUBLE,
        name = "initialEndingTime",
        lowerBound = 0.0
    )
    override var initialEndingTime: Double = timeOfTheLastEvent
        set(value) {
            require(value >= 0.0) { "The time until last was < 0.0!" }
            field = value
        }

    /**
     * The next event to be executed for the generator
     */
    private var myNextEvent: KSLEvent<Nothing>? = null

    /**
     * This flag controls whether the generator starts automatically when
     * initialized at the beginning of a replication By default this option is
     * true. If it is changed then it remains at the set value until changed
     * again.
     */
    @set:KSLControl(
        controlType = ControlType.BOOLEAN,
        name = "startOnInitializeOption",
    )
    override var startOnInitializeOption : Boolean = true

    private val myEventHandler: EventHandler = EventHandler()

    /**
     * indicates whether the generator has been started (turned on)
     */
    override var isStarted : Boolean = false
        protected set

    // now set the time to turn off

    /**
     * The number of events currently generated during the replication
     */
    override var eventCount: Long = 0
        protected set

    override fun turnOnGenerator(t: Double) {
        if (isSuspended) {
            return
        }
        if (isDone) {
            return
        }
        if (myMaxNumEvents == 0L) {
            return
        }
        if (eventCount >= myMaxNumEvents) {
            return
        }
        if (myNextEvent != null) {
            return
        }
        isStarted = true
        scheduleFirstEvent(t)
    }

    override fun turnOffGenerator() {
        isDone = true
        isStarted = false
        if (myNextEvent != null) {
            if (myNextEvent!!.isScheduled) {
                myNextEvent!!.cancel = true
            }
        }
    }

    // must be scheduled and not canceled to be pending
    override val isEventPending: Boolean
        get() = if (myNextEvent == null) {
            false
        } else {
            // must be scheduled and not canceled to be pending
            myNextEvent!!.isScheduled && !myNextEvent!!.cancel
        }

    override fun suspend() {
        isSuspended = true
        if (myNextEvent != null) {
            if (myNextEvent!!.isScheduled) {
                myNextEvent!!.cancel = true
            }
        }
    }

    /**
     * Whether the generator has been suspended
     */
    override var isSuspended: Boolean = false
        protected set

    override fun resume() {
        if (isSuspended) {
            isSuspended = false
            // get the time until next event
            val t: Double = myTimeBtwEvents.value
            // check if it is past end time
            if (t + time > endingTime) {
                turnOffGenerator()
            }
            if (!isDone) {
                // I'm not done generating, schedule the event
                myNextEvent = myEventHandler.schedule(t, priority = eventPriority)
            }
        }
    }

    /**
     * Whether the generator is done generating
     */
    override var isDone: Boolean = false
        protected set

    override fun initialize() {
        isDone = false
        isStarted = false
        isSuspended = false
        eventCount = 0
        myNextEvent = null
        // set ending time based on the initial setting for replications
        endingTime = initialEndingTime
        // set the max number of events based on initial setting for replications
        myMaxNumEvents = myInitialMaxNumEvents
        if (startOnInitializeOption) {
            if (myMaxNumEvents > 0) {
                scheduleFirstEvent(myTimeBtwEvents)
            }
        }
        if (endingTime.isFinite()) {
            schedule(this::endGeneratorAction, endingTime)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun endGeneratorAction(event: KSLEvent<Nothing>) {
        turnOffGenerator()
        endGeneratorAction?.endGeneration(this) ?: endGeneration()
    }

    /**
     * Schedules the first event at current time + r.getValue()
     *
     * @param r the time to the first event
     */
    private fun scheduleFirstEvent(r: GetValueIfc) {
        scheduleFirstEvent(r.value)
    }

    /**
     * Schedules the first event at current time + t
     *
     * @param t the time to the first event
     */
    private fun scheduleFirstEvent(t: Double) {
        if (t + time > endingTime) {
            turnOffGenerator()
        }
        if (!isDone) {
            // I'm not done generating, schedule the first event
            myNextEvent = myEventHandler.schedule(t, priority = eventPriority)
        }
    }

    /**
     * Increments the number of actions and checks if the number of actions is
     * greater than the maximum number of actions. If so, the generator is told
     * to shut down.
     */
    private fun incrementNumberOfEvents() {
        eventCount++
        if (eventCount > myMaxNumEvents) {
            turnOffGenerator()
        }
    }

    private inner class EventHandler : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            incrementNumberOfEvents()
            if (!isDone) {
                generatorAction?.generate(this@NHPPEventGenerator) ?: generate()
                // get the time until next event
                val t: Double = myTimeBtwEvents.value
                // check if it is past end time
                if (t + time > endingTime) {
                    turnOffGenerator()
                }
                if (!isSuspended) {
                    if (!isDone) {// I'm not done generating, schedule the next event
                        schedule(t, priority = eventPriority)
                    }
                }
            }
        }
    }

    protected open fun generate() {
    }

    protected open fun endGeneration() {
    }
}