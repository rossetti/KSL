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

    private val myTaskProcessors = mutableMapOf<String, TaskProcessorIfc>()
    val taskProcessors: Map<String, TaskProcessorIfc>
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
            //TODO consider adding statistics when registering transient processors
        }

        override fun initialize() {
            super.initialize()
            for(processor in myProcessors){
                if (processor is TransientTaskProcessor){
                    processor.setup()
                }
            }
        }

        override fun warmUp() {
            super.warmUp()
            for(processor in myProcessors){
                if (processor is TransientTaskProcessor){
                    processor.resetStates()
                }
            }
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
            require(myProcessors.contains(processor)) { "The processor $processor is not associated with dispatcher, ${this.name}" }
            dispatch(processor)
            taskCompleted(task)
        }

        var startProcessorFailureAction: TaskDispatcherActionIfc? = null
        var endProcessorFailureAction: TaskDispatcherActionIfc? = null
        var startProcessorInactiveAction: TaskDispatcherActionIfc? = null
        var endProcessorInactiveAction: TaskDispatcherActionIfc? = null
        var startProcessorShutdownPendingAction: TaskDispatcherActionIfc? = null
        var processorShutdownAction: TaskDispatcherActionIfc? = null
        var processorCancelShutdownAction: TaskDispatcherActionIfc? = null
        var startProcessorWorkAction: TaskDispatcherActionIfc? = null
        var endProcessorWorkAction: TaskDispatcherActionIfc? = null

        /**
         *  This function is called by tasks that were dispatched by the dispatcher when a processor
         *  action occurs.
         *  Users can provide specific logic to react to the occurrence of the start of a failure,
         *  the end of a failure, start of an inactive period, end of an inactive period, and the warning
         *  of a shutdown and the shutdown. By default, no reaction occurs.
         *  @param task the task that was dispatched
         *  @param status the status indicator for the type of action
         */
        internal fun handleTaskProcessorAction(task: Task, status: TaskProcessorStatus) {
            when (status) {
                TaskProcessorStatus.START_WORK -> {
                    startProcessorWorkAction?.action(this, task, myProcessors)
                    startProcessWorkAction(task)
                }

                TaskProcessorStatus.END_WORK -> {
                    endProcessorWorkAction?.action(this, task, myProcessors)
                    endProcessWorkAction(task)
                }

                TaskProcessorStatus.START_FAILURE -> {
                    startProcessorFailureAction?.action(this, task, myProcessors)
                    startProcessFailureAction(task)
                }

                TaskProcessorStatus.END_FAILURE -> {
                    endProcessorFailureAction?.action(this, task, myProcessors)
                    endProcessFailureAction(task)
                }

                TaskProcessorStatus.START_INACTIVE -> {
                    startProcessorInactiveAction?.action(this, task, myProcessors)
                    startProcessInactiveAction(task)
                }

                TaskProcessorStatus.END_INACTIVE -> {
                    endProcessorInactiveAction?.action(this, task, myProcessors)
                    endProcessInactiveAction(task)
                }

                TaskProcessorStatus.START_SHUTDOWN -> {
                    startProcessorShutdownPendingAction?.action(this, task, myProcessors)
                    processorStartShutdownAction(task)
                }

                TaskProcessorStatus.SHUTDOWN -> {
                    processorShutdownAction?.action(this, task, myProcessors)
                    processorShutdownAction(task)
                }

                TaskProcessorStatus.CANCEL_SHUTDOWN -> {
                    processorCancelShutdownAction?.action(this, task, myProcessors)
                    processorCancelShutdownAction(task)
                }
            }
        }

        protected open fun startProcessWorkAction(task: Task) {}
        protected open fun endProcessWorkAction(task: Task) {}
        protected open fun startProcessFailureAction(task: Task) {}
        protected open fun endProcessFailureAction(task: Task) {}
        protected open fun startProcessInactiveAction(task: Task) {}
        protected open fun endProcessInactiveAction(task: Task) {}
        protected open fun processorStartShutdownAction(task: Task) {}
        protected open fun processorCancelShutdownAction(task: Task) {}
        protected open fun processorShutdownAction(task: Task) {}
    }

    /**
     *  A functional interface to use to model actions that can be invoked to allow a task dispatcher to react
     *  to when a task processor has an action such as a failure, etc.
     */
    fun interface TaskDispatcherActionIfc {
        fun action(
            dispatcher: TaskDispatcher,
            task: Task,
            processors: MutableList<TaskProcessorIfc>
        )
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
        var taskProcessor: TaskProcessorIfc? = null

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

        var startProcessorFailureAction: TaskProcessorActionIfc? = null
        var endProcessorFailureAction: TaskProcessorActionIfc? = null
        var startProcessorInactiveAction: TaskProcessorActionIfc? = null
        var endProcessorInactiveAction: TaskProcessorActionIfc? = null
        var startProcessorShutdownPendingAction: TaskProcessorActionIfc? = null
        var processorShutdownAction: TaskProcessorActionIfc? = null
        var processorCancelShutdownAction: TaskProcessorActionIfc? = null
        var startProcessorWorkAction: TaskProcessorActionIfc? = null
        var endProcessorWorkAction: TaskProcessorActionIfc? = null

        /**
         *  This function is called for tasks that are waiting for a processor when a processor
         *  action occurs.
         *  Users can provide specific logic to react to the occurrence of the start of a failure,
         *  the end of a failure, start of an inactive period, end of an inactive period, and the warning
         *  of a shutdown and the shutdown. By default, no reaction occurs.
         *  @param status the status indicator for the type of action (START_WORK, START_FAILURE,
         *  START_INACTIVE, START_SHUTDOWN, END_WORK, END_FAILURE, END_INACTIVE, SHUTDOWN, CANCEL_SHUTDOWN)
         */
        internal fun handleTaskProcessorAction(status: TaskProcessorStatus) {
            when (status) {
                TaskProcessorStatus.START_WORK -> {
                    startProcessorWorkAction?.action(this)
                    startProcessWorkAction()
                }

                TaskProcessorStatus.END_WORK -> {
                    endProcessorWorkAction?.action(this)
                    endProcessWorkAction()
                }

                TaskProcessorStatus.START_FAILURE -> {
                    startProcessorFailureAction?.action(this)
                    startProcessFailureAction()
                }

                TaskProcessorStatus.END_FAILURE -> {
                    endProcessorFailureAction?.action(this)
                    endProcessFailureAction()
                }

                TaskProcessorStatus.START_INACTIVE -> {
                    startProcessorInactiveAction?.action(this)
                    startProcessInactiveAction()
                }

                TaskProcessorStatus.END_INACTIVE -> {
                    endProcessorInactiveAction?.action(this)
                    endProcessInactiveAction()
                }

                TaskProcessorStatus.START_SHUTDOWN -> {
                    startProcessorShutdownPendingAction?.action(this)
                    processorStartShutdownAction()
                }

                TaskProcessorStatus.SHUTDOWN -> {
                    processorShutdownAction?.action(this)
                    processorShutdownAction()
                }

                TaskProcessorStatus.CANCEL_SHUTDOWN -> {
                    processorCancelShutdownAction?.action(this)
                    processorCancelShutdownAction()
                }
            }
            this.taskDispatcher?.handleTaskProcessorAction(this, status)
        }

        protected open fun startProcessWorkAction() {}
        protected open fun endProcessWorkAction() {}
        protected open fun startProcessFailureAction() {}
        protected open fun endProcessFailureAction() {}
        protected open fun startProcessInactiveAction() {}
        protected open fun endProcessInactiveAction() {}
        protected open fun processorStartShutdownAction() {}
        protected open fun processorCancelShutdownAction() {}
        protected open fun processorShutdownAction() {}

    }

    /**
     *  A functional interface to use to model actions that can be invoked to allow a task to react
     *  to when a task processor has an action such as a failure, etc.
     */
    fun interface TaskProcessorActionIfc {
        fun action(task: Task, )
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

    /**
     *  An interface to represent the general concept of a task processor.
     *  A task processor is something that receives tasks and executes them.
     */
    interface TaskProcessorIfc : IdentityIfc {

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
         *  Cause the task processor to be setup (initialized) for processing
         */
        fun setup()

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

        /**
         *  The number of tasks waiting for the processor
         */
        fun numTasksInQ(): Int
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

    /**
     *  Used to collect statistical performance for a processor based on
     *  accumulated state information from within replications. Performance
     *  is across replications.
     *  @param parent the parent of the model element
     *  @param processor the processor being observed for performance
     *  @param allPerformance if false, only busy state performance is collected. If true
     *  then all state performance is collected. The default is false.
     *  @param name the name of the model element
     */
    inner class TaskProcessorPerformance(
        parent: ModelElement,
        private val processor: TaskProcessorIfc,
        val allPerformance: Boolean = false,
        name: String? = null
    ) : ModelElement(parent, name) {

        private val myFractionTimeBusy = Response(this, name = "${this.name}:FractionTimeBusy")
        val fractionTimeBusyResponse: ResponseCIfc
            get() = myFractionTimeBusy
        private val myNumTimesBusy = Response(this, name = "${this.name}:NumTimesBusy")
        val numTimesBusyResponse: ResponseCIfc
            get() = myNumTimesBusy
        private val myFractionIdleTime by lazy { Response(this, name = "${this.name}:FractionTimeIdle") }
        val fractionTimeIdleResponse: ResponseCIfc?
            get() = if (allPerformance) myFractionIdleTime else null
        private val myNumTimesIdle by lazy { Response(this, name = "${this.name}:NumTimesIdle") }
        val numTimesIdleResponse: ResponseCIfc?
            get() = if (allPerformance) myNumTimesIdle else null
        private val myFractionInRepairTime by lazy { Response(this, name = "${this.name}:FractionTimeInRepair") }
        val fractionTimeInRepairResponse: ResponseCIfc?
            get() = if (allPerformance) myFractionInRepairTime else null
        private val myNumTimesRepaired by lazy { Response(this, name = "${this.name}:NumTimesRepaired") }
        val numTimesRepairedResponse: ResponseCIfc?
            get() = if (allPerformance) myNumTimesRepaired else null
        private val myFractionInactiveTime by lazy { Response(this, name = "${this.name}:FractionTimeInactive") }
        val fractionTimeInactiveResponse: ResponseCIfc?
            get() = if (allPerformance) myFractionInactiveTime else null
        private val myNumTimesInactive by lazy { Response(this, name = "${this.name}:NumTimesInactive") }
        val numTimesInactiveResponse: ResponseCIfc?
            get() = if (allPerformance) myNumTimesInactive else null

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
            processor.setup()
        }

        override fun replicationEnded() {
            super.replicationEnded()
            myFractionTimeBusy.value = processor.fractionTimeBusy
            myNumTimesBusy.value = processor.numTimesBusy
            if (allPerformance) {
                myFractionIdleTime.value = processor.fractionTimeIdle
                myNumTimesIdle.value = processor.numTimesIdle
                myFractionInRepairTime.value = processor.fractionTimeInRepair
                myNumTimesRepaired.value = processor.numTimesRepaired
                myFractionInactiveTime.value = processor.fractionTimeInactive
                myNumTimesInactive.value = processor.numTimesInactive
            }
        }

        override fun warmUp() {
            super.warmUp()
            processor.resetStates()
        }
    }

    open inner class TaskProcessor(
        parent: ModelElement,
        allPerformance: Boolean = false,
        private val taskQueue: QueueIfc<Task> = TaskQueue(),
        name: String? = null
    ) : ModelElement(parent, name), TaskProcessorIfc {

        private val myTaskProcessor: TransientTaskProcessor = TransientTaskProcessor(name, taskQueue)

        val performance: TaskProcessorPerformance = TaskProcessorPerformance(
            this, myTaskProcessor, allPerformance, name = "${this.name}:Performance"
        )

        override fun setup() {
            myTaskProcessor.setup()
        }

        override val idleState: StateAccessorIfc
            get() = myTaskProcessor.idleState
        override val busyState: StateAccessorIfc
            get() = myTaskProcessor.busyState
        override val inRepairState: StateAccessorIfc
            get() = myTaskProcessor.inRepairState
        override val inactiveState: StateAccessorIfc
            get() = myTaskProcessor.inactiveState
        override val currentState: StateAccessorIfc
            get() = myTaskProcessor.currentState
        override val isShutdownPending: Boolean
            get() = myTaskProcessor.isShutdownPending
        override val timeUntilShutdown: Double
            get() = myTaskProcessor.timeUntilShutdown
        override val timeOfShutDown: Double
            get() = myTaskProcessor.timeOfShutDown
        override val numTimesRepaired: Double
            get() = myTaskProcessor.numTimesRepaired
        override val numTimesInactive: Double
            get() = myTaskProcessor.numTimesInactive
        override val numTimesIdle: Double
            get() = myTaskProcessor.numTimesIdle
        override val numTimesBusy: Double
            get() = myTaskProcessor.numTimesBusy
        override val totalIdleTime: Double
            get() = myTaskProcessor.totalIdleTime
        override val totalBusyTime: Double
            get() = myTaskProcessor.totalBusyTime
        override val totalInRepairTime: Double
            get() = myTaskProcessor.totalInRepairTime
        override val totalInactiveTime: Double
            get() = myTaskProcessor.totalInactiveTime
        override val totalCycleTime: Double
            get() = myTaskProcessor.totalCycleTime
        override val fractionTimeIdle: Double
            get() = myTaskProcessor.fractionTimeIdle
        override val fractionTimeBusy: Double
            get() = myTaskProcessor.fractionTimeBusy
        override val fractionTimeInactive: Double
            get() = myTaskProcessor.fractionTimeInactive
        override val fractionTimeInRepair: Double
            get() = myTaskProcessor.fractionTimeInRepair

        override fun resetStates() {
            myTaskProcessor.resetStates()
        }

        override fun isBusy(): Boolean {
            return myTaskProcessor.isBusy()
        }

        override fun isInRepair(): Boolean {
            return myTaskProcessor.isInRepair()
        }

        override fun isIdle(): Boolean {
            return myTaskProcessor.isIdle()
        }

        override fun isInactive(): Boolean {
            return myTaskProcessor.isInactive()
        }

        override fun isShutDown(): Boolean {
            return myTaskProcessor.isShutDown()
        }

        override fun receive(task: Task) {
            myTaskProcessor.receive(task)
            //make sure that the task does not have the delegate as its processor
            task.taskProcessor = this
        }

        override fun scheduleShutDown(timeUntilShutdown: Double) {
            myTaskProcessor.scheduleShutDown(timeUntilShutdown)
        }

        override fun cancelShutDown() {
            myTaskProcessor.cancelShutDown()
        }

        override fun hasNextTask(): Boolean {
            return myTaskProcessor.hasNextTask()
        }

        override fun numTasksInQ(): Int {
            return myTaskProcessor.numTasksInQ()
        }

    }

    /**
     * Responsible for executing tasks that have been supplied.
     * This processor is transient. It is not a model element and thus
     * does not participate in automatic model element actions such as
     * initialization, warmup, and replication ending.
     * @param name the name of the processor
     * @param taskQueue a queue that will be used to hold tasks while the processor
     * is processing its current task. The default is a non-statistical based queue.
     */
    open inner class TransientTaskProcessor(
        name: String? = null,
        private val taskQueue: QueueIfc<Task> = TaskQueue()
    ) : TaskProcessorIfc, IdentityIfc by Identity(name) {

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

        override fun setup() {
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
         *  and any waiting tasks are notified of the pending shutdown. This allows the
         *  tasks to react gracefully to the pending shutdown.
         *
         * @param timeUntilShutdown The time until the commencement of the shutdown. The default is 0 (now).
         */
        override fun scheduleShutDown(timeUntilShutdown: Double) {
            require(timeUntilShutdown >= 0.0) { "The time until shutdown must be >= 0.0!" }
            myShutDownEvent = schedule(this@TransientTaskProcessor::shutDownAction, timeUntilShutdown)
            // notify waiting tasks of pending shutdown
            for (task in taskQueue) {
                task.handleTaskProcessorAction(TaskProcessorStatus.START_SHUTDOWN)
            }
        }

        /**
         *  Causes a pending shutdown event to be cancelled. If there are waiting tasks associated with
         *  the task processor, they will be notified of the cancellation.
         */
        override fun cancelShutDown() {
            myShutDownEvent?.cancel = true
            myShutDownEvent = null
            for (task in taskQueue) {
                task.handleTaskProcessorAction(TaskProcessorStatus.CANCEL_SHUTDOWN)
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

        override fun numTasksInQ(): Int {
            return taskQueue.size
        }

        /**
         *  Finds the next task to work on.  If null, then a new task could not be selected.
         */
        private fun nextTask(): Task? {
            return taskQueue.removeNext()
        }

        private fun processorStartActionType(taskType: TaskType): TaskProcessorStatus {
            return when (taskType) {
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
        }

        private fun notifyTasksOfStartAction(taskType: TaskType) {
            val actionType = processorStartActionType(taskType)
            for (task in taskQueue) {
                task.handleTaskProcessorAction(actionType)
            }
        }

        private fun processorEndActionType(taskType: TaskType): TaskProcessorStatus {
            return when (taskType) {
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
        }

        private fun notifyTasksOfEndAction(taskType: TaskType) {
            val actionType = processorEndActionType(taskType)
            for (task in taskQueue) {
                task.handleTaskProcessorAction(actionType)
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
                // notify any waiting tasks of shutdown
                for (task in taskQueue) {
                    task.handleTaskProcessorAction(TaskProcessorStatus.SHUTDOWN)
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
                    currentTask = nextTask
                    // set the state based on the task type
                    val nextState = nextState(nextTask)
                    changeState(nextState)
                    beforeTaskExecution()
                    notifyTasksOfStartAction(nextTask.taskType)
//                    nextTask.taskDispatcher?.handleTaskProcessorAction(
//                        nextTask.taskProcessor!!, processorStartActionType(nextTask.taskType))
                    nextTask.beforeTaskStart()
                    waitFor(nextTask.taskProcess)
                    nextTask.afterTaskCompleted()
                    notifyTasksOfEndAction(nextTask.taskType)
//                    nextTask.taskDispatcher?.handleTaskProcessorAction(
//                        nextTask.taskProcessor!!, processorEndActionType(nextTask.taskType))
                    afterTaskExecution()
                    nextTask.taskDispatcher?.dispatchCompleted(nextTask.taskProcessor!!, nextTask)
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