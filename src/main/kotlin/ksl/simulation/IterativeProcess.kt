package ksl.simulation

import jsl.simulation.IllegalStateException

/**
 * A counter to count the number of created to assign "unique" ids
 */
private var idCounter_: Int = 0

open class IterativeProcess : IterativeProcessIfc {
    /**
     * A reference to the current state of the iterative process
     */
    protected var state: IterativeProcess.IterativeState? = null

    override val isDone: Boolean
        get() = TODO("Not yet implemented")

    override val isExecutionTimeExceeded: Boolean
        get() = TODO("Not yet implemented")

    override val beginExecutionTime: Long
        get() = TODO("Not yet implemented")

    override val endExecutionTime: Long
        get() = TODO("Not yet implemented")

    override var maximumAllowedExecutionTime: Long
        get() = TODO("Not yet implemented")
        set(value) {}

    override val numberStepsCompleted: Long
        get() = TODO("Not yet implemented")

    override val isCreated: Boolean
        get() = TODO("Not yet implemented")

    override val isInitialized: Boolean
        get() = TODO("Not yet implemented")

    override val isRunning: Boolean
        get() = TODO("Not yet implemented")

    override val isStepCompleted: Boolean
        get() = TODO("Not yet implemented")

    override val isEnded: Boolean
        get() = TODO("Not yet implemented")

    override val allStepsCompleted: Boolean
        get() = TODO("Not yet implemented")

    override val stoppedByCondition: Boolean
        get() = TODO("Not yet implemented")

    override val isUnfinished: Boolean
        get() = TODO("Not yet implemented")

    override val isRunningStep: Boolean
        get() = TODO("Not yet implemented")

    override val noStepsExecuted: Boolean
        get() = TODO("Not yet implemented")

    override val stoppingMessage: String?
        get() = TODO("Not yet implemented")

    override val stopping: Boolean
        get() = TODO("Not yet implemented")

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