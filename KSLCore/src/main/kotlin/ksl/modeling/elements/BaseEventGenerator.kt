package ksl.modeling.elements

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.GetValueIfc
import ksl.utilities.random.rvariable.ConstantRV

open class BaseEventGenerator(
    parent: ModelElement,
    generateAction: GeneratorActionIfc? = null,
    timeUntilFirstEvent: GetValueIfc,
    timeBtwEvents: GetValueIfc,
    maxNumberOfEvents: Long = Long.MAX_VALUE,
    timeOfTheLastEvent: Double = Double.POSITIVE_INFINITY,
    name: String? = null
) : ModelElement(parent, name), BaseGeneratorIfc, BaseGeneratorTimeBtwEventsIfc, BaseGeneratorCIfc {

    init {
        validateMaxNumEventsAndTimeBtwEvents(timeBtwEvents, maxNumberOfEvents)
        require(timeOfTheLastEvent >= 0) { "The time of the last event was < 0!" }
        //TODO need to implement ranges so that time until first can be checked
    }

    fun validateMaxNumEventsAndTimeBtwEvents(timeBtwEvents: GetValueIfc, maxNumEvents: Long)  {
        require(maxNumEvents >= 0) { "The maximum number of events to generate was < 0!" }
        if (maxNumEvents == Long.MAX_VALUE) {
            if (timeBtwEvents is ConstantRV) {
                //TODO ranges will make this easier to check
                require(timeBtwEvents.value != 0.0) { "Maximum number of events is $maxNumEvents and time between events is 0.0" }
            }
        }
    }

    override var timeUntilFirstEvent: GetValueIfc = timeUntilFirstEvent
        set(value) {
            require(model.isNotRunning) { "The model should not be running when changing the time until the first event." }
            field = value
        }

    protected var myMaxNumEvents: Long = maxNumberOfEvents

    protected var myTimeBtwEvents: GetValueIfc = timeBtwEvents

    override var maximumNumberOfEvents: Long
        get() = myMaxNumEvents
        set(value) {
            setTimeBetweenEvents(myTimeBtwEvents, value)
        }

    override var timeBetweenEvents: GetValueIfc
        get() = myTimeBtwEvents
        set(value) {
            setTimeBetweenEvents(value, myMaxNumEvents)
        }

    override fun setTimeBetweenEvents(timeBtwEvents: GetValueIfc, maxNumEvents: Long) {
        validateMaxNumEventsAndTimeBtwEvents(timeBtwEvents, maxNumEvents)
        myTimeBtwEvents = timeBtwEvents
        myMaxNumEvents = maxNumEvents
        // if number of events is >= desired number of events, turn off the generator
        if (eventCount >= maxNumEvents) {
            turnOffGenerator()
        }
    }

    protected var myInitialMaxNumEvents: Long = maxNumberOfEvents

    override var initialMaximumNumberOfEvents: Long
        get() = myInitialMaxNumEvents
        set(value) {
            setInitialTimeBetweenEventsAndMaxNumEvents(myInitialTimeBtwEvents, value)
        }

    protected var myInitialTimeBtwEvents: GetValueIfc = timeBtwEvents

    override var initialTimeBtwEvents: GetValueIfc
        get() = myInitialTimeBtwEvents
        set(value) {
            setInitialTimeBetweenEventsAndMaxNumEvents(value, myInitialMaxNumEvents)
        }

    override fun setInitialTimeBetweenEventsAndMaxNumEvents(initialTimeBtwEvents: GetValueIfc, initialMaxNumEvents: Long) {
        require(model.isNotRunning) { "The model should not be running when changing the initial time between events or the initial maximum number of events." }
        validateMaxNumEventsAndTimeBtwEvents(initialTimeBtwEvents, initialMaxNumEvents)
        myInitialTimeBtwEvents = initialTimeBtwEvents
        myInitialMaxNumEvents = initialMaxNumEvents
    }

    override fun setInitialEventTimeProcesses(eventTimeProcess: GetValueIfc) {
        timeUntilFirstEvent = eventTimeProcess
        initialTimeBtwEvents = eventTimeProcess
    }

    /**
     * The action for the events for generation
     */
    private var generatorAction: GeneratorActionIfc? = generateAction

    /**
     *  Can be used to supply logic to invoke when the generator's
     *  is supposed to generate
     */
    fun generatorAction(action: GeneratorActionIfc?) {
        generatorAction = action
    }

    private var endGeneratorAction: EndGeneratorActionIfc? = null

    /**
     *  Can be used to supply logic to invoke when the generator's
     *  ending time is finite and the generator is turned off.
     */
    fun endGeneratorAction(action: EndGeneratorActionIfc?) {
        endGeneratorAction = action
    }

    /**
     * Determines the priority of the event generator's events The default is
     * DEFAULT_PRIORITY - 1 A lower number implies higher priority.
     */
    var eventPriority = EVENT_PRIORITY

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
    override var startOnInitializeOption = true

    private val myEventHandler: EventHandler = EventHandler()

    /**
     * indicates whether the generator has been started (turned on)
     */
    override var isStarted = false
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
        // set the time between events based on the initial setting for replications
        myTimeBtwEvents = myInitialTimeBtwEvents
        if (startOnInitializeOption) {
            if (myMaxNumEvents > 0) {
                scheduleFirstEvent(timeUntilFirstEvent)
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
                generatorAction?.generate(this@BaseEventGenerator) ?: generate()
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

    companion object {
        /**
         * Determines the priority of the event generator's events The default is
         * DEFAULT_PRIORITY - 1 A lower number implies higher priority.
         */
        const val EVENT_PRIORITY: Int = KSLEvent.DEFAULT_PRIORITY - 1

    }
}