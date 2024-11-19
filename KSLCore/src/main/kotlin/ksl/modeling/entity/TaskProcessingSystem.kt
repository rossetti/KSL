package ksl.modeling.entity

import ksl.modeling.queue.Queue
import ksl.simulation.ModelElement
import ksl.utilities.statistic.State


open class TaskProcessingSystem(
    parent: ModelElement,
    name: String? = null
) : ProcessModel(parent, name) {

    val WORK = nextTypeConstant()
    val FAILURE = nextTypeConstant()
    val BREAK = nextTypeConstant()

    companion object {
        private var myTaskTypeCounter = 0

        fun nextTypeConstant(): Int {
            return myTaskTypeCounter++
        }
    }

    /**
     * Provides the ability to react to the completion of a task that was
     * started.
     */
    interface TaskCompletedIfc {

        /**
         * Called when the task is completed
         */
        fun taskCompleted(task: Task)
    }

    abstract inner class Task(
        val taskStarter: TaskCompletedIfc,
        val taskType: Int = WORK
    ) : Entity() {

        /**
            The time that the task started.  Double.NaN if never started.
         */
        var startTime: Double = Double.NaN
            internal set

        /**
         *  The time that the task ended. Double.NaN if never started or ended.
         */
        var endTime: Double = Double.NaN
            internal set

        /**
         *  The elapsed time taken by the task, end time minus start time
         */
        val elapsedTime: Double
            get() = endTime - startTime

        /**
         *  The processor assigned to execute the task's process
         */
        var taskProcessor: TaskProcessor? = null

        /**
         * The deadline may be used by the worker to assist with task selection
         */
        var deadline: Double = Double.POSITIVE_INFINITY
            set(value) {
                require((value > 0.0) || (value.isInfinite())) { "The deadline must be > 0.0 or infinite" }
                field = value
            }

        abstract val taskProcess: KSLProcess

    }

    open inner class TaskProcessor(
        val taskQueue: Queue<Task>,
    ) : Entity() {
        //TODO consider a TaskProcessorIfc interface

        var currentTask: Task? = null
        var previousTask: Task? = null
        val idleState : State = State(name = "Idle" )
        val busyState : State = State(name = "Busy" )
        val failedState : State = State(name = "Failed" )
        val inactiveState : State = State(name = "Inactive" )
        var currentState: State = idleState
            private set(value) {
                field.exit(time) // exit the current state
                field = value // update the state
                field.enter(time) // enter the new state
            }

        fun isBusy(): Boolean {
            return currentState === busyState
        }

        fun isFailed(): Boolean {
            return currentState === failedState
        }

        fun isIdle(): Boolean {
            return currentState === idleState
        }

        fun isInactive(): Boolean {
            return currentState === inactiveState
        }

        /**
         *  Receives the task for processing
         */
        fun receiveTask(task: Task, deadline: Double = Double.POSITIVE_INFINITY) {
            require(currentProcess != task.taskProcess) { "The task ${task.taskProcess.name} is the same as the current process! " }
            require(task.taskProcess.isCreated) { "The supplied process ${task.taskProcess.name} must be in the created state. It's state was: ${task.taskProcess.currentStateName}" }
            if (task.deadline != deadline) {
                task.deadline = deadline
            }
            task.taskProcessor = this
            taskQueue.enqueue(task)
            // if worker is idle then activate the worker's task processing
            if (isIdle()) {
                activate(taskProcessing)
            }
        }

        /**
         *  Describes how to process a task. If there are tasks, a new task
         *  is selected and then executed.
         */
        val taskProcessing = process("${this.name}_TaskProcessing") {
            while (hasNextTask()) {
                val nextTask = selectNextTask() ?: break
                taskQueue.remove(nextTask)
                currentTask = nextTask
                beforeTaskExecution()
                // set the state based on the task type
                updateState(nextTask)
                waitFor(nextTask.taskProcess)
                afterTaskExecution()
                nextTask.taskStarter.taskCompleted(nextTask)
                previousTask = nextTask
                currentTask = null
            }
            currentState = idleState
        }

        protected fun updateState(task: Task) {
            when (task.taskType) {
                BREAK -> { currentState = inactiveState }
                FAILURE -> { currentState = failedState }
                WORK -> { currentState = busyState }
            }
        }

        protected open fun beforeTaskExecution() {

        }

        protected open fun afterTaskExecution() {

        }

        /**
         *  Indicates true if selectNextTask() results in a non-null task
         */
        fun hasNextTask(): Boolean {
            return selectNextTask() != null
        }

        /**
         *  Finds the next task to work on. Does not remove the task
         *  from the task queue. If null, then a new task could not be selected.
         */
        open fun selectNextTask(): Task? {
            return taskQueue.peekNext()
        }
    }
}