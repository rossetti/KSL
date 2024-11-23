package ksl.modeling.entity

import ksl.modeling.queue.Queue
import ksl.modeling.queue.QueueCIfc
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
        START_FAILURE, END_FAILURE, START_INACTIVE, END_INACTIVE, START_SHUTDOWN, SHUTDOWN
    }

    enum class TaskType { WORK, FAILURE, BREAK }

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
    fun shutdownAllTaskProcessors(timeUntilShutdown: Double = 0.0){
        require(timeUntilShutdown >= 0.0) { "The time until shutdown must be >= 0.0!" }
        for (taskProcessor in myTaskProcessors.values) {
            taskProcessor.scheduleShutDown(timeUntilShutdown)
        }
    }

    fun createTaskProcessors(number: Int, prefix: String = "Processor_"){
        val set = mutableSetOf<String>()
        for(i in 1..number){
            set.add(prefix+i)
        }
        createTaskProcessors(set)
    }

    fun createTaskProcessors(names: Set<String>){
        for (name in names){
            addTaskProcessor(TaskProcessor(name))
        }
    }

    fun addTaskProcessor(taskProcessor: TaskProcessor){
        require(!myTaskProcessors.containsKey(taskProcessor.name)) {"The task processor name (${taskProcessor.name}) already exists for ${this@TaskProcessingSystem.name}" }
        myTaskProcessors[taskProcessor.name] = taskProcessor
    }

    override fun afterReplication() {
        super.afterReplication()
        for (taskProcessor in myTaskProcessors.values) {
            //TODO afterReplication, need to initialize but not start them
        }
    }

    override fun warmUp() {
        super.warmUp()
        for (taskProcessor in myTaskProcessors.values) {
            taskProcessor.resetStates()
        }
    }

    //TODO statistics on task processors
    //TODO make it easier to create a set of task processors

    /**
     * Provides the ability to react to the completion of a task that was
     * sent for processing.
     */
    interface TaskSenderIfc {

        /**
         * Called when the task is completed
         */
        fun taskCompleted(task: Task)

        /**
         *  This function is called by task processors that are processing tasks sent by the sender.
         *  Subclasses can provide specific logic to react to the occurrence of the start of a failure,
         *  the end of a failure, start of an inactive period, end of an inactive period, and the warning
         *  of a shutdown and the shutdown. By default, no reaction occurs.
         *  @param taskInQ a task sent by the sender that is waiting in queue for the processor
         *  @param taskProcessor the task processor
         *  @param status the status indicator for the type of action
         */
        fun onTaskProcessorAction(taskInQ: Task, taskProcessor: TaskProcessor, status: TaskProcessorStatus) {}

    }

    /**
     *  Something that promises to receive tasks
     */
    interface TaskReceiverIfc {
        /**
         *  @param task the task the should be received
         *  @param deadline a valid time by which the task should be completed. Can be used to
         *  make decisions related to task processing
         */
        fun receiveTask(task: Task, deadline: Double = Double.POSITIVE_INFINITY)
    }

    interface TaskProviderIfc : Iterator<Task> {

        override fun hasNext() : Boolean

        override fun next(): Task

        /**
         * Called when the task is completed
         */
        fun taskCompleted(task: Task)

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
     *  @param taskSender the thing that wants the task completed
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

    //TODO consider name change to RepairTask
    inner class FailureTask(
        var downTime: GetValueIfc
    ) : Task(TaskType.FAILURE) {

        constructor(downTime: Double) : this(ConstantValue(downTime))

        override val taskProcess: KSLProcess = process {
            delay(downTime)
        }
    }

    inner class InactiveTask(
        var awayTime: GetValueIfc
    ) : Task(TaskType.FAILURE) {

        constructor(awayTime: Double) : this(ConstantValue(awayTime))

        override val taskProcess: KSLProcess = process {
            delay(awayTime)
        }
    }

    //TODO why not generalize out the queue to a TaskSelectorIfc because a queue is just one way to select tasks
    // need to think more carefully about startup
    open inner class TaskProcessor(
        aName: String? = null
    ) : Entity(aName) {

        //TODO consider a TaskProcessorIfc interface

        //TODO consider ability to shutdown the processor, types of shutdown (graceful, hard)
        // shutdown is permanent deactivation, would need to notify sender of tasks
        // consider the ability to allow sender to react to failure, react to inactive, react to shutdown
        // allow scheduling of shutdown, allow canceling of shutdown, assume shutdown can only occur
        // after current task is completed.
        // once shutdown occurs no new tasks can be received

        //TODO consider generalizing starting state

        private var myTaskProvider: TaskProviderIfc? = null

        /**
         *  Indicates if the processor is shutdown and will no longer process tasks.
         */
        var shutdown = false
            private set

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

        val myFailedState: State = State(name = "Failed")
        val failedState: StateAccessorIfc
            get() = myFailedState

        val myInactiveState: State = State(name = "Inactive")
        val inactiveState: StateAccessorIfc
            get() = myInactiveState

        private var myCurrentState: State = myIdleState
            private set(value) {
                field.exit(time) // exit the current state
                field = value // update the state
                field.enter(time) // enter the new state
            }
        val currentState: StateAccessorIfc
            get() = myCurrentState

        //TODO when and where to call
        internal fun initialize() {
            resetStates()
            myTaskProvider = null
            previousTask = null
            currentTask = null
            shutdown = false
            myCurrentState = myIdleState
            myIdleState.enter(time)
        }

//        fun startUp(){
//            initialize()
//            if (hasNextTask() && isIdle()) {
//                activate(taskProcessing)
//            }
//        }

        fun resetStates(){
            myIdleState.initialize()
            myBusyState.initialize()
            myFailedState.initialize()
            myInactiveState.initialize()
        }

        /**
         *  Describes how to process a task. If there are tasks, a new task
         *  is selected and then executed.
         */
        val taskProcessing = process("${this.name}_TaskProcessing") {
            while (hasNextTask() && !shutdown) {
                val nextTask = selectNextTask() ?: break
                nextTask.taskProcessor = this@TaskProcessor
                currentTask = nextTask
                // set the state based on the task type
                updateState(nextTask)
                beforeTaskExecution()//TODO is this necessary
                notifySendersOfStartAction(nextTask.taskType)
                nextTask.beforeTaskStart()
                waitFor(nextTask.taskProcess)
                nextTask.afterTaskCompleted()
                notifySendersOfEndAction(nextTask.taskType)
                afterTaskExecution() //TODO is this necessary
                myTaskProvider?.taskCompleted(nextTask)
                previousTask = nextTask
                currentTask = null
            }
            myCurrentState = myIdleState
        }

        fun isBusy(): Boolean {
            return myCurrentState === myBusyState
        }

        fun isFailed(): Boolean {
            return myCurrentState === myFailedState
        }

        fun isIdle(): Boolean {
            return myCurrentState === myIdleState
        }

        fun isInactive(): Boolean {
            return myCurrentState === myInactiveState
        }

        val numTimesFailed: Double
            get() = myFailedState.numberOfTimesEntered

        val numTimesInactive: Double
            get() = myInactiveState.numberOfTimesExited

        val numTimesIdle: Double
            get() = myIdleState.numberOfTimesEntered

        val numTimesBusy: Double
            get() = myBusyState.numberOfTimesEntered

        val totalIdleTime: Double
            get() = myIdleState.totalTimeInState

        val totalBusyTime: Double
            get() = myBusyState.totalTimeInState

        val totalFailedTime: Double
            get() = myFailedState.totalTimeInState

        val totalInactiveTime: Double
            get() = myInactiveState.totalTimeInState

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

        fun activateProcessor(taskProvider: TaskProviderIfc){
            require(!shutdown) {"${this.name} Task Processor: cannot be activated because it is shutdown!"}
            require(isIdle()) {"${this.name} Task Processor: cannot be activated because it is not idle!"}
            // must be idle thus it can be activated
            // if the incoming task provider is different from the current provider
            // then we can exchange it, otherwise it stays the same
            if (myTaskProvider != taskProvider){
                myTaskProvider = taskProvider
            }
            activate(taskProcessing)
        }

        private fun updateState(task: Task) {
            myCurrentState = when (task.taskType) {
                TaskType.BREAK -> {
                    myInactiveState
                }

                TaskType.FAILURE -> {
                    myFailedState
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
         *  Finds the next task to work on. Does not remove the task
         *  from the task queue. If null, then a new task could not be selected.
         */
        private fun selectNextTask(): Task? {
            //TODO consider what happens if separate queues/lists are used for failures and breaks
            // provide functional interface alternative for selecting
            return myTaskProvider?.next()
        }

        private fun notifySendersOfStartAction(taskType: TaskType) {
            var actionType: TaskProcessorStatus? = null
            if (taskType == TaskType.FAILURE) {
                actionType = TaskProcessorStatus.START_FAILURE
            }
            if (taskType == TaskType.BREAK) {
                actionType = TaskProcessorStatus.START_INACTIVE
            }
            if (actionType != null) {
                myTaskProvider?.onTaskProcessorAction(this, actionType)
            }
        }

        private fun notifySendersOfEndAction(taskType: TaskType) {
            var actionType: TaskProcessorStatus? = null
            if (taskType == TaskType.FAILURE) {
                actionType = TaskProcessorStatus.END_FAILURE
            }
            if (taskType == TaskType.BREAK) {
                actionType = TaskProcessorStatus.END_INACTIVE
            }
            if (actionType != null) {
                myTaskProvider?.onTaskProcessorAction(this, actionType)
            }
        }

        private var myShutDownEvent: KSLEvent<Nothing>? = null
        val isShutdownPending: Boolean
            get() = myShutDownEvent != null
        val timeUntilShutdown: Double
            get() = if (myShutDownEvent != null) {
                myShutDownEvent!!.interEventTime
            } else {
                Double.POSITIVE_INFINITY
            }

        fun scheduleShutDown(timeUntilShutdown: Double = 0.0) {
            require(timeUntilShutdown >= 0.0) { "The time until shutdown must be >= 0.0!" }
            myShutDownEvent = schedule(this@TaskProcessor::shutDownAction, timeUntilShutdown)
            // notify the provider of tasks of pending shutdown
            myTaskProvider?.onTaskProcessorAction(this, TaskProcessorStatus.START_SHUTDOWN)
            val s = if (myShutDownEvent != null) myShutDownEvent!!.interEventTime else Double.POSITIVE_INFINITY
        }

        private fun shutDownAction(event: KSLEvent<Nothing>){
            //TODO need to decide what to do if the task processor is currently working on a task
            // default: current task should be allowed to complete before shutdown actually occurs??
            // TODO need shutdownPending flag
            if (!shutdown) {
                shutdown = true
                // notify the provider of tasks of pending shutdown
                myTaskProvider?.onTaskProcessorAction(this, TaskProcessorStatus.START_SHUTDOWN)
                myShutDownEvent = null
            }
        }

        private fun shutdown(){
            //TODO call from event
            if (!shutdown) {
                shutdown = true
                // notify the provider of tasks of shutdown
                myTaskProvider?.onTaskProcessorAction(this, TaskProcessorStatus.SHUTDOWN)
                myShutDownEvent = null
            }
        }
    }
}