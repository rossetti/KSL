package ksl.modeling.entity

import ksl.modeling.queue.Queue
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.ConstantValue
import ksl.utilities.GetValueIfc
import ksl.utilities.statistic.State
import ksl.utilities.statistic.StateAccessorIfc


open class TaskProcessingSystem(
    parent: ModelElement,
    name: String? = null
) : ProcessModel(parent, name) {

    enum class TaskProcessorStatus {
        START_WORK, END_WORK, START_FAILURE, END_FAILURE, START_INACTIVE, END_INACTIVE, START_SHUTDOWN, SHUTDOWN, CANCEL_SHUTDOWN
    }

    enum class TaskType { WORK, REPAIR, BREAK }

    private val myTaskProcessors = mutableMapOf<String, TaskProcessor>()
    val taskProcessors: Map<String, TaskProcessor>
        get() = myTaskProcessors

    /**
     *  Causes all the task processors in the system to shut down based on
     *  the supplied time until shutdown. The shutdowns may not occur exactly
     *  at the desired time due to the fact that a task processor may be performing
     *  a task. The task is allowed to complete before the shutdown occurs.
     *  @param timeUntilShutdown a desired time until shutdown, must be greater than or equal to 0.0
     */
    fun shutdownAllTaskProcessors(timeUntilShutdown: Double = 0.0) {
        require(timeUntilShutdown >= 0.0) { "The time until shutdown must be >= 0.0!" }
        for (taskProcessor in myTaskProcessors.values) {
            taskProcessor.scheduleShutDown(timeUntilShutdown)
        }
    }

    fun createTaskProcessors(number: Int, prefix: String = "Processor_") {
        val set = mutableSetOf<String>()
        for (i in 1..number) {
            set.add(prefix + i)
        }
        createTaskProcessors(set)
    }

    fun createTaskProcessors(names: Set<String>, allPerformance: Boolean = false) {
        for (name in names) {
            addTaskProcessor(TaskProcessor(this, allPerformance, name))
        }
    }

    fun addTaskProcessor(taskProcessor: TaskProcessor) {
        require(!myTaskProcessors.containsKey(taskProcessor.name)) { "The task processor name (${taskProcessor.name}) already exists for ${this@TaskProcessingSystem.name}" }
        myTaskProcessors[taskProcessor.name] = taskProcessor
    }

    inner class QueueBasedTaskProvider(
        val taskProcessor: TaskProcessor,
        val queue: Queue<Task>
    ) : TaskProviderIfc {
        override fun hasNext(): Boolean {
            return queue.peekNext() != null
        }

        override fun next(): Task? {
            val task = queue.removeNext()
            task?.taskProvider = this
            return task
        }

        fun enqueue(task: Task) {
            queue.enqueue(task)
            if (taskProcessor.isIdle() && !taskProcessor.shutdown) {
                taskProcessor.activateProcessor(this)
            }
        }
    }

    fun interface TaskCompletedIfc {
        fun taskCompleted(task: Task)
    }

    fun interface TaskProcessorActionIfc  {
        fun onTaskProcessorAction(taskProcessor: TaskProcessor, status: TaskProcessorStatus)
    }

    interface TaskProviderIfc {

        /**
         *  Checks if the provider has another task
         */
        fun hasNext(): Boolean

        /**
         *  Provides the task. Implementors should set the provider of the task
         *  before supplying the task.
         */
        fun next(): Task?

        /**
         * Called when the task is completed
         */
        fun taskCompleted(task: Task) {}

        /**
         *  This function is called by task processors that are processing tasks sent by the provider.
         *  Subclasses can provide specific logic to react to the occurrence of the start of a failure,
         *  the end of a failure, start of an inactive period, end of an inactive period, and the warning
         *  of a shutdown and the shutdown. By default, no reaction occurs.
         *  @param taskProcessor the task processor
         *  @param status the status indicator for the type of action
         */
        fun onTaskProcessorAction(taskProcessor: TaskProcessor, status: TaskProcessorStatus) {}

    }

    /**
     *  Represents something that must be executed by a TaskProcessor.
     *  @param taskType the type of task
     */
    abstract inner class Task(
        val taskType: TaskType = TaskType.WORK
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
         *  The processor that executed the task's process
         */
        var taskProcessor: TaskProcessor? = null

        /**
         *  The provider that supplied the task for processing
         */
        var taskProvider: TaskProviderIfc? = null

        /**
         * The deadline may be used by the task processor to assist with task selection
         */
        var deadline: Double = Double.POSITIVE_INFINITY
            set(value) {
                require((value > 0.0) || (value.isInfinite())) { "The deadline must be > 0.0 or infinite" }
                field = value
            }

        /**
         *  The process routine defined for the task.
         */
        abstract val taskProcess: KSLProcess

        /**
         *  Called by the processor immediately before starting the task
         */
        open fun beforeTaskStart() {}

        /**
         *  Called by the processor immediately after the task completes and before the sender
         *  is notified of completion
         */
        open fun afterTaskCompleted() {}

    }

    inner class WorkTask(
        var workTime: GetValueIfc
    ) : Task(TaskType.WORK) {

        constructor(workTime: Double) : this(ConstantValue(workTime))

        override val taskProcess: KSLProcess = process {
            delay(workTime)
        }
    }

    inner class RepairTask(
        var downTime: GetValueIfc
    ) : Task(TaskType.REPAIR) {

        constructor(downTime: Double) : this(ConstantValue(downTime))

        override val taskProcess: KSLProcess = process {
            delay(downTime)
        }
    }

    inner class InactiveTask(
        var awayTime: GetValueIfc
    ) : Task(TaskType.BREAK) {

        constructor(awayTime: Double) : this(ConstantValue(awayTime))

        override val taskProcess: KSLProcess = process {
            delay(awayTime)
        }
    }

    /**
     *
     */
    open inner class TaskProcessor(
        val taskProcessingSystem: TaskProcessingSystem,
        var allPerformance: Boolean = false,
        name: String? = null
    ) : ModelElement(taskProcessingSystem, name) {

        private val myFractionTimeBusy = Response(this, name = "${this.name}:FractionTimeBusy")
        val fractionTimeBusyResponse: ResponseCIfc
            get() = myFractionTimeBusy
        private val myNumTimesBusy = Response(this, name = "${this.name}:NumTimesBusy")
        val numTimesBusyResponse: ResponseCIfc
            get() = myNumTimesBusy
        private val myFractionIdleTime by lazy { Response(this, name = "${this.name}:FractionTimeIdle") }
        val fractionTimeIdleResponse: ResponseCIfc
            get() = myFractionIdleTime
        private val myNumTimesIdle by lazy { Response(this, name = "${this.name}:NumTimesIdle") }
        val numTimesIdleResponse: ResponseCIfc
            get() = myNumTimesIdle
        private val myFractionInRepairTime by lazy { Response(this, name = "${this.name}:FractionTimeInRepair") }
        val fractionTimeInRepairResponse: ResponseCIfc
            get() = myFractionInRepairTime
        private val myNumTimesRepaired by lazy { Response(this, name = "${this.name}:NumTimesRepaired") }
        val numTimesRepairedResponse: ResponseCIfc
            get() = myNumTimesRepaired
        private val myFractionInactiveTime by lazy { Response(this, name = "${this.name}:FractionTimeInactive") }
        val fractionTimeInactiveResponse: ResponseCIfc
            get() = myFractionInactiveTime
        private val myNumTimesInactive by lazy { Response(this, name = "${this.name}:NumTimesInactive") }
        val numTimesInactiveResponse: ResponseCIfc
            get() = myNumTimesInactive

        init {
            if (allPerformance) {
                // cause the lazy variables to be created when the model element is created
                myFractionIdleTime.id
                myNumTimesIdle.id
                myFractionInRepairTime.id
                myNumTimesRepaired.id
                myFractionInactiveTime.id
                myNumTimesInactive.id
            }
        }

        private var myTaskProvider: TaskProviderIfc? = null
        private var myProcessor: Processor? = null

        /**
         *  The task that the processor is currently executing
         */
        var currentTask: Task? = null
            private set

        /**
         *  The task that was previously executed by the processor
         */
        var previousTask: Task? = null
            private set

        /**
         *  Used to indicate that the processor is idle (not processing any tasks)
         *  and to tabulate time spent in the state.
         */
        private val myIdleState: State = State(name = "Idle")
        val idleState: StateAccessorIfc
            get() = myIdleState

        val myBusyState: State = State(name = "Busy")
        val busyState: StateAccessorIfc
            get() = myBusyState

        val myInRepairState: State = State(name = "InRepair")
        val inRepairState: StateAccessorIfc
            get() = myInRepairState

        val myInactiveState: State = State(name = "Inactive")
        val inactiveState: StateAccessorIfc
            get() = myInactiveState

        private var myCurrentState: State = myIdleState
        val currentState: StateAccessorIfc
            get() = myCurrentState

        private fun changeState(nextState: State) {
            myCurrentState.exit(time) // exit the current state
            nextState.enter(time) // enter the new state
            myCurrentState = nextState // update the state
        }

        /**
         *  Indicates if the processor is shutdown and will no longer process tasks.
         */
        var shutdown = false
            private set
        private var myShutDownEvent: KSLEvent<Nothing>? = null
        val isShutdownPending: Boolean
            get() = myShutDownEvent != null
        val timeUntilShutdown: Double
            get() = if (myShutDownEvent != null) {
                myShutDownEvent!!.interEventTime
            } else {
                Double.POSITIVE_INFINITY
            }
        val timeOfShutDown: Double
            get() = if (myShutDownEvent != null) {
                myShutDownEvent!!.time
            } else {
                Double.POSITIVE_INFINITY
            }

        override fun initialize() {
            super.initialize()
            resetStates()
            myTaskProvider = null
            myProcessor = null
            previousTask = null
            currentTask = null
            shutdown = false
            myCurrentState = myIdleState
            myIdleState.enter(time)
        }

        override fun replicationEnded() {
            super.replicationEnded()
            myFractionTimeBusy.value = fractionTimeBusy
            myNumTimesBusy.value = numTimesBusy
            if (allPerformance) {
                myFractionIdleTime.value = fractionTimeIdle
                myNumTimesIdle.value = numTimesIdle
                myFractionInRepairTime.value = fractionTimeFailed
                myNumTimesRepaired.value = numTimesRepaired
                myFractionInactiveTime.value = fractionTimeInactive
                myNumTimesInactive.value = numTimesInactive
            }
        }

        override fun warmUp() {
            super.warmUp()
            resetStates()
        }

        fun resetStates() {
            myIdleState.initialize()
            myBusyState.initialize()
            myInRepairState.initialize()
            myInactiveState.initialize()
            //need to re-enter the current state after resetting states
            myCurrentState.enter(time)
        }

        fun isBusy(): Boolean {
            return myCurrentState === myBusyState
        }

        fun isFailed(): Boolean {
            return myCurrentState === myInRepairState
        }

        fun isIdle(): Boolean {
            return myCurrentState === myIdleState
        }

        fun isInactive(): Boolean {
            return myCurrentState === myInactiveState
        }

        val numTimesRepaired: Double
            get() = myInRepairState.numberOfTimesExited

        val numTimesInactive: Double
            get() = myInactiveState.numberOfTimesExited

        val numTimesIdle: Double
            get() = myIdleState.numberOfTimesExited

        val numTimesBusy: Double
            get() = myBusyState.numberOfTimesExited

        val totalIdleTime: Double
            get() {
                val st = if (isIdle()) time - myIdleState.timeStateEntered else 0.0
                return myIdleState.totalTimeInState + st
            }

        val totalBusyTime: Double
            get() {
                val st = if (isBusy()) time - myBusyState.timeStateEntered else 0.0
                return myBusyState.totalTimeInState + st
            }

        val totalFailedTime: Double
            get() {
                val st = if (isFailed()) time - myInRepairState.timeStateEntered else 0.0
                return myInRepairState.totalTimeInState + st
            }

        val totalInactiveTime: Double
            get() {
                val st = if (isInactive()) time - myInactiveState.timeStateEntered else 0.0
                return myInactiveState.totalTimeInState + st
            }

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
         *  Causes the task processor to be activated and to start processing tasks from the supplied
         *  task provider. The task provider must not be shutdown and must be idle in order to be
         *  activated. The task processor will continue to execute tasks from the provider as long
         *  as the provider can supply them.
         *
         * @param taskProvider the task provider from which tasks will be pulled after activation
         */
        fun activateProcessor(taskProvider: TaskProviderIfc) {
            require(!shutdown) { "${this.name} Task Processor: cannot be activated because it is shutdown!" }
            require(isIdle()) { "${this.name} Task Processor: cannot be activated because it is not idle!" }
            // must be idle thus it can be activated
            // if the incoming task provider is different from the current provider
            // then we can exchange it, otherwise it stays the same
            if (myTaskProvider != taskProvider) {
                myTaskProvider = taskProvider
            }
            myProcessor = Processor("Processor_${this.name}")
            activate(myProcessor!!.taskProcessing)
        }

        /**
         *  Causes a shutdown event to be scheduled for the supplied time. The shutdown event is scheduled
         *  and the current task provider is notified of the pending shutdown. This allows the
         *  current task provider to react gracefully to the pending shutdown.  If there is no task provider
         *  then no notification occurs.  There is no task provider if the task processor has not been
         *  activated.
         *
         * @param timeUntilShutdown The time until the commencement of the shutdown. The default is 0 (now).
         */
        fun scheduleShutDown(timeUntilShutdown: Double = 0.0) {
            require(timeUntilShutdown >= 0.0) { "The time until shutdown must be >= 0.0!" }
            myShutDownEvent = schedule(this@TaskProcessor::shutDownAction, timeUntilShutdown)
            // notify the provider of tasks of pending shutdown
            myTaskProvider?.onTaskProcessorAction(this, TaskProcessorStatus.START_SHUTDOWN)
        }

        /**
         *  Causes a pending shutdown event to be cancelled. If there is a task provider associated with
         *  the task processor it will be notified of the cancellation.
         */
        fun cancelShutDown() {
            myShutDownEvent?.cancel = true
            myShutDownEvent = null
            myTaskProvider?.onTaskProcessorAction(this, TaskProcessorStatus.CANCEL_SHUTDOWN)
        }

        private fun nextState(task: Task): State {
            return when (task.taskType) {
                TaskType.BREAK -> {
                    myInactiveState
                }

                TaskType.REPAIR -> {
                    myInRepairState
                }

                TaskType.WORK -> {
                    myBusyState
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
        private fun hasNextTask(): Boolean {
            return myTaskProvider?.hasNext() ?: false
        }

        /**
         *  Finds the next task to work on.  If null, then a new task could not be selected.
         */
        private fun nextTask(): Task? {
            return myTaskProvider?.next()
        }

        private fun notifyProviderOfStartAction(taskType: TaskType) {

            val actionType = when (taskType) {
                TaskType.BREAK -> {
                    TaskProcessorStatus.START_INACTIVE
                }

                TaskType.REPAIR -> {
                    TaskProcessorStatus.START_FAILURE
                }

                TaskType.WORK -> {
                    TaskProcessorStatus.START_WORK
                }
            }

            myTaskProvider?.onTaskProcessorAction(this, actionType)
        }

        private fun notifyProviderOfEndAction(taskType: TaskType) {
            val actionType = when (taskType) {
                TaskType.BREAK -> {
                    TaskProcessorStatus.END_INACTIVE
                }

                TaskType.REPAIR -> {
                    TaskProcessorStatus.END_FAILURE
                }

                TaskType.WORK -> {
                    TaskProcessorStatus.END_WORK
                }
            }
            myTaskProvider?.onTaskProcessorAction(this, actionType)

        }

        private fun shutDownAction(event: KSLEvent<Nothing>) {
            // if the current task is not null we can't immediately start the shutdown
            // because the processor is executing a task;
            // however, if the current task is null then the processor is not executing a task
            // and can be shutdown (immediately).
            if (currentTask == null) {
                shutdown()
            }
        }

        private fun shutdown() {
            if (!shutdown) {
                shutdown = true
                // notify the provider of tasks of shutdown
                myTaskProvider?.onTaskProcessorAction(this, TaskProcessorStatus.SHUTDOWN)
                myShutDownEvent = null
            }
        }

        private inner class Processor(name: String?) : Entity(name) {

            /**
             *  Describes how to process a task. If there are tasks, a new task
             *  is selected and then executed.
             */
            val taskProcessing = process("${this.name}_TaskProcessing") {
                while (hasNextTask() && !shutdown) {
                    val nextTask = nextTask() ?: break
                    nextTask.taskProcessor = this@TaskProcessor
                    currentTask = nextTask
                    // set the state based on the task type
                    val nextState = nextState(nextTask)
                    changeState(nextState)
                    beforeTaskExecution()
                    notifyProviderOfStartAction(nextTask.taskType)
                    nextTask.beforeTaskStart()
                    waitFor(nextTask.taskProcess)
                    nextTask.afterTaskCompleted()
                    notifyProviderOfEndAction(nextTask.taskType)
                    afterTaskExecution()
                    myTaskProvider?.taskCompleted(nextTask)
                    previousTask = nextTask
                    currentTask = null
                    // need to catch shutdown that might have occurred during task execution
                    if (timeOfShutDown <= time) {
                        shutdown()
                    }
                }
                changeState(myIdleState)
                myProcessor = null
            }
        }
    }
}