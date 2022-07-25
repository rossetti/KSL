package ksl.simulation

import jsl.simulation.IllegalStateException
import jsl.simulation.NoSuchStepException
import ksl.utilities.observers.Observable
import ksl.utilities.observers.ObservableIfc
import mu.KLoggable
import mu.KotlinLogging

/**
 * A counter to count the number of created to assign "unique" ids
 */
private var idCounter_: Int = 0

private val logger = KotlinLogging.logger {}

abstract class IterativeProcess<T> : IterativeProcessIfc, ObservableIfc<T> by Observable() {

    /**
     * A reference to the created state for the iterative process A iterative
     * process is in the created state when it is first constructed and can then
     * only transition to the initialized state
     */
    protected val myCreatedState: Created = Created()

    /**
     * A reference to the initialized state of the iterative process A iterative
     * process is in the initialized state after the initialize() method is
     * called from a proper state.
     */
    protected val myInitializedState: Initialized = Initialized()

    /**
     * A reference to the step completed state of the iterative process A
     * iterative process is in the step completed state after the runNext method
     * is called from a proper state
     */
    protected val myStepCompletedState: StepCompleted = StepCompleted()

    /**
     * A reference to the ended state of the iterative process A iterative
     * process is in the ended state after the process is told to end
     */
    protected val myEndedState: Ended = Ended()

    /**
     * A reference to an object related to the current step of the process It
     * can be passed to observers
     */
    protected var myCurrentStep: T? = null

    /**
     * A reference to the current state of the iterative process
     */
    protected var state: IterativeProcess<T>.IterativeState = Created()
        protected set(value) {
            field = value
            notifyObservers(this, myCurrentStep)
        }

    override var endingStatus: IterativeProcessIfc.EndingStatus = IterativeProcessIfc.EndingStatus.UNFINISHED
        protected set

    override var isDone: Boolean = false
        protected set

    override var beginExecutionTime: Long = -1
        protected set

    override var endExecutionTime: Long = -1
        protected set

    override var maximumAllowedExecutionTime: Long = 0
        set(value) {
            require(value > 0) { "The maximum allowed execution time must be > 0" }
            field = value
        }

    override var numberStepsCompleted: Long = 0
        protected set

    override val isCreated: Boolean
        get() = state == myCreatedState

    override val isInitialized: Boolean
        get() = state == myInitializedState

    override val isStepCompleted: Boolean
        get() = state == myStepCompletedState

    override val isEnded: Boolean
        get() = state == myEndedState

    override var stoppingMessage: String? = null

    override var isRunningStep: Boolean = false
        protected set

    override var isRunning: Boolean = false
        protected set

    override var stopping: Boolean = false
        protected set

    final override fun initialize() {
        state.initialize()
    }

    final override fun runNext() {
        if (!hasNext()) {
            val s = StringBuilder()
            s.append("Iterative Process: No such step exception!\n")
            s.append(toString())
            throw NoSuchStepException(s.toString())
        }
        state.runNext()
    }

    /**
     * This method should check to see if another step is necessary for the
     * iterative process. True means that the process has another step to be
     * executed. False, means that no more steps are available for execution.
     *
     * @return true if another step is present
     */
    protected abstract operator fun hasNext(): Boolean

    /**
     * This method should return the next step to be executed in the iterative
     * process or null if no more steps can be executed. It should advance the
     * current step to the next step if it is available
     *
     * @return the type of the step
     */
    protected abstract operator fun next(): T?

    final override fun run() {
        runAll_()
    }

    final override fun end(msg: String?) {
        stoppingMessage = msg
        state.end()
    }

    final override fun stop(msg: String?) {
        stoppingMessage = msg
        stopping = true
    }

    protected fun initializeIterations() {
        stoppingMessage = null
        stopping = false
        isDone = false
        isRunningStep = false
        isRunning = false
        numberStepsCompleted = 0
        beginExecutionTime = System.nanoTime()
        state = myInitializedState
        TODO("Not yet implemented")
    }

    protected fun runAll_() {
        if (!isInitialized) {
            initialize()
        }
        if (hasNext()) {
            while (!isDone) {
                runNext()
            }
        } else {
            // no steps to execute
            isDone = true
            endingStatus = IterativeProcessIfc.EndingStatus.NO_STEPS_EXECUTED
        }
        endIterations()
    }

    protected fun runNext_() {
        TODO("Not yet implemented")
    }

    protected fun endIterations() {
        TODO("Not yet implemented")
    }

    open inner class IterativeState(private val name: String) {
        open fun initialize() {
            val sb = StringBuilder()
            sb.appendLine()
            sb.append("Tried to initialize ")
            sb.append(name)
            sb.append(" from an illegal state: ")
            sb.append(state.toString())
            sb.appendLine()
            sb.append(this@IterativeProcess.toString())
            throw IllegalStateException(sb.toString())
        }

        open fun runNext() {
            val sb = StringBuilder()
            sb.appendLine()
            sb.append("Tried to run the next step of ")
            sb.append(name)
            sb.append(" from an illegal state: ")
            sb.append(state.toString())
            sb.appendLine()
            sb.append(this@IterativeProcess.toString())
            throw IllegalStateException(sb.toString())
        }

        open fun runAll() {
            val sb = StringBuilder()
            sb.appendLine()
            sb.append("Tried to run all the steps of ")
            sb.append(name)
            sb.append(" from an illegal state: ")
            sb.append(state.toString())
            sb.appendLine()
            sb.append(this@IterativeProcess.toString())
            throw IllegalStateException(sb.toString())
        }

        open fun end() {
            val sb = StringBuilder()
            sb.appendLine()
            sb.append("Tried to end ")
            sb.append(name)
            sb.append(" from an illegal state: ")
            sb.append(state.toString())
            sb.appendLine()
            sb.append(this@IterativeProcess.toString())
            throw IllegalStateException(sb.toString())
        }

        override fun toString(): String {
            return name
        }
    }

    protected inner class Created : IterativeState("CreatedState") {
        override fun initialize() {
            initializeIterations()
        }

        override fun end() {
            endIterations()
        }
    }

    protected inner class Initialized : IterativeState("InitializedState") {
        override fun runNext() {
            runNext_()
        }

        override fun runAll() {
            runAll_()
        }

        override fun end() {
            endIterations()
        }
    }

    protected inner class StepCompleted : IterativeState("StepCompleted") {
        override fun runNext() {
            runNext_()
        }

        override fun runAll() {
            runAll_()
        }

        override fun end() {
            endIterations()
        }
    }

    protected inner class Ended : IterativeState("EndedState") {
        override fun initialize() {
            initializeIterations()
        }
    }

}