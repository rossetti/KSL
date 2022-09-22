package ksl.modeling.entity

import ksl.modeling.elements.EventGenerator
import ksl.modeling.queue.QObject
import ksl.modeling.queue.Queue
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.GetValueIfc
import ksl.utilities.exceptions.IllegalStateException
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.ConstantRV
import mu.KLoggable
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.createCoroutineUnintercepted
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * A process model facilitates the modeling of entities experiencing processes via the
 * process view of simulation. A ProcessModel has inner classes (Entity, EntityGenerator, etc.)
 * that can be used to describe entities and the processes that they experience. The key class
 * is Entity, which has a function process() that uses a builder to describe the entity's
 * process in the form of a coroutine.  An entity can have many processes described that it
 * may follow based on different modeling logic. A process model facilitates the running
 * of a sequence of processes that are stored in an entity's processSequence property.
 * An entity can experience only one process at a time. After completing the process,
 * the entity will try to use its sequence to run the next process (if available). Individual
 * processes can be activated for specific entities.
 *
 * @param parent the parent model element
 * @param name an optional name for the process model
 */
open class ProcessModel(parent: ModelElement, name: String? = null) : ModelElement(parent, name) {

    private val suspendedEntities = mutableSetOf<Entity>()

    /**
     * @param entityCreator the thing that creates the entities of the particular type. Typically,
     * a reference to the constructor of the class
     * @param theTimeUntilTheFirstEntity the time until the first entity creation
     * @param theTimeBtwEvents the time between entity creation
     * @param theMaxNumberOfEvents the maximum number of entities to create
     * @param theTimeOfTheLastEvent the time of the last entity creation
     * @param theName a name for the generator
     */
    protected inner class EntityGenerator<T : Entity>(
        private val entityCreator: () -> T,
        theTimeUntilTheFirstEntity: RandomIfc = ConstantRV.ZERO,
        theTimeBtwEvents: RandomIfc = ConstantRV.POSITIVE_INFINITY,
        theMaxNumberOfEvents: Long = Long.MAX_VALUE,
        theTimeOfTheLastEvent: Double = Double.POSITIVE_INFINITY,
        var activationPriority: Int = KSLEvent.DEFAULT_PRIORITY + 1,
        theName: String? = null
    ) : EventGenerator(
        this@ProcessModel, null, theTimeUntilTheFirstEntity,
        theTimeBtwEvents, theMaxNumberOfEvents, theTimeOfTheLastEvent, theName
    ) {
        override fun generate() {
            val entity = entityCreator()
            startProcessSequence(entity, priority = activationPriority)
        }

    }

    /** Cause the entity to start the process sequence in the order specified by the sequence.
     *  The activation of the first process is governed by an event that is scheduled
     *  to occur based on the time until activation parameter.
     *
     * @param entity the entity to start the sequence
     * @param timeUntilActivation the time until the first process in the sequence activates
     * @param priority the priority associated with the event to start the first process
     * @return the scheduled activation event or null if there were no processes to start.
     */
    fun <T : Entity> startProcessSequence(
        entity: T,
        timeUntilActivation: Double = 0.0,
        priority: Int = KSLEvent.DEFAULT_PRIORITY,
    ): KSLEvent<KSLProcess>? {
        if (entity.processSequence.isEmpty()) {
            logger.warn { "Attempted to start an empty sequence for entity: $entity" }
        }
        entity.useProcessSequence = true
        entity.processSequenceIterator = entity.processSequence.listIterator()
        if (entity.processSequenceIterator.hasNext()) {
            //TODO could attache the entity to the event
            return activate(entity.processSequenceIterator.next())
        }
        return null
    }

    /** Cause the entity to start the process sequence in the order specified by the sequence.
     *  The activation of the first process is governed by an event that is scheduled
     *  to occur at the specified activation time.
     *
     * @param entity the entity to start the sequence
     * @param timeUnitActivation the time until the start of the first process in the sequence
     * @param priority the priority associated with the event to start the first process
     * @return the scheduled activation event or null if there were no processes to start.
     */
    fun <T : Entity> startProcessSequence(
        entity: T,
        timeUnitActivation: GetValueIfc,
        priority: Int = KSLEvent.DEFAULT_PRIORITY,
    ): KSLEvent<KSLProcess>? {
        return startProcessSequence(entity, timeUnitActivation.value, priority)
    }

    /** Cause the entity to start the process.
     *  The activation of the process is governed by an event that is scheduled
     *  to occur at the specified activation time.
     *
     * @param process the process to start for an entity
     * @param timeUntilActivation the time until the start of the process
     * @param priority the priority associated with the event to start the process
     * @return the scheduled activation event
     */
    fun activate(
        process: KSLProcess,
        timeUntilActivation: GetValueIfc,
        priority: Int = KSLEvent.DEFAULT_PRIORITY
    ): KSLEvent<KSLProcess> {
        return activate(process, timeUntilActivation.value, priority)
    }

    /** Cause the entity to start the process.
     *  The activation of the process is governed by an event that is scheduled
     *  to occur at the specified activation time.
     *
     * @param process the process to start for an entity
     * @param timeUntilActivation the time until the start the process
     * @param priority the priority associated with the event to start the process
     * @return the scheduled activation event
     */
    fun activate(
        process: KSLProcess,
        timeUntilActivation: Double = 0.0,
        priority: Int = KSLEvent.DEFAULT_PRIORITY
    ): KSLEvent<KSLProcess> {
        val c = process as Entity.ProcessCoroutine
        //TODO could attache the entity to the event
        return c.activate(timeUntilActivation, priority)
    }

    /**
     *  This method is called when the entity has been activated, run at least
     *  one process and has no further processes to run.  Subclasses can
     *  override this method to provide logic after all processes have been
     *  completed by the entity. By default, nothing happens.
     *
     *  @param entity  The entity that just completed all of its processes
     */
    protected open fun dispose(entity: Entity) {

    }

    final override fun afterReplication() {
        for (entity in suspendedEntities) {
            entity.terminateProcess()
        }
        suspendedEntities.clear()
    }

    /** An entity is something that can experience processes and as such may wait in queue. It is a
     * subclass of QObject.  The general approach is to use the process() function to define
     * a process that a subclass of Entity can follow.  Entity instances may use resources, signals,
     * hold queues, etc. as shared mutable state.  Entities may follow a process sequence if defined.
     *
     * @param aName an optional name for the entity
     */
    open inner class Entity(aName: String? = null) : QObject(time, aName) {

        /**
         *  Controls whether the entity uses an assigned process sequence via the processSequence property
         *  at the end of successfully completing a process to determine the next process to experience.
         *  The default is true.
         */
        var useProcessSequence = false

        /**
         *  An iterator over the entity's current process sequence.  If a process sequence is supplied
         *  and the property useProcessSequence is true then the iterator is used to determine the
         *  next process for the entity to execute.
         */
        var processSequenceIterator: ListIterator<KSLProcess> = emptyList<KSLProcess>().listIterator()
            internal set

        /**
         *  Provides a list of processes for the entity to follow before being disposed
         */
        var processSequence: MutableList<KSLProcess> = mutableListOf()
            set(list) {
                for (process in list) {
                    require(process.entity == this) { "The process $process does not belong to entity $this in entity type $processModel" }
                }
                field = list
            }

        /**
         *  Controls whether the entity goes through the function dispose() of its containing ProcessModel.
         *  The default is true.
         */
        var autoDispose = true

        private var processCounter = 0
        val processModel = this@ProcessModel
        private val myCreatedState = CreatedState()
        private val myScheduledState = Scheduled()
        private val myWaitingForSignalState = WaitingForSignal()
        private val myInHoldQueueState = InHoldQueue()
        private val myActiveState = Active()
        private val myWaitingForResourceState = WaitingForResource()
        private val myProcessEndedState = ProcessEndedState()
        private var state: EntityState = myCreatedState

        val isCreated: Boolean
            get() = state == myCreatedState
        val isScheduled: Boolean
            get() = state == myScheduledState
        val isWaitingForSignal: Boolean
            get() = state == myWaitingForSignalState
        val isInHoldQueue: Boolean
            get() = state == myInHoldQueueState
        val isActive: Boolean
            get() = state == myActiveState
        val isWaitingForResource: Boolean
            get() = state == myWaitingForResourceState
        val isProcessEnded: Boolean
            get() = state == myProcessEndedState
        val isSuspended: Boolean
            get() = isScheduled || isWaitingForSignal || isInHoldQueue || isWaitingForResource

        var currentDelay: String? = null
            private set
        var currentSuspensionPoint: String? = null
            private set
        var currentWaitFor: String? = null
            private set
        var currentHold: String? = null
            private set
        var currentSeize: String? = null
            private set

        /**
         *  When an entity enters a time delayed state, this property captures the event associated
         *  with the delay action
         */
        private var myDelayEvent: KSLEvent<Nothing>? = null
        //TODO add functionality to allow cancellation, this will involve interrupting the delay
        private val myResumeAction = ResumeAction()

        private var myCurrentProcess: ProcessCoroutine? = null // track the currently executing process
        val currentProcess: KSLProcess?
            get() = myCurrentProcess

        val hasCurrentProcess: Boolean
            get() = myCurrentProcess != null
        val currentProcessName: String?
            get() = myCurrentProcess?.name
        private var myPendingProcess: ProcessCoroutine? = null // if a process has been scheduled to activate

        /**
         *  True if a process has been scheduled to activate
         */
        val hasPendingProcess: Boolean
            get() = myPendingProcess != null
        val pendingProcess: KSLProcess?
            get() = myPendingProcess

        /**
         *  If not null, the name of the process scheduled to activate
         */
        val pendingProcessName: String?
            get() = myPendingProcess?.name

        var previousProcess: KSLProcess? = null
            private set

        /**  An entity can be using 0 or more resources.
         *  The key to this map represents the resources that are allocated to this entity.
         *  The element of this map represents the list of allocations allocated to the entity for the give resource.
         */
        private val resourceAllocations: MutableMap<Resource, MutableList<Allocation>> = mutableMapOf()

        /**
         *  A string representation of the allocations held by the entity. Useful for printing and
         *  diagnostics.
         */
        fun allocationsAsString(): String {
            val str = StringBuilder()
            for (entry in resourceAllocations) {
                str.append("Resource: ${entry.key.name}")
                str.appendLine()
                for (allocation in entry.value) {
                    str.append("\t $allocation")
                    str.appendLine()
                }
            }
            return str.toString()
        }

        /**
         *  Checks if the entity is using (has allocated units) the specified resource.
         */
        fun isUsing(resource: Resource): Boolean {
            return resourceAllocations.contains(resource)
        }

        /**
         *  Computes the number of different allocations of the resource held by the entity.
         */
        fun numberOfAllocations(resource: Resource): Int {
            return if (!isUsing(resource)) {
                0
            } else {
                resourceAllocations[resource]!!.size
            }
        }

        val hasAllocations: Boolean
            get() = resourceAllocations.isNotEmpty()

        /**
         * Computes the total number of units of the specified resource that are allocated
         * to the entity.
         */
        fun totalAmountAllocated(resource: Resource): Int {
            if (!isUsing(resource)) {
                return 0
            } else {
                var sum = 0
                for (allocation in resourceAllocations[resource]!!) {
                    sum = sum + allocation.amount
                }
                return sum
            }
        }

        /** Indicates to the entity that it has received an allocation from a resource
         *  This internal method is called from the Resource class.
         *
         * @param allocation the allocation created by the resource for the entity
         */
        internal fun allocate(allocation: Allocation) {
            if (!resourceAllocations.contains(allocation.resource)) {
                resourceAllocations[allocation.resource] = mutableListOf()
            }
            resourceAllocations[allocation.resource]!!.add(allocation)
        }

        /**
         * This internal method is called from the Resource class, when the allocation is
         * released on the resource.
         *
         * @param allocation the allocation to be removed (deallocated) from the entity
         */
        internal fun deallocate(allocation: Allocation) {
            resourceAllocations[allocation.resource]!!.remove(allocation)
            if (resourceAllocations[allocation.resource]!!.isEmpty()) {
                resourceAllocations.remove(allocation.resource)
            }
        }

        /**
         *  Causes the entity to release any allocations related to the supplied resource
         *  @param resource the resource to release all allocations
         */
        private fun releaseResource(resource: Resource) {
            if (isUsing(resource)) {
                val allocations: MutableList<Allocation>? = resourceAllocations[resource]
                val copies = ArrayList(allocations!!)
                for (copy in copies) {
                    resource.deallocate(copy)
                }
            }
        }

        /**
         *  Causes the entity to release all allocations for any resource that it
         *  may have
         */
        private fun releaseAllResources() {
            for (r in resourceAllocations.keys) {
                releaseResource(r)
            }
        }

        /**
         *  This function is used to define via a builder for a process for the entity.
         *
         *  Creates the coroutine and immediately suspends it.  To start executing
         *  the created coroutine use the methods for activating processes.
         *
         *  Note that by default, a process defined by this function, will automatically be
         *  added to the entity's processSequence.  If you do not want a defined process to
         *  be part of the entity's process sequence, then supply false for the addToSequence
         *  argument.
         *
         *  @param processName the name of the process
         *  @param addToSequence whether to add the process to the entity's default process sequence
         */
        protected fun process(
            processName: String? = null,
            addToSequence: Boolean = true,
            block: suspend KSLProcessBuilder.() -> Unit
        ): KSLProcess {
            val coroutine = ProcessCoroutine(processName)
            if (addToSequence) {
                processSequence.add(coroutine)
            }
            coroutine.continuation = block.createCoroutineUnintercepted(receiver = coroutine, completion = coroutine)
            return coroutine
        }

        /**
         *  If the entity is executing a process and the process is suspended, then
         *  the process is scheduled to resume at the current simulation time.
         *  @param priority the priority parameter can be used to provide an ordering to the
         *  scheduled resumption events, if multiple events are scheduled at the same time
         */
        fun resumeProcess(priority: Int = KSLEvent.DEFAULT_PRIORITY) {
            // entity must be in a process and suspended
            if (myCurrentProcess != null) {
                myResumeAction.schedule(0.0, priority = priority)
            }
        }

        /**
         *  If the entity is executing a process and the process is suspended, then
         *  the process routine is terminated. This causes the currently suspended
         *  process to exit, essentially with an error condition.  No further
         *  processing within the process will execute. The process ends (is terminated).
         *  All resources that the entity has allocated will be deallocated.  If the entity was
         *  waiting in a queue, the entity is removed from the queue and no statistics
         *  are collected on its queueing.  If the entity is experiencing a delay,
         *  then the event associated with the delay is cancelled.
         *
         *  If the entity has additional processes in
         *  its process sequence they are not automatically executed. If the user
         *  requires specific behavior to occur for the entity after termination, then
         *  the user should override the Entity's handleTerminatedProcess() function to
         *  supply specific logic.  Termination happens immediately, with no time delay.
         */
        fun terminateProcess() {
            if (myCurrentProcess != null) {
                myCurrentProcess!!.terminate()
            }
        }

        private inner class ResumeAction : EventAction<Nothing>() {
            override fun action(event: KSLEvent<Nothing>) {
                if (myCurrentProcess != null) {
                    myCurrentProcess!!.resume()
                }
            }
        }

        /**
         *  Subclasses can override this method and provide logic for the supplied
         *  process that will be executed right before the process runs to is first suspension point.
         *  For example, this method could be used to make assignments to the entity before
         *  the entity starts running the process. By default, this method does nothing.
         *
         *  @param process  This is actually the pending process. The process that will run next but has not
         *  started running
         */
        protected open fun beforeRunningProcess(process: KSLProcess) {

        }

        /**
         *  Can be used by subclasses to perform some logic when (after) the
         *  current process is completed, but before deciding to execute
         *  the next process (if there is one). By default, this method does nothing.
         *
         *  @param completedProcess  This is actually the process that just completed.
         */
        protected open fun afterRunningProcess(completedProcess: KSLProcess) {

        }

        /**
         *  If the useProcessSequence property is true, this method automatically uses
         *  the current processSequenceIterator. If the iterator has a next element it is
         *  returned, else null is returned. Subclasses may override this implementation
         *  to provide a more general approach to determining the next process to run.
         *  @param completedProcess the process just completed
         *  @return the next process to activate or null if none to activate
         */
        protected open fun determineNextProcess(completedProcess: KSLProcess): KSLProcess? {
            if (useProcessSequence) {
                if (processSequenceIterator.hasNext()) {
                    return processSequenceIterator.next()
                }
            }
            return null
        }

        /**
         *  This function is called if there are no more processes to run for the entity
         *  after successfully completing a process.  This method provides subclasses
         *  the ability to release any allocations or do clean up before the entity is
         *  passed to its EntityType for handling.  By default, this method does nothing.
         *  @param completedProcess the process just completed
         */
        protected open fun dispose(completedProcess: KSLProcess) {

        }

        /** This method is called from ProcessCoroutine to clean up the entity when
         *  the process has successfully completed (run through its last coroutine suspension point or returned).
         *
         *  Need to automatically dispose of entity at end of processes and check if it still has allocations.
         * It is an error to dispose of an entity that has allocations. Disposing an entity is not the same as ending a process.
         * An entity that has no more processes to execute cannot end its last process with allocations.
         */
        private fun afterSuccessfulProcessCompletion(completedProcess: KSLProcess) {
            logger.trace { "time = $time : entity $id completed process = $completedProcess" }
            afterRunningProcess(completedProcess)
            val np = determineNextProcess(completedProcess)
            if (np != null) {
                previousProcess = completedProcess
                logger.trace { "time = $time : entity $id to activate process = $np next" }
                activate(np)
            } else {
                // no next process to run, entity must not have any allocations
                dispose(completedProcess)
                if (hasAllocations) {
                    val msg = StringBuilder()
                    msg.append("time = $time : entity $id had allocations when ending process $completedProcess with no next process!")
                    msg.appendLine()
                    msg.append(allocationsAsString())
                    logger.error { msg.toString() }
                    throw IllegalStateException(msg.toString())
                }
                // okay to dispose of the entity
                if (autoDispose) {
                    logger.trace { "time = $time : entity $id is being disposed by ${processModel.name}" }
                    dispose(this)
                }
            }
        }

        /** This method is called from ProcessCoroutine to clean up the entity when
         *  the process has been terminated.
         *
         *  If the entity is executing a process and the process is suspended, then
         *  the process routine is terminated. This causes the currently suspended
         *  process to exit, essentially with an error condition.  No further
         *  processing within the process will execute. The process ends (is terminated).
         *  All resources that the entity has allocated will be deallocated.  If the entity was
         *  waiting in a queue, the entity is removed from the queue and no statistics
         *  are collected on its queueing.  If the entity is experiencing a delay,
         *  then the event associated with the delay is cancelled.
         *
         *  If the entity has additional processes in
         *  its process sequence they are not automatically executed. If the user
         *  requires specific behavior to occur for the entity after termination, then
         *  the user should override the Entity's handleTerminatedProcess() function to
         *  supply specific logic.
         */
        private fun afterTerminatedProcessCompletion(completedProcess: KSLProcess) {
            if (hasAllocations) {
                logger.trace { "time = $time Process $completedProcess was terminated for Entity $this releasing all resources." }
                releaseAllResources()
            }
            if (isQueued) {
                //remove it from its queue with no stats
                @Suppress("UNCHECKED_CAST")
                // since this is an entity, it must be in a HoldQueue which must hold EntityType.Entity
                val q = queue!! as Queue<ProcessModel.Entity>
                q.remove(this, false)
                logger.trace { "time = $time Process $completedProcess was terminated for Entity $this removed from queue ${q.name} ." }
            } else if (isScheduled) {
                if (myDelayEvent != null) {
                    if (myDelayEvent!!.scheduled) {
                        logger.trace { "time = $time Process $completedProcess was terminated for Entity $this delay event was cancelled." }
                        myDelayEvent?.cancelled = true
                    }
                }
            }
        }

        /**
         *  Subclasses of Entity can use this method to clean up after a process is terminated
         *  for the entity. Currently, it does nothing.
         *  @param terminatedProcess the process that was terminated.
         */
        protected open fun handleTerminatedProcess(terminatedProcess: KSLProcess) {

        }

        /**
         *  A state pattern implementation to ensure that the entity only transitions to
         *  valid states from its current state.
         */
        private abstract inner class EntityState(val name: String) {
            open fun create() {
                errorMessage("create entity")
            }

            open fun activate() {
                errorMessage("activate the entity")
            }

            open fun schedule() {
                errorMessage("schedule the entity")
            }

            open fun waitForSignal() {
                errorMessage("wait for signal the entity")
            }

            open fun holdInQueue() {
                errorMessage("hold in queue the entity")
            }

            open fun waitForResource() {
                errorMessage("wait for resource the entity")
            }

            open fun processEnded() {
                errorMessage("complete the entity")
            }

            private fun errorMessage(message: String) {
                val sb = StringBuilder()
                sb.appendLine()
                sb.append("Tried to $message ")
                sb.append(name)
                sb.append(" from an illegal state: ")
                sb.append(state.toString())
                sb.appendLine()
                sb.append(this@Entity.toString())
                logger.error { sb.toString() }
                throw IllegalStateException(sb.toString())
            }

            override fun toString(): String {
                return name
            }
        }

        private inner class CreatedState : EntityState("Created") {

            override fun schedule() {
                state = myScheduledState
            }
        }

        private inner class ProcessEndedState : EntityState("ProcessEnded") {

            override fun schedule() {
                state = myScheduledState
            }
        }

        private inner class Active : EntityState("Active") {

            override fun schedule() {
                state = myScheduledState
            }

            override fun waitForSignal() {
                state = myWaitingForSignalState
            }

            override fun holdInQueue() {
                state = myInHoldQueueState
            }

            override fun waitForResource() {
                state = myWaitingForResourceState
            }

            override fun processEnded() {
                state = myProcessEndedState
            }
        }

        private inner class Scheduled : EntityState("Scheduled") {
            override fun activate() {
                state = myActiveState
            }
        }

        private inner class WaitingForSignal : EntityState("WaitingForSignal") {
            override fun activate() {
                state = myActiveState
            }
        }

        private inner class InHoldQueue : EntityState("InHoldQueue") {
            override fun activate() {
                state = myActiveState
            }
        }

        private inner class WaitingForResource : EntityState("WaitingForResource") {
            override fun activate() {
                state = myActiveState
            }
        }

        /**
         *  The main implementation of the coroutine magic.
         */
        internal inner class ProcessCoroutine(processName: String? = null) : KSLProcessBuilder, KSLProcess,
            Continuation<Unit> {
            override val id = (++processCounter)
            override val name: String = processName ?: ("PROCESS_$id")
            internal var continuation: Continuation<Unit>? = null //set with suspending function
            override val context: CoroutineContext get() = EmptyCoroutineContext

            override var isActivated: Boolean = false
                private set
            private val created = Created()
            private val suspended = Suspended()
            private val terminated = Terminated()
            private val completed = Completed()
            private val running = Running()
            private var state: ProcessState = created
            override val isCreated: Boolean
                get() = state == created
            override val isSuspended: Boolean
                get() = state == suspended
            override val isTerminated: Boolean
                get() = state == terminated
            override val isCompleted: Boolean
                get() = state == completed
            override val isRunning: Boolean
                get() = state == running

            override val entity: Entity = this@Entity // to facilitate which entity is in the process routine

            private val delayAction = DelayAction()

            /**
             *  Used to invoke activation of a process
             */
            private val myActivationAction: ActivateAction = ActivateAction()
            private val mySeizeAction: SeizeAction = SeizeAction()

            /**
             *  Activates the process. Causes the process to be scheduled to start at the present time or some time
             *  into the future. This schedules an event
             *
             *  @param activationTime the time into the future at which the process should be activated (started) for
             *  the supplied entity
             *  @param priority used to indicate priority of activation if there are activations at the same time.
             *  Lower priority goes first.
             *  @return KSLEvent the event used to schedule the activation
             */
            internal fun activate(
                activationTime: Double = 0.0,
                priority: Int = KSLEvent.DEFAULT_PRIORITY
            ): KSLEvent<KSLProcess> {
                check(!hasPendingProcess) { "The $this process cannot be activated for the entity because the entity already has a pending process" }
                check(!hasCurrentProcess) { "The $this process cannot be activated for the entity because the entity is already running a process" }
                myPendingProcess = this
                entity.state.schedule()
                logger.trace { "time = $time : entity ${entity.id} scheduled to start process $this at time ${time + activationTime}" }
                return myActivationAction.schedule(activationTime, this, priority)
            }

            private inner class ActivateAction : EventAction<KSLProcess>() {
                override fun action(event: KSLEvent<KSLProcess>) {
                    beforeRunningProcess(myPendingProcess!!)
                    activateProcess()
                }
            }

            /**
             * This method is called when the entity's current process is activated for the
             * first time.
             */
            private fun activateProcess() {
                myPendingProcess = null
                logger.trace { "time = $time : entity ${entity.id} activating and running process, ($this)" }
                entity.state.activate()// was scheduled, now entity is active for running
                start() // starts the coroutine from the created state
            }

            /**
             *  Must be called only when coroutine is in its created state. Causes the coroutine to
             *  resume its continuation and run until its first suspension point.
             */
            private fun start() {
                state.start() // this is the coroutine state, can only start process (coroutine) from the created state
                // The coroutine is told to resume its continuation. Thus, it runs until its first suspension point.
                logger.trace { "time = $time : entity ${entity.id} has hit the first suspension point of process, ($this)" }
            }

            //TODO how to run a sub-process from within another process (coroutine)?
            // what happens if the subProcess is placed within a loop? i.e. called more than once
            private fun runSubProcess(subProcess: KSLProcess) {
                //TODO check if the process is a sub-process if so run it, if not throw an IllegalArgumentException
                val p = subProcess as ProcessCoroutine
                if (p.isCreated) {
                    // must start it
                    p.start() // coroutine run until its first suspension point
                }
                TODO("not fully implemented/tested 9-14-2022")
            }

            internal fun resume() {
                state.resume()
            }

            internal fun terminate() {
                state.terminate()
            }

            override suspend fun suspend(suspensionObserver: SuspensionObserver, suspensionName: String?) {
                currentSuspensionPoint = suspensionName
                logger.trace { "time = $time : entity ${entity.id} suspended process, ($this) using suspension observe, ${suspensionObserver.name}" }
                suspensionObserver.attach(entity)
                suspend()
                suspensionObserver.detach(entity)
                logger.trace { "time = $time : entity ${entity.id} suspended process, ($this) resumed by suspension observe, ${suspensionObserver.name}" }
            }

            /**
             *  The critical method. This method uses suspendCoroutineUninterceptedOrReturn() to capture
             *  the continuation for future resumption. Places the state of the process into the suspended state.
             */
            private suspend fun suspend() {
                logger.trace { "time = $time : entity ${entity.id} suspended process, ($this) ..." }
                state.suspend()
                return suspendCoroutineUninterceptedOrReturn<Unit> { cont ->
                    continuation = cont
                    COROUTINE_SUSPENDED
                }
            }

            override suspend fun waitFor(signal: Signal, waitPriority: Int, waitStats: Boolean, waitForName: String?) {
                currentWaitFor = waitForName
                logger.trace { "time = $time : entity ${entity.id} waiting for ${signal.name} in process, ($this)" }
                entity.state.waitForSignal()
                signal.hold(entity, waitPriority)
                suspend()
                signal.release(entity, waitStats)
                entity.state.activate()
                logger.trace { "time = $time : entity ${entity.id} released from ${signal.name} in process, ($this)" }
            }

            override suspend fun hold(queue: HoldQueue, priority: Int, holdName: String?) {
                currentHold = holdName
                logger.trace { "time = $time : entity ${entity.id} being held in ${queue.name} in process, ($this)" }
                entity.state.holdInQueue()
                queue.enqueue(entity, priority)
                suspend()
                entity.state.activate()
                logger.trace { "time = $time : entity ${entity.id} exited ${queue.name} in process, ($this)" }
            }

            override suspend fun seize(
                resource: Resource,
                amountNeeded: Int,
                seizePriority: Int,
                seizeName: String?
            ): Allocation {
                require(amountNeeded >= 1) { "The amount to allocate must be >= 1" }
                currentSeize = seizeName
                logger.trace { "time = $time : entity ${entity.id} seizing $amountNeeded units of ${resource.name} in process, ($this)" }
                resource.enqueue(entity)
                entity.state.schedule()
                mySeizeAction.schedule(0.0, priority = seizePriority)
                suspend()
                entity.state.activate()
                if (amountNeeded > resource.numAvailableUnits) {
                    // entity is already in the queue waiting for the resource, just suspend
                    logger.trace { "time = $time : entity ${entity.id} waiting for $amountNeeded units of ${resource.name} in process, ($this)" }
                    entity.state.waitForResource()
                    suspend()
                    entity.state.activate()
                }
                resource.dequeue(entity)
                logger.trace { "time = $time : entity ${entity.id} allocated $amountNeeded units of ${resource.name} in process, ($this)" }
                return resource.allocate(entity, amountNeeded, currentSeize)
            }

            override suspend fun delay(delayDuration: Double, delayPriority: Int, delayName: String?) {
                require(delayDuration >= 0.0) { "The duration of the delay must be >= 0.0 in process, ($this)" }
                require(delayDuration.isFinite()) { "The duration of the delay must be finite (cannot be infinite) in process, ($this)" }
                currentDelay = delayName
                // capture the event for possible cancellation
                entity.state.schedule()
                myDelayEvent = delayAction.schedule(delayDuration, priority = delayPriority)
                //TODO could attach the entity to the event
                logger.trace { "time = $time : entity ${entity.id} delaying for $delayDuration, suspending process, ($this) ..." }
                suspend()
                entity.state.activate()
            }

            override fun release(allocation: Allocation) {
                logger.trace { "time = $time : entity ${entity.id} releasing ${allocation.amount} units of ${allocation.resource.name} in process, ($this)" }
                allocation.resource.deallocate(allocation)
            }

            override fun release(resource: Resource) {
                val amount = entity.totalAmountAllocated(resource)
                logger.trace { "time = $time : entity ${entity.id} releasing all $amount units of ${resource.name} allocated in process, ($this)" }
                entity.releaseResource(resource)
            }

            override fun releaseAllResources() {
                logger.trace { "time = $time : entity ${entity.id} releasing all units of every allocated resource in process, ($this)" }
                entity.releaseAllResources()
            }

            override fun resumeWith(result: Result<Unit>) {
                // Resumes the execution of the corresponding coroutine passing a successful or failed result
                // as the return value of the last suspension point.
                logger.trace { "time = $time The coroutine process ${this@ProcessCoroutine} completed with result = $result" }
                result.onSuccess {
                    state.complete()
                    afterSuccessfulProcessCompletion(this)
                }.onFailure {
                    if (it is ProcessTerminatedException) {
                        afterTerminatedProcessCompletion(this)
                        handleTerminatedProcess(this)
                    } else {
                        // some other exception, rethrow it
                        throw it
                    }
                }
            }

            override fun toString(): String {
                return "Process(id=$id, name='$name', state = ${state.processStateName})"
            }

            private inner class DelayAction : EventAction<Nothing>() {
                override fun action(event: KSLEvent<Nothing>) {
                    logger.trace { "time = $time : entity ${entity.id} exiting delay, resuming process, (${this@ProcessCoroutine}) ..." }
                    resume()
                }

            }

            private inner class SeizeAction : EventAction<Nothing>() {
                override fun action(event: KSLEvent<Nothing>) {
                    resume()
                }

            }

            private abstract inner class ProcessState(val processStateName: String) {

                open fun start() {
                    errorMessage("start process")
                }

                open fun suspend() {
                    errorMessage("suspend process")
                }

                open fun resume() {
                    errorMessage("resume process")
                }

                open fun terminate() {
                    errorMessage("terminate process")
                }

                open fun complete() {
                    errorMessage("complete process")
                }

                private fun errorMessage(routineName: String) {
                    val sb = StringBuilder()
                    sb.appendLine()
                    sb.append("Tried to $routineName  ")
                    sb.append("${this@ProcessCoroutine}")
                    sb.append(" from an illegal state: ")
                    sb.append(state.processStateName)
                    sb.appendLine()
                    sb.append("for Entity: ${this@Entity}")
                    logger.error { sb.toString() }
                    throw IllegalStateException(sb.toString())
                }
            }

            private inner class Created : ProcessState("Created") {
                override fun start() {
                    myCurrentProcess = this@ProcessCoroutine
                    isActivated = true
                    state = running
                    // this starts the coroutine for the first time, because I used createCoroutineUnintercepted()
                    continuation?.resume(Unit)
                }
            }

            private inner class Running : ProcessState("Running") {
                override fun suspend() {
                    //capture suspended entities here
                    suspendedEntities.add(entity)
                    state = suspended
                }

                override fun complete() {
                    state = completed
                }
            }

            private inner class Suspended : ProcessState("Suspended") {
                override fun resume() {
                    state = running
                    //un-capture suspended entities here
                    suspendedEntities.remove(entity)
                    logger.trace { "time = $time : entity ${entity.id} resumed process, (${this@ProcessCoroutine}) ..." }
                    continuation?.resume(Unit)
                }

                override fun terminate() {
                    state = terminated
                    logger.trace { "time = $time : entity ${entity.id} terminated process, (${this@ProcessCoroutine}) ..." }
                    //resume with exception
//                    continuation?.resumeWith(Result.failure(ProcessTerminatedException())) // same as below
                    continuation?.resumeWithException(ProcessTerminatedException())
                }
            }

            private inner class Terminated : ProcessState("Terminated")

            private inner class Completed : ProcessState("Completed")
        }

    }

    companion object : KLoggable {
        override val logger = logger()
    }
}