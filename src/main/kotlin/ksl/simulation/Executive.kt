package ksl.simulation

import ksl.calendar.CalendarIfc
import ksl.calendar.PriorityQueueEventCalendar
import ksl.utilities.io.KSL
import ksl.utilities.observers.Observable
import ksl.utilities.observers.ObservableIfc
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

open class Executive(private val myEventCalendar: CalendarIfc = PriorityQueueEventCalendar()) :
    ObservableIfc<JSLEvent<*>?> by Observable() {

    enum class Status {
        CREATED, INITIALIZED, BEFORE_EVENT, AFTER_EVENT, AFTER_EXECUTION
    }

    var myObserverState = Status.CREATED
        protected set

    var myCurrentTime: Double = 0.0
        protected set

    var myNumEventsScheduled: Long = 0
        protected set

    var myNumEventsScheduledDuringExecution: Double = Double.NaN
        protected set

    var myNumEventsExecuted: Long = 0
        protected set

    var myActualEndingTime: Double = Double.NaN
        protected set

    private var myLastExecutedEvent: JSLEvent<*>? = null

    private var myEndEvent: JSLEvent<*>? = null

    var myTerminationWarningMsgOption = true

    /**
     * Returns the time the execution was scheduled to end
     *
     * @return the scheduled end time or Double.POSITIVE_INFINITY
     */
    fun getScheduledEndTime(): Double {
        return myEndEvent?.time ?: Double.POSITIVE_INFINITY
    }

    /**
     * Returns true if an event has been scheduled to stop execution at
     * getTimeHorizon()
     *
     * @return true if scheduled
     */
    fun isEndEventScheduled(): Boolean {
        return myEndEvent != null
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
     * Executes the provided event
     *
     * @param event represents the next event to execute or null
     */
    protected open fun execute(event: JSLEvent<*>) {
        try {
            // the event is no longer scheduled
            event.scheduled = false
            if (!event.cancelled) {
                // event was not cancelled
                // update the current simulation time to the event time
                myCurrentTime = event.time
                myObserverState = Status.BEFORE_EVENT
                notifyObservers(this, event)
                event.execute()
                myLastExecutedEvent = event
                myNumEventsExecuted = myNumEventsExecuted + 1
                myObserverState = Status.AFTER_EVENT
                notifyObservers(this, event)
//TODO                    performCPhase()
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
            val sim = event.modelElement.simulation //TODO is there a better way to get the simulation?
            sb.append(sim)
            KSL.logger.error(sb.toString())
            throw e
        }
    }

    /**
     * Tells the event calendar to clear all the events. Resets the simulation
     * time to 0.0. Resets the event identification counter. Unregisters all
     * actions Notifies observers of initialization
     */
    protected open fun initializeCalendar() {
        myEndEvent = null
        myLastExecutedEvent = null
        myCurrentTime = 0.0
        myActualEndingTime = Double.NaN
        myEventCalendar.clear()
//TODO        unregisterAllActions()
        myNumEventsScheduled = 0
        myNumEventsExecuted = 0
        myObserverState = Status.INITIALIZED
        notifyObservers(this, null)
    }

    /**
     * This method is called before any events are executed and after
     * initializing the iterative process. It can be used to insert behavior
     * after initializing
     *
     */
    protected open fun beforeExecutingAnyEvents() {}

    /**
     * This method is called after executing all events when ending the
     * iterative process. It can be used to insert behavior after the executive
     * ends
     *
     */
    protected open fun afterExecution() {}

    protected inner class EventExecutionProcess(name: String?) : IterativeProcess<JSLEvent<*>>(name) {

        override fun initializeIterations() {
            super.initializeIterations()
            initializeCalendar()
            beforeExecutingAnyEvents()
        }

        override fun endIterations() {
            super.endIterations()
            // record the actual ending time
            myActualEndingTime = myCurrentTime
            // record # events scheduled during execution
            myNumEventsScheduledDuringExecution = myNumEventsScheduled.toDouble()
            // set observer state and notify observers
            myObserverState = Status.AFTER_EXECUTION
            this@Executive.notifyObservers(this@Executive, null)
            afterExecution()
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