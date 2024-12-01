package ksl.modeling.entity

import ksl.modeling.queue.Queue
import ksl.modeling.queue.Queue.Discipline
import ksl.modeling.queue.QueueCIfc
import ksl.modeling.queue.QueueIfc
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.ConstantValue
import ksl.utilities.GetValueIfc
import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
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

    private val myTaskProcessors = mutableMapOf<String, TransientTaskProcessor>()
    val taskProcessors: Map<String, TransientTaskProcessor>
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

//    fun createTaskProcessors(number: Int, prefix: String = "Processor_") {
//        val set = mutableSetOf<String>()
//        for (i in 1..number) {
//            set.add(prefix + i)
//        }
//        createTaskProcessors(set)
//    }
//
//    fun createTaskProcessors(names: Set<String>, allPerformance: Boolean = false) {
//        for (name in names) {
//            addTaskProcessor(TaskProcessor(this, allPerformance, name))
//        }
//    }
//
//    fun addTaskProcessor(taskProcessor: TaskProcessor) {
//        require(!myTaskProcessors.containsKey(taskProcessor.name)) { "The task processor name (${taskProcessor.name}) already exists for ${this@TaskProcessingSystem.name}" }
//        myTaskProcessors[taskProcessor.name] = taskProcessor
//    }

    //TODO work on public interface, hiding methods
    open inner class TaskDispatcher(
        parent: ModelElement,
        name: String? = null,
        discipline: Discipline = Discipline.FIFO
    ) : ModelElement(parent, name) {

        protected val myProcessors = mutableListOf<TaskProcessorIfc>()
        protected val myTaskQueue: Queue<Task> = Queue(this, name = "${this.name}:TaskQ", discipline)
        val queue: QueueCIfc<Task>
            get() = myTaskQueue

        /**
         *  Receives tasks and attempts to dispatch them. Selects a processor
         *  via the selectProcessor() function and then dispatches the task
         *  to the processor with the dispatch() function.
         *
         * @param task The task that needs dispatching
         */
        fun receive(task: Task) {
            myTaskQueue.enqueue(task)
            val processor = selectProcessor()
            if (processor != null) {
                dispatch(processor)
            }
        }

        /**
         *  Called to cause a task to be sent to the supplied processor.
         *  If the dispatcher has a task for dispatching the task is
         *  selected via the nextTask() function and then the processor
         *  is told to receive the task for processing.
         *
         *  @param processor the processor to attempt sending a task to
         */
        protected open fun dispatch(processor: TaskProcessorIfc) {
            if (hasTask()) {
                val nextTask = nextTask()
                if (nextTask != null) {
                    nextTask.taskDispatcher = this
                    processor.receive(nextTask)
                }
            }
        }

        /**
         *  @return true if there is a task ready for dispatching
         */
        fun hasTask(): Boolean {
            return myTaskQueue.peekNext() != null
        }

        /**
         *  @return the task that should be dispatched or null if no task is ready
         */
        protected open fun nextTask(): Task? {
            return myTaskQueue.removeNext()
        }

        /** Registers the processor to receive tasks that are dispatched by
         * the dispatcher.
         *
         * @param taskProcessor the processor that should be registered
         */
        fun register(taskProcessor: TaskProcessorIfc) {
            require(!myProcessors.contains(taskProcessor)) { "The task processor, ${taskProcessor}, is already registered with dispatcher, $name" }
            myProcessors.add(taskProcessor)
            println("registered the task processor $taskProcessor with dispatcher, ${this.name}")
        }

        /**
         *  Unregisters the processor so that it no longer receives tasks from the dispatcher.
         *
         *  @param taskProcessor the processor that should be unregistered
         */
        fun unregister(taskProcessor: TaskProcessorIfc): Boolean {
            return myProcessors.remove(taskProcessor)
        }

        /**
         *  Selects a processor to receive that next task to be dispatched.
         *  The default is to select the first processor that is idle and is not shutdown.
         *  Idle implies that the processor is not failed (in-repair), busy, or inactive.
         *
         * @return the processor that should next receive tasks
         */
        protected open fun selectProcessor(): TaskProcessorIfc? {
            return myProcessors.firstOrNull { it.isIdle() && !it.isShutDown() }
        }

        /**
         *  Called when a formally dispatched task is completed.
         *  @param task the completed task
         */
        protected open fun taskCompleted(task: Task) {}

        /**
         * Called (internally) by the processor when a formally dispatched task is completed.
         * @param processor the processor that completed the task
         * @param task the task that was completed.
         */
        internal fun dispatchCompleted(processor: TaskProcessorIfc, task: Task) {
//            require(myProcessors.contains(processor)) {"The processor $processor is not associated with dispatcher, ${this.name}"}
            //TODO it is not being found because the processor is the delegate not the original
            dispatch(processor)
//            if (myProcessors.contains(processor)){
//                dispatch(processor)
//            }
            taskCompleted(task)
        }

        /**
         *  This function is called by task processors that are processing tasks sent by the provider.
         *  Subclasses can provide specific logic to react to the occurrence of the start of a failure,
         *  the end of a failure, start of an inactive period, end of an inactive period, and the warning
         *  of a shutdown and the shutdown. By default, no reaction occurs.
         *  @param taskProcessor the task processor
         *  @param status the status indicator for the type of action
         */
        fun onTaskProcessorAction(taskProcessor: TransientTaskProcessor, status: TaskProcessorStatus) {}
        //TODO need separate functions by status, protected
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
        var taskProcessor: TransientTaskProcessor? = null

        /**
         *  The provider that supplied the task for processing
         */
        var taskDispatcher: TaskDispatcher? = null

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

        //TODO need to consider functional actions

        /**
         *  This function is called when the task's associated processor is starting a processor action.
         *  The task is only notified if it is waiting for the processor and not yet executing.
         *  Subclasses can provide specific logic to react to the occurrence of a processor
         *  start action (START_WORK, START_FAILURE, START_INACTIVE, START_SHUTDOWN).
         *  @param status the status indicator for the type of action
         */
        open fun onTaskProcessorStartAction(status: TaskProcessorStatus) {}

        /**
         *  This function is called when the task's associated processor is starting a processor action.
         *  The task is only notified if it is waiting for the processor and not yet executing.
         *  Subclasses can provide specific logic to react to the occurrence of a processor
         *  start action (END_WORK, END_FAILURE, END_INACTIVE, SHUTDOWN, CANCEL_SHUTDOWN).
         *  @param status the status indicator for the type of action
         */
        open fun onTaskProcessorEndAction(status: TaskProcessorStatus) {}
    }

    /**
     *  Simple delay task to represent work being done.
     *
     *  @param workTime the time to do the work
     */
    inner class WorkTask(
        var workTime: GetValueIfc
    ) : Task(TaskType.WORK) {

        constructor(workTime: Double) : this(ConstantValue(workTime))

        override val taskProcess: KSLProcess = process {
            delay(workTime)
        }
    }

    /**
     *  Simple delay task to represent repair being done.
     *  @param repairTime the time down to complete the repair
     */
    inner class RepairTask(
        var repairTime: GetValueIfc
    ) : Task(TaskType.REPAIR) {

        constructor(repairTime: Double) : this(ConstantValue(repairTime))

        override val taskProcess: KSLProcess = process {
            delay(repairTime)
        }
    }

    /**
     *  Simple delay to represent a break period for a processor.
     *  @param awayTime the time away for the break.
     */
    inner class InactiveTask(
        var awayTime: GetValueIfc
    ) : Task(TaskType.BREAK) {

        constructor(awayTime: Double) : this(ConstantValue(awayTime))

        override val taskProcess: KSLProcess = process {
            delay(awayTime)
        }
    }

    //TODO extract performance out to its own class

    //TODO need to add some queue functionality
    interface TaskProcessorIfc {
        val taskProcessingSystem: TaskProcessingSystem //TODO??

        /**
         *  Allows access to accumulated state (idle) usage.
         */
        val idleState: StateAccessorIfc
        /**
         *  Allows access to accumulated state (busy) usage.
         */
        val busyState: StateAccessorIfc
        /**
         *  Allows access to accumulated state (in-repair) usage.
         */
        val inRepairState: StateAccessorIfc
        /**
         *  Allows access to accumulated state (inactive) usage.
         */
        val inactiveState: StateAccessorIfc
        /**
         *  Allows access to accumulated state (current) usage.
         */
        val currentState: StateAccessorIfc

        /**
         *  Indicates if a shutdown has been scheduled.
         */
        val isShutdownPending: Boolean

        /**
         * The time until a shutdown or infinity.
         */
        val timeUntilShutdown: Double

        /**
         *  The time of the shutdown or infinity.
         */
        val timeOfShutDown: Double

        /**
         *  The number of times the processor has completed repair.
         */
        val numTimesRepaired: Double

        /**
         * The number of times that the processor has completed inactive periods.
         */
        val numTimesInactive: Double

        /**
         *  The number of times that the processor has completed an idle period.
         */
        val numTimesIdle: Double

        /**
         *  The number of times that the processor has exited the busy state.
         */
        val numTimesBusy: Double

        /**
         *  The total time up to the current time that the processor has been idle.
         */
        val totalIdleTime: Double
        /**
         *  The total time up to the current time that the processor has been busy.
         */
        val totalBusyTime: Double
        /**
         *  The total time up to the current time that the processor has been in the repaired state.
         */
        val totalInRepairTime: Double
        /**
         *  The total time up to the current time that the processor has been in the inactive state.
         */
        val totalInactiveTime: Double
        /**
         *  The total time up to the current time that the processor has been in any state.
         */
        val totalCycleTime: Double

        /**
         *  The fraction of time up to the current time that the processor has been in the idle state.
         */
        val fractionTimeIdle: Double

        /**
         *  The fraction of time up to the current time that the processor has been in the busy state.
         */
        val fractionTimeBusy: Double

        /**
         *  The fraction of time up to the current time that the processor has been in the active state.
         */
        val fractionTimeInactive: Double

        /**
         *  The fraction of time up to the current time that the processor has been in the in-repair state.
         */
        val fractionTimeInRepair: Double

        /**
         *  Causes the accumulated state information to be reset.
         */
        fun resetStates()

        /**
         *  Indicates if the processor is in the busy state.
         */
        fun isBusy(): Boolean

        /**
         *  Indicates if the processor is in the in-repair state.
         */
        fun isInRepair(): Boolean

        /**
         *  Indicates if the processor is in the idle state.
         */
        fun isIdle(): Boolean

        /**
         *  Indicates if the processor is in the inactive state.
         */
        fun isInactive(): Boolean

        /**
         *  Indicates if the processor has been shutdown.
         */
        fun isShutDown(): Boolean

        /**
         *  Indicates if the processor has not been shutdown.
         */
        fun isNotShutDown(): Boolean {
            return !isShutDown()
        }

        /**
         *  Receives the task for processing. Enqueues the task and if the
         *  processor is idle, activates the processor to process tasks.
         *
         *  @param task The task that needs executing.
         */
        fun receive(task: Task)

        /**
         *  Causes a shutdown event to be scheduled for the supplied time. The shutdown event is scheduled
         *  and the current task provider is notified of the pending shutdown. This allows the
         *  current task provider to react gracefully to the pending shutdown.  If there is no task provider
         *  then no notification occurs.  There is no task provider if the task processor has not been
         *  activated.
         *
         * @param timeUntilShutdown The time until the commencement of the shutdown. The default is 0 (now).
         */
        fun scheduleShutDown(timeUntilShutdown: Double = 0.0)

        /**
         *  Causes a pending shutdown event to be cancelled. If there is a task provider associated with
         *  the task processor it will be notified of the cancellation.
         */
        fun cancelShutDown()

        /**
         *  Indicates true if selectNextTask() results in a non-null task
         */
        fun hasNextTask(): Boolean
    }

    /**
     *  A task queue can hold tasks for a processor. This class
     *  does not collect queueing statistics.
     */
    inner class TaskQueue : QueueIfc<Task> {

        private val myTaskList = mutableListOf<Task>()

        override val size: Int
            get() = myTaskList.size
        override val isEmpty: Boolean
            get() = myTaskList.isEmpty()
        override val isNotEmpty: Boolean
            get() = myTaskList.isNotEmpty()

        override fun peekNext(): Task? {
            if (isNotEmpty) {
                return myTaskList.first()
            } else {
                return null
            }
        }

        override fun removeNext(): Task? {
            val next = peekNext()
            if (next != null) {
                myTaskList.remove(next)
                return next
            } else {
                return null
            }
        }

        override fun clear() {
            myTaskList.clear()
        }

        override fun iterator(): Iterator<Task> {
            return myTaskList.iterator()
        }

        override fun contains(qObj: Task): Boolean {
            return myTaskList.contains(qObj)
        }

        override fun enqueue(qObject: Task) {
            require(!contains(qObject)) { "The task, $qObject is already in the queue" }
            myTaskList.add(qObject)
        }

    }

    //TODO extract out performance, supply it (or not)
    // don't use delegation, consider sub-classing from TaskProcessor
    open inner class TaskProcessorME(
        parent: ModelElement,
        private val allPerformance: Boolean = false,
        name: String? = null,
        private val taskProcessor: TransientTaskProcessor = TransientTaskProcessor(name)
    ) : ModelElement(parent, name), TaskProcessorIfc by taskProcessor {

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

        override fun initialize() {
            super.initialize()
            taskProcessor.setup()
        }

        override fun replicationEnded() {
            super.replicationEnded()
            myFractionTimeBusy.value = taskProcessor.fractionTimeBusy
            myNumTimesBusy.value = taskProcessor.numTimesBusy
            if (allPerformance) {
                myFractionIdleTime.value = taskProcessor.fractionTimeIdle
                myNumTimesIdle.value = taskProcessor.numTimesIdle
                myFractionInRepairTime.value = taskProcessor.fractionTimeInRepair
                myNumTimesRepaired.value = taskProcessor.numTimesRepaired
                myFractionInactiveTime.value = taskProcessor.fractionTimeInactive
                myNumTimesInactive.value = taskProcessor.numTimesInactive
            }
        }

        override fun warmUp() {
            super.warmUp()
            taskProcessor.resetStates()
        }

    }

    /**
     * Responsible for executing tasks that have been supplied.
     */
    //TODO rename TransientTaskProcessor
    // parameters to indicate when to end, when to remove/shutdown
    open inner class TransientTaskProcessor(
        name: String? = null,
        private val taskQueue: QueueIfc<Task> = TaskQueue()
    ) : TaskProcessorIfc, IdentityIfc by Identity(name) {

        //TODO why is this needed
        override val taskProcessingSystem: TaskProcessingSystem = this@TaskProcessingSystem

        /**
         *  The internal entity used to run processes for the processor.
         */
        private var myProcessingEntity: ProcessingEntity? = null

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
        override val idleState: StateAccessorIfc
            get() = myIdleState

        val myBusyState: State = State(name = "Busy")
        override val busyState: StateAccessorIfc
            get() = myBusyState

        val myInRepairState: State = State(name = "InRepair")
        override val inRepairState: StateAccessorIfc
            get() = myInRepairState

        val myInactiveState: State = State(name = "Inactive")
        override val inactiveState: StateAccessorIfc
            get() = myInactiveState

        private var myCurrentState: State = myIdleState
        override val currentState: StateAccessorIfc
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

        final override fun isShutDown(): Boolean {
            return shutdown
        }

        private var myShutDownEvent: KSLEvent<Nothing>? = null
        override val isShutdownPending: Boolean
            get() = myShutDownEvent != null
        override val timeUntilShutdown: Double
            get() = if (myShutDownEvent != null) {
                myShutDownEvent!!.interEventTime
            } else {
                Double.POSITIVE_INFINITY
            }
        override val timeOfShutDown: Double
            get() = if (myShutDownEvent != null) {
                myShutDownEvent!!.time
            } else {
                Double.POSITIVE_INFINITY
            }

        fun setup() {
            resetStates()
            taskQueue.clear()
            myProcessingEntity = null
            previousTask = null
            currentTask = null
            shutdown = false
            myCurrentState = myIdleState
            myIdleState.enter(time)
        }

        override fun resetStates() {
            myIdleState.initialize()
            myBusyState.initialize()
            myInRepairState.initialize()
            myInactiveState.initialize()
            //need to re-enter the current state after resetting states
            myCurrentState.enter(time)
        }

        override fun isBusy(): Boolean {
            return myCurrentState === myBusyState
        }

        override fun isInRepair(): Boolean {
            return myCurrentState === myInRepairState
        }

        override fun isIdle(): Boolean {
            return myCurrentState === myIdleState
        }

        override fun isInactive(): Boolean {
            return myCurrentState === myInactiveState
        }

        override val numTimesRepaired: Double
            get() = myInRepairState.numberOfTimesExited

        override val numTimesInactive: Double
            get() = myInactiveState.numberOfTimesExited

        override val numTimesIdle: Double
            get() = myIdleState.numberOfTimesExited

        override val numTimesBusy: Double
            get() = myBusyState.numberOfTimesExited

        override val totalIdleTime: Double
            get() {
                val st = if (isIdle()) time - myIdleState.timeStateEntered else 0.0
                return myIdleState.totalTimeInState + st
            }

        override val totalBusyTime: Double
            get() {
                val st = if (isBusy()) time - myBusyState.timeStateEntered else 0.0
                return myBusyState.totalTimeInState + st
            }

        override val totalInRepairTime: Double
            get() {
                val st = if (isInRepair()) time - myInRepairState.timeStateEntered else 0.0
                return myInRepairState.totalTimeInState + st
            }

        override val totalInactiveTime: Double
            get() {
                val st = if (isInactive()) time - myInactiveState.timeStateEntered else 0.0
                return myInactiveState.totalTimeInState + st
            }

        override val totalCycleTime: Double
            get() = totalIdleTime + totalBusyTime + totalInRepairTime + totalInactiveTime

        override val fractionTimeIdle: Double
            get() {
                val tt = totalCycleTime
                if (tt == 0.0) {
                    return Double.NaN
                }
                return totalIdleTime / tt
            }

        override val fractionTimeBusy: Double
            get() {
                val tt = totalCycleTime
                if (tt == 0.0) {
                    return Double.NaN
                }
                return totalBusyTime / tt
            }

        override val fractionTimeInactive: Double
            get() {
                val tt = totalCycleTime
                if (tt == 0.0) {
                    return Double.NaN
                }
                return totalInactiveTime / tt
            }

        override val fractionTimeInRepair: Double
            get() {
                val tt = totalCycleTime
                if (tt == 0.0) {
                    return Double.NaN
                }
                return totalInRepairTime / tt
            }

        /**
         *  Receives the task for processing. Enqueues the task and if the
         *  processor is idle, activates the processor to process tasks.
         *
         *  @param task The task that needs executing.
         */
        override fun receive(task: Task) {
            require(!shutdown) { "${this.name} Task Processor: cannot receive task = $task because it is shutdown!" }
            taskQueue.enqueue(task)
            task.taskProcessor = this
            if (isIdle()) {
                myProcessingEntity = ProcessingEntity("ProcessingEntity_${this.name}")
                activate(myProcessingEntity!!.taskProcessing)
            }
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
        override fun scheduleShutDown(timeUntilShutdown: Double) {
            require(timeUntilShutdown >= 0.0) { "The time until shutdown must be >= 0.0!" }
            myShutDownEvent = schedule(this@TransientTaskProcessor::shutDownAction, timeUntilShutdown)
            // notify the provider of tasks of pending shutdown
//            myDispatcher?.onTaskProcessorAction(this, TaskProcessorStatus.START_SHUTDOWN)
            for (task in taskQueue) {
                task.onTaskProcessorStartAction(TaskProcessorStatus.START_SHUTDOWN)
            }
        }

        /**
         *  Causes a pending shutdown event to be cancelled. If there is a task provider associated with
         *  the task processor it will be notified of the cancellation.
         */
        override fun cancelShutDown() {
            myShutDownEvent?.cancel = true
            myShutDownEvent = null
//            myDispatcher?.onTaskProcessorAction(this, TaskProcessorStatus.CANCEL_SHUTDOWN)
            for (task in taskQueue) {
                task.onTaskProcessorEndAction(TaskProcessorStatus.CANCEL_SHUTDOWN)
            }
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
        override fun hasNextTask(): Boolean {
            return taskQueue.peekNext() != null
        }

        /**
         *  Finds the next task to work on.  If null, then a new task could not be selected.
         */
        private fun nextTask(): Task? {
            return taskQueue.removeNext()
        }

        private fun notifyTasksOfStartAction(taskType: TaskType) {
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
            for (task in taskQueue) {
                task.onTaskProcessorStartAction(actionType)
            }
        }

        private fun notifyTasksOfEndAction(taskType: TaskType) {
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
            for (task in taskQueue) {
                task.onTaskProcessorEndAction(actionType)
            }
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
//                myDispatcher?.onTaskProcessorAction(this, TaskProcessorStatus.SHUTDOWN)
                for (task in taskQueue) {
                    task.onTaskProcessorEndAction(TaskProcessorStatus.SHUTDOWN)
                }
                myShutDownEvent = null
            }
        }

        private inner class ProcessingEntity(name: String?) : Entity(name) {

            /**
             *  Describes how to process a task. If there are tasks, a new task
             *  is selected and then executed.
             */
            val taskProcessing = process("${this.name}_TaskProcessing") {
                while (hasNextTask() && !shutdown) {
                    val nextTask = nextTask() ?: break
                    //TODO this@TaskProcessor is the delegate, not the thing that was registered!!!
                   //nextTask.taskProcessor = this@TaskProcessor
                    currentTask = nextTask
                    // set the state based on the task type
                    val nextState = nextState(nextTask)
                    changeState(nextState)
                    beforeTaskExecution()
                    notifyTasksOfStartAction(nextTask.taskType)
                    nextTask.beforeTaskStart()
                    waitFor(nextTask.taskProcess)
                    nextTask.afterTaskCompleted()
                    notifyTasksOfEndAction(nextTask.taskType)
                    afterTaskExecution()
                    //TODO
                    nextTask.taskDispatcher?.dispatchCompleted(this@TransientTaskProcessor, nextTask)
//                    myDispatcher?.taskCompleted(nextTask)
                    previousTask = nextTask
                    currentTask = null
                    // need to catch shutdown that might have occurred during task execution
                    if (timeOfShutDown <= time) {
                        shutdown()
                    }
                }
                changeState(myIdleState)
                myProcessingEntity = null
            }
        }
    }
}