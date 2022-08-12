package ksl.simulation

import ksl.calendar.CalendarIfc
import ksl.calendar.PriorityQueueEventCalendar
import ksl.utilities.exceptions.JSLEventException
import ksl.utilities.observers.Observable
//import mu.KotlinLogging

//private val logger = KotlinLogging.logger {} //TODO decide if this should be KSL or not Simulation logger

class Executive(private val myEventCalendar: CalendarIfc = PriorityQueueEventCalendar()) : Observable<JSLEvent<*>?>(){

    enum class Status {
        CREATED, INITIALIZED, BEFORE_EVENT, AFTER_EVENT, AFTER_EXECUTION
    }

    var status = Status.CREATED
        private set

    var currentTime: Double = 0.0
        private set

    var numEventsScheduled: Long = 0
        private set

    var numEventsScheduledDuringExecution: Double = Double.NaN
        private set

    var numEventsExecuted: Long = 0
        private set

    var endingTime: Double = Double.NaN
        private set

    private var lastExecutedEvent: JSLEvent<*>? = null

    internal var endEvent: JSLEvent<Nothing>? = null

    var terminationWarningMsgOption = true

    /**
     * Controls the execution of events over time
     */
    private val eventExecutionProcess: EventExecutionProcess =
        EventExecutionProcess("Executive's Inner Iterative Process")

    /**
     * Provides for 3 phase method for conditional events
     *
     */
    private val conditionalActionProcessor: ConditionalActionProcessor = ConditionalActionProcessor()

    /**
     * Returns the time the execution was scheduled to end
     *
     * @return the scheduled end time or Double.POSITIVE_INFINITY
     */
    fun scheduledEndTime(): Double {
        return endEvent?.time ?: Double.POSITIVE_INFINITY
    }

    /**
     * Returns true if an event has been scheduled to stop execution at
     * getTimeHorizon()
     *
     * @return true if scheduled
     */
    fun isEndEventScheduled(): Boolean {
        return endEvent != null
    }

    /**
     * Checks to see if the executive has another event
     *
     * @return true if it has another event
     */
    fun hasNextEvent(): Boolean {
        return myEventCalendar.isNotEmpty
    }

    /**
     * If the Executive has an end event or maximum allowed execution time, then
     * return true
     *
     * @return true if the executive has an end event or max allowed execution
     * time
     */
    fun willTerminate(): Boolean {
        var flag = true
        if (!isEndEventScheduled()) {
            if (maximumAllowedExecutionTime == 0L) {
                flag = false
            }
        }
        return flag
    }

    val isInitialized
        get() = eventExecutionProcess.isInitialized

    val isUnfinished
        get() = eventExecutionProcess.isUnfinished

    val isRunning
        get() = eventExecutionProcess.isRunning

    val isEnded
        get() = eventExecutionProcess.isEnded

    val isDone
        get() = eventExecutionProcess.isDone

    val allEventsExecuted
        get() = eventExecutionProcess.allStepsCompleted

    val isEndConditionMet
        get() = eventExecutionProcess.stoppedByCondition

    val beginExecutionTime
        get() = eventExecutionProcess.beginExecutionTime

    val endedExecutionTime
        get() = eventExecutionProcess.endExecutionTime

    val elapsedExecutionTime
        get() = eventExecutionProcess.elapsedExecutionTime

    var maximumAllowedExecutionTime
        get() = eventExecutionProcess.maximumAllowedExecutionTime
        set(value) {
            eventExecutionProcess.maximumAllowedExecutionTime = value
        }

    val stoppingMessage
        get() = eventExecutionProcess.stoppingMessage

    val isExecutionTimeExceeded
        get() = eventExecutionProcess.isExecutionTimeExceeded

    internal fun initialize() {
        eventExecutionProcess.initialize()
    }

    /**
     * Causes the next event to be executed if it exists
     *
     */
    internal fun executeNextEvent() {
        eventExecutionProcess.runNext()
    }

    /*
     * Executes all the events in the calendar, clears the calendar if the
     * Executive has not been initialized
     *
     */
    internal fun executeAllEvents() {
        if (!willTerminate()) {
            // no end event scheduled, no max exec time, warn the user
            if (terminationWarningMsgOption) {
                val sb = StringBuilder()
                sb.append("Executive: In initializeIterations()")
                sb.appendLine()
                sb.append("The executive was told to run all events without an end event scheduled.")
                sb.appendLine()
                sb.append("There was no maximum real-clock execution time specified.")
                sb.appendLine()
                sb.append("The user is responsible for ensuring that the Executive is stopped.")
                sb.appendLine()
                Simulation.logger.warn(sb.toString())
                System.out.flush()
            }
        }
        eventExecutionProcess.run()
    }

    internal fun stop(msg: String = "The executive was told to stop by the user at time $currentTime") {
        eventExecutionProcess.stop(msg)
    }

    internal fun end(msg: String = "The executive was told to end by the user at time $currentTime") {
        eventExecutionProcess.end(msg)
    }

    /**
     * Creates an event and schedules it onto the event calendar
     *
     * @param T the type of the event message
     * @param eventAction represents an ActionListener that will handle the change of state logic, cannot be null
     * @param interEventTime represents the inter-event time, i.e. the interval from the
     * current time to when the event will need to occur, Cannot be negative
     * @param priority is used to influence the ordering of events
     * @param message is a generic Object that may represent data to be
     * transmitted with the event, may be null
     * @param name the name of the event, can be null
     * @param theElementScheduling the element doing the scheduling, cannot be null
     * @return a valid JSLEvent
     */
    internal fun <T> scheduleEvent(
        theElementScheduling: ModelElement, eventAction: ModelElement.EventActionIfc<T>, interEventTime: Double, message: T? = null,
        priority: Int, name: String? = null
    ): JSLEvent<T> {
        if (eventExecutionProcess.isCreated || eventExecutionProcess.isEnded) {
            val sb = StringBuilder()
            sb.append("Attempted to schedule an event when the Executive is in the created or ended state.")
            sb.appendLine()
            sb.append("Since the Executive has not yet been initialized this event will not execute.")
            sb.appendLine()
            sb.append("The event was scheduled from ModelElement : ").append(theElementScheduling.name)
            sb.appendLine()
            sb.append("It is likely that the user scheduled the event in a ModelElement's constructor or outside the context of the simulation running.")
            sb.appendLine()
            sb.append("Hint: Do not schedule initial events in a constructor.  Use the initialize() method instead.")
            sb.appendLine()
            sb.append("Hint: Do not schedule initial events prior to executing (running) the simulation.  Use the initialize() method instead.")
            sb.appendLine()
            Simulation.logger.warn(sb.toString())
            System.out.flush()
            throw JSLEventException(sb.toString())
        }
        if (interEventTime < 0.0) {
            Simulation.logger.warn("Attempted to schedule an event before the Current Time!")
            System.out.flush()
            throw JSLEventException("Attempted to schedule an event before the Current Time!")
        }
        val eventTime = currentTime + interEventTime
        if (eventTime <= scheduledEndTime()) {
            numEventsScheduled = numEventsScheduled + 1
            // create the event
            val event =
                JSLEvent(numEventsScheduled, eventAction, eventTime, priority, message, name, theElementScheduling)
            myEventCalendar.add(event)
            event.scheduled = true
            return event
        } else {
            val sb = StringBuilder()
            sb.append("Attempted to schedule an event after the scheduled simulation end time: ${scheduledEndTime()}")
            sb.appendLine()
            sb.append("The event was scheduled from ModelElement : ").append(theElementScheduling.name)
            sb.appendLine()
            Simulation.logger.warn(sb.toString())
            System.out.flush()
            throw JSLEventException(sb.toString())
        }
    }

    /**
     * Executes the provided event
     *
     * @param event represents the next event to execute
     */
    private fun execute(event: JSLEvent<*>) {
        try {
            // the event is no longer scheduled
            event.scheduled = false
            if (!event.cancelled) {
                // event was not cancelled
                // update the current simulation time to the event time
                currentTime = event.time
                status = Status.BEFORE_EVENT
                notifyObservers(event)
                event.execute()
                lastExecutedEvent = event
                numEventsExecuted = numEventsExecuted + 1
                status = Status.AFTER_EVENT
                notifyObservers(event)
                performCPhase()
            }
        } catch (e: RuntimeException) {
            val sb = StringBuilder()
            sb.append("######################################")
            sb.appendLine()
            sb.append("A RuntimeException occurred near this event:")
            sb.appendLine()
            sb.append(event)
            sb.appendLine()
            sb.append("######################################")
            sb.appendLine()
            sb.appendLine()
            val sim = event.modelElement.myModel.mySimulation //TODO is there a better way to get the simulation, is it needed?
            sb.append(sim)
            Simulation.logger.error(sb.toString())
            System.out.flush()
            throw e
        }
    }

    private fun performCPhase() {
        val ne: JSLEvent<*>? = myEventCalendar.peekNext()
        if (ne == null) {
            return
        } else if (ne.time > currentTime) {
            conditionalActionProcessor.performCPhase()
        }
    }

    /**
     * Tells the event calendar to clear all the events. Resets the simulation
     * time to 0.0. Resets the event identification counter. Unregisters all
     * actions Notifies observers of initialization
     */
    private fun initializeCalendar() {
        endEvent = null
        lastExecutedEvent = null
        currentTime = 0.0
        endingTime = Double.NaN
        myEventCalendar.clear()
        unregisterAllActions()
        numEventsScheduled = 0
        numEventsExecuted = 0
        status = Status.INITIALIZED
        notifyObservers(null)
    }

    internal fun unregisterAllActions() {
        conditionalActionProcessor.unregisterAllActions()
    }

    internal fun register(action: ConditionalAction, priority: Int = ConditionalActionProcessor.DEFAULT_PRIORITY) {
        conditionalActionProcessor.register(action, priority)
    }

    internal fun changePriority(action: ConditionalAction, priority: Int) {
        conditionalActionProcessor.changePriority(action, priority)
    }

    internal fun unregister(action: ConditionalAction) {
        conditionalActionProcessor.unregister(action)
    }

    var maxScans: Int
        get() = conditionalActionProcessor.maxScans
        set(value) {
            conditionalActionProcessor.maxScans = value
        }

    var maxScansFlag: Boolean
        get() = conditionalActionProcessor.maxScanFlag
        set(value) {
            conditionalActionProcessor.maxScanFlag = value
        }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("Executive: ")
        sb.appendLine()
        sb.append("Number of events scheduled: ")
        sb.append(numEventsScheduled)
        sb.appendLine()
        sb.append("Number of events scheduled during execution: ")
        sb.append(numEventsScheduledDuringExecution)
        sb.appendLine()
        sb.append("Number of events executed: ")
        sb.append(numEventsExecuted)
        sb.appendLine()
        sb.append("Scheduled end time: ")
        sb.append(scheduledEndTime())
        sb.appendLine()
        sb.append("Actual Ending time: ")
        sb.append(endingTime)
        sb.appendLine()
        sb.append("Current time: ")
        sb.append(currentTime)
        sb.appendLine()
        sb.append(eventExecutionProcess)
        return sb.toString()
    }
    private inner class EventExecutionProcess(name: String?) : IterativeProcess<JSLEvent<*>>(name) {

        override fun initializeIterations() {
            super.initializeIterations()
            initializeCalendar()
        }

        override fun endIterations() {
            super.endIterations()
            // record the actual ending time
            endingTime = currentTime
            // record # events scheduled during execution
            numEventsScheduledDuringExecution = numEventsScheduled.toDouble()
            // set observer state and notify observers
            status = Status.AFTER_EXECUTION
            this@Executive.notifyObservers(null)
        }

        override fun hasNextStep(): Boolean {
            return hasNextEvent()
        }

        override fun nextStep(): JSLEvent<*>? {
            return myEventCalendar.nextEvent()
        }

        override fun runStep() {
            myCurrentStep = nextStep()
            if (myCurrentStep != null) {
                execute(myCurrentStep!!)
            }
        }
    }
}