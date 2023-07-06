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

package ksl.simulation

import ksl.calendar.CalendarIfc
import ksl.calendar.PriorityQueueEventCalendar
import ksl.utilities.exceptions.KSLEventException
import ksl.utilities.observers.Observable
import kotlin.time.Duration

class Executive(private val myEventCalendar: CalendarIfc = PriorityQueueEventCalendar()) : Observable<KSLEvent<*>?>(){

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

    private var lastExecutedEvent: KSLEvent<*>? = null

    internal var endEvent: KSLEvent<Nothing>? = null

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
            if (maximumAllowedExecutionTime == Duration.ZERO) {
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
                Model.logger.warn(sb.toString())
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
    ): KSLEvent<T> {
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
            Model.logger.warn(sb.toString())
            System.out.flush()
            throw KSLEventException(sb.toString())
        }
        if (interEventTime < 0.0) {
            Model.logger.warn("Attempted to schedule an event before the Current Time!")
            System.out.flush()
            throw KSLEventException("Attempted to schedule an event before the Current Time!")
        }
        val eventTime = currentTime + interEventTime
        if (eventTime <= scheduledEndTime()) {
            numEventsScheduled = numEventsScheduled + 1
            // create the event
            val event =
                KSLEvent(numEventsScheduled, eventAction, eventTime, priority, message, name, theElementScheduling)
            event.name = name
            myEventCalendar.add(event)
            event.isScheduled = true
            return event
        } else {
            val event = KSLEvent(-99, eventAction, eventTime, priority, message, name, theElementScheduling)
            val sb = StringBuilder()
            sb.append("Attempted to schedule an event, $event, after the scheduled simulation end time: ${scheduledEndTime()} the event was not scheduled and will not execute")
            Model.logger.trace(sb.toString())
            return event
        }
    }

    /**
     * Executes the provided event
     *
     * @param event represents the next event to execute
     */
    private fun execute(event: KSLEvent<*>) {
        try {
            // the event is no longer scheduled
            event.isScheduled = false
            if (!event.cancel) {
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
            Model.logger.error(e.message)
            System.err.println(e.message)
            val sb = StringBuilder()
            sb.append("######################################")
            sb.appendLine()
            sb.append("A RuntimeException occurred near this event:")
            sb.appendLine()
            sb.append(event)
            sb.appendLine()
            if (event.entity != null){
                sb.appendLine("Entity Information:")
                sb.appendLine(event.entity)
                if (event.entity!!.currentProcess != null)
                    sb.appendLine(event.entity!!.currentProcess)
            }
            sb.appendLine()
            sb.appendLine(event.modelElement.myModel)
            sb.append("######################################")
            sb.appendLine()
            sb.appendLine()
            Model.logger.error(sb.toString())
            System.err.println(sb.toString())
            System.err.flush()
            System.out.flush()
            throw e
        }
    }

    private fun performCPhase() {
        val ne: KSLEvent<*>? = myEventCalendar.peekNext()
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
    internal fun initializeCalendar() {
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
    private inner class EventExecutionProcess(name: String?) : IterativeProcess<KSLEvent<*>>(name) {

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

        override fun nextStep(): KSLEvent<*>? {
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