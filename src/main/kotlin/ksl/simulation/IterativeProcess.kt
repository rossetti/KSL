package ksl.simulation

import jsl.simulation.IllegalStateException

/**
 * A counter to count the number of created to assign "unique" ids
 */
private var idCounter_: Int = 0

open class IterativeProcess : IterativeProcessIfc {

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
     * A reference to the current state of the iterative process
     */
    protected var state: IterativeProcess.IterativeState? = null
        protected set(value) = TODO("Not yet implemented")

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

    override val allStepsCompleted: Boolean
        get() = endingStatus == IterativeProcessIfc.EndingStatus.COMPLETED_ALL_STEPS

    override val stoppedByCondition: Boolean
        get() = endingStatus == IterativeProcessIfc.EndingStatus.MET_STOPPING_CONDITION

    override val isUnfinished: Boolean
        get() = endingStatus == IterativeProcessIfc.EndingStatus.UNFINISHED

    override val isExecutionTimeExceeded: Boolean
        get() = endingStatus == IterativeProcessIfc.EndingStatus.EXCEEDED_EXECUTION_TIME

    override val noStepsExecuted: Boolean
        get() = endingStatus == IterativeProcessIfc.EndingStatus.NO_STEPS_EXECUTED

    override val stoppingMessage: String
        get() = endingStatus.msg

    override var isRunningStep: Boolean = false
        protected set

    override var isRunning: Boolean = false
        protected set

    override var stopping: Boolean = false
        protected set

    override fun initialize() {
        TODO("Not yet implemented")
    }

    override fun runNext() {
        TODO("Not yet implemented")
    }

    override fun run() {
        TODO("Not yet implemented")
    }

    override fun end(msg: String?) {
        TODO("Not yet implemented")
    }

    override fun stop(msg: String?) {
        TODO("Not yet implemented")
    }

    protected fun initializeIterations() {
        TODO("Not yet implemented")
    }

    protected fun runAll_() {
        TODO("Not yet implemented")
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