package ksl.modeling.entity

import ksl.modeling.queue.Queue
import ksl.simulation.ModelElement
import ksl.utilities.ConstantValue
import ksl.utilities.GetValueIfc
import ksl.utilities.random.rvariable.ConstantRV
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
    interface TaskSenderIfc {

        /**
         * Called when the task is completed
         */
        fun taskCompleted(task: Task)

        /**
         *  Called if the task processor is about to start a failure task.
         *  Subclasses can provide logic to react to the occurrence of a failure
         *  for which it has current tasks pending or where it might send future tasks.
         */
        fun onTaskProcessorFailure(taskProcessor: TaskProcessor) {}

        /**
         *  Called if the task processor has completed a failure task.
         *  Subclasses can provide logic to react to the processor coming back after
         *  a failure.
         */
        fun onTaskProcessorRepaired(taskProcessor: TaskProcessor) {}

        /**
         *  Called if the task processor is about to start an inactive task.
         *  Subclasses can provide logic to react to the occurrence of an inactive
         *  period for the processor.
         */
        fun onTaskProcessorInactive(taskProcessor: TaskProcessor) {}

        /**
         *  Called if the task processor has completed an inactive period.
         *  Subclasses can provide logic to react to the occurrence of the processor
         *  returning from an inactive period.
         */
        fun onTaskProcessorActive(taskProcessor: TaskProcessor) {}

        /**
         *  Called if the task processor is about to be shutdown (permanently).
         *  Subclasses can provide logic to react to a task processor being permanently shutdown.
         */
        fun onTaskProcessorShutdown(taskProcessor: TaskProcessor) {}
    }

    interface TaskReceiverIfc {
        fun receiveTask(task: Task, deadline: Double = Double.POSITIVE_INFINITY)
    }

    abstract inner class Task(
        val taskStarter: TaskSenderIfc,
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

        /**
         *  Called by the processor immediately before starting the task
         */
        open fun beforeTaskStart() {}

        /**
         *  Called by the processor immediately after the task completes and before the sender
         */
        open fun afterTaskCompleted() {}

    }

    inner class WorkTask(
        var workTime: GetValueIfc, taskSender: TaskSenderIfc
    ) : Task(taskSender, WORK) {

        constructor(workTime: Double, taskSender: TaskSenderIfc) : this(ConstantValue(workTime), taskSender)

        override val taskProcess: KSLProcess = process {
            delay(workTime)
        }
    }

    inner class FailureTask(
        var downTime: GetValueIfc, taskSender: TaskSenderIfc
    ) : Task(taskSender, FAILURE) {

        constructor(downTime: Double, taskSender: TaskSenderIfc) : this(ConstantValue(downTime), taskSender)

        override val taskProcess: KSLProcess = process {
            delay(downTime)
        }
    }

    inner class InactiveTask(
        var awayTime: GetValueIfc, taskSender: TaskSenderIfc
    ) : Task(taskSender, FAILURE) {

        constructor(awayTime: Double, taskSender: TaskSenderIfc) : this(ConstantValue(awayTime), taskSender)

        override val taskProcess: KSLProcess = process {
            delay(awayTime)
        }
    }

    open inner class TaskProcessor(
        val taskQueue: Queue<Task>,
    ) : Entity(), TaskReceiverIfc {
        //TODO consider a TaskProcessorIfc interface

        //TODO consider ability to shutdown the processor, types of shutdown (graceful, hard)
        // shutdown is permanent deactivation, would need to notify sender of tasks
        // consider the ability to allow sender to react to failure, react to inactive, react to shutdown

        //TODO consider generalizing starting state

        var currentTask: Task? = null
        var previousTask: Task? = null

        val idleState: State = State(name = "Idle")

        init {
            idleState.enter(time)
        }

        val busyState: State = State(name = "Busy")
        val failedState: State = State(name = "Failed")
        val inactiveState: State = State(name = "Inactive")

        var currentState: State = idleState
            private set(value) {
                field.exit(time) // exit the current state
                field = value // update the state
                field.enter(time) // enter the new state
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
                beforeTaskExecution()//TODO is this necessary
                // set the state based on the task type
                updateState(nextTask) //TODO need to notify senders before failure, inactive starts
                waitFor(nextTask.taskProcess)
                afterTaskExecution() //TODO is this necessary
                nextTask.taskStarter.taskCompleted(nextTask)
                previousTask = nextTask
                currentTask = null
            }
            currentState = idleState
        }

        init {
            if (taskQueue.isNotEmpty && isIdle()) {
                activate(taskProcessing)
            }
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

        val numTimesFailed: Double
            get() = failedState.numberOfTimesEntered

        val numTimesInactive: Double
            get() = inactiveState.numberOfTimesExited

        val numTimesIdle: Double
            get() = idleState.numberOfTimesEntered

        val numTimesBusy: Double
            get() = busyState.numberOfTimesEntered

        val totalIdleTime: Double
            get() = idleState.totalTimeInState

        val totalBusyTime: Double
            get() = busyState.totalTimeInState

        val totalFailedTime: Double
            get() = failedState.totalTimeInState

        val totalInactiveTime: Double
            get() = inactiveState.totalTimeInState

        val totalCycleTime: Double
            get() = totalIdleTime + totalBusyTime + totalFailedTime + totalInactiveTime

        val fractionTimeIdle: Double
            get() {
                val tt = totalCycleTime
                if (tt == 0.0) {
                    return Double.NaN
                }
                return totalIdleTime / tt
            }

        val fractionTimeBusy: Double
            get() {
                val tt = totalCycleTime
                if (tt == 0.0) {
                    return Double.NaN
                }
                return totalBusyTime / tt
            }

        val fractionTimeInactive: Double
            get() {
                val tt = totalCycleTime
                if (tt == 0.0) {
                    return Double.NaN
                }
                return totalInactiveTime / tt
            }

        val fractionTimeFailed: Double
            get() {
                val tt = totalCycleTime
                if (tt == 0.0) {
                    return Double.NaN
                }
                return totalFailedTime / tt
            }

        /**
         *  Receives the task for processing
         */
        override fun receiveTask(task: Task, deadline: Double) {
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

        protected fun updateState(task: Task) {
            when (task.taskType) {
                BREAK -> {
                    currentState = inactiveState
                }

                FAILURE -> {
                    currentState = failedState
                }

                WORK -> {
                    currentState = busyState
                }
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