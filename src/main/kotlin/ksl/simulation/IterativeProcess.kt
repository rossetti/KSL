package ksl.simulation

import ksl.utilities.exceptions.IllegalStateException
import ksl.utilities.exceptions.NoSuchStepException
import ksl.simulation.IterativeProcessIfc.EndingStatus.*
import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.observers.Observable
import ksl.utilities.observers.ObservableIfc
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

abstract class IterativeProcess<T>(name: String? = null) : IdentityIfc by Identity(name),
    IterativeProcessIfc, ObservableIfc<T> by Observable() {

    /**
     * A reference to the created state for the iterative process A iterative
     * process is in the created state when it is first constructed and can then
     * only transition to the initialized state
     */
    protected val myCreatedState: Created = Created()

    /**
     * A reference to the initialized state of the iterative process A iterative
     * process is in the initialized state after the method initialize() is
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
            logger.trace { "current state = $field, transitioning to state = $value" }
            field = value
            notifyObservers(this, myCurrentStep)
        }

    override var endingStatus: IterativeProcessIfc.EndingStatus = UNFINISHED
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

    override var numberStepsCompleted: Int = 0
        protected set

    override val isCreated: Boolean
        get() = state == myCreatedState

    override val isInitialized: Boolean
        get() = state == myInitializedState

    override val isStepCompleted: Boolean
        get() = state == myStepCompletedState

    override val isEnded: Boolean
        get() = state == myEndedState

    override var stoppingMessage: String? = UNFINISHED.msg

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
        if (!hasNextStep()) {
            val s = StringBuilder()
            s.append("Iterative Process: No such step exception!")
            s.appendLine()
            s.append(toString())
            logger.error { s.toString() }
            throw NoSuchStepException(s.toString())
        }
        logger.trace { "Running a step of the process: $name" }
        state.runNext()
    }

    /**
     * This method should check to see if another step is necessary for the
     * iterative process. True means that the process has another step to be
     * executed. False, means that no more steps are available for execution.
     *
     * @return true if another step is present
     */
    abstract fun hasNextStep(): Boolean

    /**
     * This method should return the next step to be executed in the iterative
     * process or null if no more steps can be executed. It should advance the
     * current step to the next step if it is available
     *
     * @return the type of the step
     */
    protected abstract fun nextStep(): T?

    final override fun run() {
        runAllSteps()
    }

    final override fun end(msg: String?) {
        stoppingMessage = msg
        state.end()
    }

    final override fun stop(msg: String?) {
        stoppingMessage = msg
        stopping = true
    }

    protected open fun initializeIterations() {
        logger.trace { "Initializing the process: $name" }
        stoppingMessage = null
        stopping = false
        isDone = false
        isRunningStep = false
        isRunning = false
        numberStepsCompleted = 0
        beginExecutionTime = System.nanoTime()
        state = myInitializedState
    }

    protected fun runAllSteps() {
        logger.trace { "Running all the steps of: $name" }
        if (!isInitialized) {
            initialize()
        }
        if (hasNextStep()) {
            while (!isDone) {
                runNext()
            }
        } else {
            // no steps to execute
            isDone = true
            endingStatus = NO_STEPS_EXECUTED
            stoppingMessage = endingStatus.msg
        }
        endIterations()
    }

    protected fun runNextStep() {
        isRunning = true
        isRunningStep = true
        runStep()
        isRunningStep = false
        numberStepsCompleted++
        state = myStepCompletedState
        stoppingConditionCheck()
    }

    protected open fun checkStoppingCondition() {}

    private fun stoppingConditionCheck() {
        checkStoppingCondition()
        if (stopping) {
            // user called stop on the process
            isDone = true
            endingStatus = MET_STOPPING_CONDITION
            if (stoppingMessage == null) {
                // user message not available, set message to default
                stoppingMessage = endingStatus.msg
            }
        } else {
            // user did not call stop, check if it needs to stop
            if (!hasNextStep()) {
                // no more steps
                isDone = true
                endingStatus = COMPLETED_ALL_STEPS
                stoppingMessage = endingStatus.msg
            } else if (isExecutionTimeExceeded) {
                isDone = true
                endingStatus = EXCEEDED_EXECUTION_TIME
                stoppingMessage = endingStatus.msg
            }
        }
    }

    /**
     * This method tells the iterative process to execute the current step.
     * Typical usage is to call this after calling next() to advance to the next
     * step. This method should throw a NoSuchStepException if there are no more
     * steps to run, and it is told to run the step.
     *
     */
    protected abstract fun runStep()

    protected open fun endIterations() {
        logger.trace { "Ending the process: $name" }
        isRunning = false
        isRunningStep = false
        isDone = true
        endExecutionTime = System.nanoTime()
        state = myEndedState
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
            logger.error { sb.toString() }
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
            logger.error { sb.toString() }
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
            logger.error { sb.toString() }
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
            logger.error { sb.toString() }
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
            runNextStep()
        }

        override fun runAll() {
            runAllSteps()
        }

        override fun end() {
            endIterations()
        }
    }

    protected inner class StepCompleted : IterativeState("StepCompleted") {
        override fun runNext() {
            runNextStep()
        }

        override fun runAll() {
            runAllSteps()
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
