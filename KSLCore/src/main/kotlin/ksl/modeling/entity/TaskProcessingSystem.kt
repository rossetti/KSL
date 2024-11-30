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
         *  Receives tasks for dispatching to processors. Selects a processor for dispatching
         *  according to the selectProcessor() function and if a processor is found
         *  dispatches the task using the dispatch() function.
         *
         *  @param task the task that needs dispatching
         */
        fun receive(task: Task) {
            myTaskQueue.enqueue(task)
            val processor = selectProcessor()
            if (processor != null) {
                dispatch(processor)
            }
//            selectProcessor()?.activateProcessor(this)
        }

        /**
         *  Checks if there is a task that needs dispatching and if so
         *  dispatches it to the supplied processor.
         *
         *  @param processor the processor to send the next task to
         */
        protected open fun dispatch(processor: TaskProcessorIfc){
            if (hasTask()){
                val nextTask = nextTask()
                if (nextTask != null){
                    nextTask.taskDispatcher = this
                    processor.receive(nextTask)
                }
            }
        }

        /**
         *  Determines if there is a task that needs dispatching.
         *  Returns true if there is a task that needs dispatching.
         */
        fun hasTask(): Boolean {
            return myTaskQueue.peekNext() != null
        }

        /**
         *  Returns the next task that needs dispatching or null
         *  if no task is available for dispatching.
         */
        protected open fun nextTask(): Task? {
            return myTaskQueue.removeNext()
        }

        /**
         *  Registers the supplied task processor as a possible processor for
         *  dispatches.
         *
         *  @param taskProcessor the task processor to register
         */
        fun register(taskProcessor: TaskProcessorIfc) {
            require(!myProcessors.contains(taskProcessor)) {"The task processor, ${taskProcessor}, is already registered with dispatcher, $name"}
            myProcessors.add(taskProcessor)
        }

        /** Causes the supplied task processor to no longer be considered for
         *  dispatching.
         *  @param taskProcessor the task processor to unregister
         */
        fun unregister(taskProcessor: TaskProcessorIfc) : Boolean {
            return myProcessors.remove(taskProcessor)
        }

        /**
         *  Selects a task processor for dispatching. The default behavior is to
         *  select the first processor that is idle and not shutdown. A task
         *  processor is idle if it is not failed or not inactive.
         */
        protected open fun selectProcessor(): TaskProcessorIfc? {
            return myProcessors.firstOrNull { it.isIdle() && !it.isShutDown() }
        }

        /**
         *  Called when a dispatched task is completed. The default behavior is
         *  to do nothing.
         */
        protected fun taskCompleted(task: Task) {}

        /**
         * Called when a dispatched task is completed by the processor
         */
        internal fun dispatchCompleted(processor: TaskProcessorIfc, task: Task) {
            // handle the possibility that the processor was unregistered during the
            // execution of the task.
            if (myProcessors.contains(processor)){
                dispatch(processor)
            }
            taskCompleted(task)
        }

        /**
         *  This function is called by task processors that are processing tasks sent by the provider.
         *  Subclasses can provide specific logic to react to the occurrence of the start of a failure,
         *  the end of a failure, start of an inactive period, end of an inactive period, and the warning
         *  of a shutdown and the shutdown. By default, no reaction occurs.
         *  @param processor the task processor
         *  @param status the status indicator for the type of action
         */
        fun onTaskProcessorAction(processor: TaskProcessor, status: TaskProcessorStatus) {}
        //TODO consider unregistering processors that shutdown
        //TODO consider protected methods to handle the cases and making onTaskProcessorAction() internal
    }

    fun interface TaskCompletedIfc {
        fun taskCompleted(task: Task)
    }

    fun interface TaskProcessorActionIfc {
        fun onTaskProcessorAction(taskProcessor: TaskProcessor, status: TaskProcessorStatus)
    }

//    interface TaskDispatcherIfc {
//
//        fun receive(task: Task)
//
//        /**
//         *  Checks if the provider has another task
//         */
//        fun hasNext(): Boolean
//
//        /**
//         *  Provides the task. Implementors should set the provider of the task
//         *  before supplying the task.
//         */
//        fun next(): Task?
//
//        /**
//         * Called when the task is completed
//         */
//        fun taskCompleted(task: Task) {}
//
//        /**
//         *  This function is called by task processors that are processing tasks sent by the provider.
//         *  Subclasses can provide specific logic to react to the occurrence of the start of a failure,
//         *  the end of a failure, start of an inactive period, end of an inactive period, and the warning
//         *  of a shutdown and the shutdown. By default, no reaction occurs.
//         *  @param taskProcessor the task processor
//         *  @param status the status indicator for the type of action
//         */
//        fun onTaskProcessorAction(taskProcessor: TaskProcessor, status: TaskProcessorStatus) {}
//
//        fun register(taskProcessor: TaskProcessorIfc)
//
//        fun unregister(taskProcessor: TaskProcessorIfc): Boolean
//
//        fun selectProcessor() : TaskProcessorIfc?
//    }

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
        open fun onTaskProcessorEndAction(status:TaskProcessorStatus) {}
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

    interface TaskProcessorPerformanceIfc {
        val fractionTimeBusyResponse: ResponseCIfc
        val numTimesBusyResponse: ResponseCIfc
        val fractionTimeIdleResponse: ResponseCIfc
        val numTimesIdleResponse: ResponseCIfc
        val fractionTimeInRepairResponse: ResponseCIfc
        val numTimesRepairedResponse: ResponseCIfc
        val fractionTimeInactiveResponse: ResponseCIfc
        val numTimesInactiveResponse: ResponseCIfc
    }

    interface TaskProcessorIfc {
        val taskProcessingSystem: TaskProcessingSystem //TODO??
        val idleState: StateAccessorIfc
        val busyState: StateAccessorIfc
        val inRepairState: StateAccessorIfc
        val inactiveState: StateAccessorIfc
        val currentState: StateAccessorIfc

        val isShutdownPending: Boolean
        val timeUntilShutdown: Double
        val timeOfShutDown: Double
        val numTimesRepaired: Double
        val numTimesInactive: Double
        val numTimesIdle: Double
        val numTimesBusy: Double
        val totalIdleTime: Double
        val totalBusyTime: Double
        val totalFailedTime: Double
        val totalInactiveTime: Double
        val totalCycleTime: Double
        val fractionTimeIdle: Double
        val fractionTimeBusy: Double
        val fractionTimeInactive: Double
        val fractionTimeFailed: Double

        fun resetStates()
        fun isBusy(): Boolean
        fun isFailed(): Boolean
        fun isIdle(): Boolean
        fun isInactive(): Boolean
        fun isShutDown(): Boolean
        fun isNotShutDown(): Boolean {
            return !isShutDown()
        }

//        /**
//         *  Causes the task processor to be activated and to start processing tasks from the supplied
//         *  task provider. The task provider must not be shutdown and must be idle in order to be
//         *  activated. The task processor will continue to execute tasks from the provider as long
//         *  as the provider can supply them.
//         *
//         * @param taskProvider the task provider from which tasks will be pulled after activation
//         */
//        fun activateProcessor(taskProvider: TaskDispatcher)

        /**
         *  Causes the task processor to be activated and to start processing tasks from the supplied
         *  task provider. The task provider must not be shutdown and must be idle in order to be
         *  activated. The task processor will continue to execute tasks from the provider as long
         *  as the provider can supply them.
         *
         * @param taskProvider the task provider from which tasks will be pulled after activation
         */
        fun activateProcessor(taskProvider: TaskDispatcher)

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

    inner class TaskQueue: QueueIfc<Task> {

        private val myTaskList = mutableListOf<Task>()

        override val size: Int
            get() = myTaskList.size
        override val isEmpty: Boolean
            get() = myTaskList.isEmpty()
        override val isNotEmpty: Boolean
            get() = myTaskList.isNotEmpty()

        override fun peekNext(): Task? {
            if (isNotEmpty){
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
            require(!contains(qObject)) {"The task, $qObject is already in the queue"}
            myTaskList.add(qObject)
        }

    }

    open inner class TaskProcessorME(
        parent: ModelElement,
        private val allPerformance: Boolean = false,
        name: String? = null,
        private val taskProcessor: TaskProcessor = TaskProcessor(name),
    ) : ModelElement(parent, name),
        TaskProcessorPerformanceIfc, TaskProcessorIfc by taskProcessor {

        private val myFractionTimeBusy = Response(this, name = "${this.name}:FractionTimeBusy")
        override val fractionTimeBusyResponse: ResponseCIfc
            get() = myFractionTimeBusy
        private val myNumTimesBusy = Response(this, name = "${this.name}:NumTimesBusy")
        override val numTimesBusyResponse: ResponseCIfc
            get() = myNumTimesBusy
        private val myFractionIdleTime by lazy { Response(this, name = "${this.name}:FractionTimeIdle") }
        override val fractionTimeIdleResponse: ResponseCIfc
            get() = myFractionIdleTime
        private val myNumTimesIdle by lazy { Response(this, name = "${this.name}:NumTimesIdle") }
        override val numTimesIdleResponse: ResponseCIfc
            get() = myNumTimesIdle
        private val myFractionInRepairTime by lazy { Response(this, name = "${this.name}:FractionTimeInRepair") }
        override val fractionTimeInRepairResponse: ResponseCIfc
            get() = myFractionInRepairTime
        private val myNumTimesRepaired by lazy { Response(this, name = "${this.name}:NumTimesRepaired") }
        override val numTimesRepairedResponse: ResponseCIfc
            get() = myNumTimesRepaired
        private val myFractionInactiveTime by lazy { Response(this, name = "${this.name}:FractionTimeInactive") }
        override val fractionTimeInactiveResponse: ResponseCIfc
            get() = myFractionInactiveTime
        private val myNumTimesInactive by lazy { Response(this, name = "${this.name}:NumTimesInactive") }
        override val numTimesInactiveResponse: ResponseCIfc
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
            taskProcessor.initialize()
        }

        override fun replicationEnded() {
            super.replicationEnded()
            myFractionTimeBusy.value = taskProcessor.fractionTimeBusy
            myNumTimesBusy.value = taskProcessor.numTimesBusy
            if (allPerformance) {
                myFractionIdleTime.value = taskProcessor.fractionTimeIdle
                myNumTimesIdle.value = taskProcessor.numTimesIdle
                myFractionInRepairTime.value = taskProcessor.fractionTimeFailed
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
    open inner class TaskProcessor(
        name: String? = null,
        private val taskQueue: QueueIfc<Task> = TaskQueue()
    ) : TaskProcessorIfc, IdentityIfc by Identity(name) {

        //TODO why is this needed
        override val taskProcessingSystem: TaskProcessingSystem = this@TaskProcessingSystem
        //TODO why is this needed
//        private var myDispatcher: TaskDispatcher? = null
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

        fun initialize() {
            resetStates()
            taskQueue.clear()
//            myDispatcher = null
            myProcessor = null
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

        override fun isFailed(): Boolean {
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

        override val totalFailedTime: Double
            get() {
                val st = if (isFailed()) time - myInRepairState.timeStateEntered else 0.0
                return myInRepairState.totalTimeInState + st
            }

        override val totalInactiveTime: Double
            get() {
                val st = if (isInactive()) time - myInactiveState.timeStateEntered else 0.0
                return myInactiveState.totalTimeInState + st
            }

        override val totalCycleTime: Double
            get() = totalIdleTime + totalBusyTime + totalFailedTime + totalInactiveTime

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

        override val fractionTimeFailed: Double
            get() {
                val tt = totalCycleTime
                if (tt == 0.0) {
                    return Double.NaN
                }
                return totalFailedTime / tt
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
            if (isIdle()){
                myProcessor = Processor("Processor_${this.name}")
                activate(myProcessor!!.taskProcessing)
            }
        }

//        /**
//         *  Causes the task processor to be activated and to start processing tasks from the supplied
//         *  task provider. The task provider must not be shutdown and must be idle in order to be
//         *  activated. The task processor will continue to execute tasks from the provider as long
//         *  as the provider can supply them.
//         *
//         * @param taskProvider the task provider from which tasks will be pulled after activation
//         */
//        override fun activateProcessor(taskProvider: TaskDispatcher) {
//            require(!shutdown) { "${this.name} Task Processor: cannot be activated because it is shutdown!" }
//            require(isIdle()) { "${this.name} Task Processor: cannot be activated because it is not idle!" }
//            // must be idle thus it can be activated
//            // if the incoming task provider is different from the current provider
//            // then we can exchange it, otherwise it stays the same
////            if (myDispatcher != taskProvider) {
////                myDispatcher = taskProvider
////            }
//            myProcessor = Processor("Processor_${this.name}")
//            activate(myProcessor!!.taskProcessing)
//        }

        /**
         *  Causes the task processor to be activated and to start processing tasks from the supplied
         *  task provider. The task provider must not be shutdown and must be idle in order to be
         *  activated. The task processor will continue to execute tasks from the provider as long
         *  as the provider can supply them.
         *
         * @param taskProvider the task provider from which tasks will be pulled after activation
         */
        override fun activateProcessor(taskProvider: TaskDispatcher) {
            require(!shutdown) { "${this.name} Task Processor: cannot be activated because it is shutdown!" }
            require(isIdle()) { "${this.name} Task Processor: cannot be activated because it is not idle!" }
            // must be idle thus it can be activated
            // if the incoming task provider is different from the current provider
            // then we can exchange it, otherwise it stays the same
//            if (myDispatcher != taskProvider) {
//                myDispatcher = taskProvider
//            }
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
        override fun scheduleShutDown(timeUntilShutdown: Double) {
            require(timeUntilShutdown >= 0.0) { "The time until shutdown must be >= 0.0!" }
            myShutDownEvent = schedule(this@TaskProcessor::shutDownAction, timeUntilShutdown)
            // notify the provider of tasks of pending shutdown
//            myDispatcher?.onTaskProcessorAction(this, TaskProcessorStatus.START_SHUTDOWN)
            for(task in taskQueue){
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
            for(task in taskQueue){
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

        private fun notifyTasksOfStartAction(taskType: TaskType){
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
            for(task in taskQueue){
                task.onTaskProcessorStartAction(actionType)
            }
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

//            myDispatcher?.onTaskProcessorAction(this, actionType)
        }

        private fun notifyTasksOfEndAction(taskType: TaskType){
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
            for(task in taskQueue){
                task.onTaskProcessorEndAction(actionType)
            }
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
//            myDispatcher?.onTaskProcessorAction(this, actionType)

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
                for(task in taskQueue){
                    task.onTaskProcessorEndAction(TaskProcessorStatus.SHUTDOWN)
                }
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
                    //TODO
                    nextTask.taskDispatcher?.dispatchCompleted(this@TaskProcessor, nextTask)
//                    myDispatcher?.taskCompleted(nextTask)
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