/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.modeling.entity

import ksl.modeling.elements.EventGenerator
import ksl.modeling.queue.Queue
import ksl.modeling.spatial.*
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.GetValueIfc
import ksl.utilities.IdentityIfc
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
 * See **[ksl.modeling.entity.KSLProcessBuilder]** for documentation on the functionality provided for processes.
 *
 * @param parent the parent model element
 * @param name an optional name for the process model
 */
open class ProcessModel(parent: ModelElement, name: String? = null) : ModelElement(parent, name) {

    private val suspendedEntities = mutableSetOf<Entity>()

    /** Note that an EntityGenerator relies on the entity having at least one process
     * that has been added to its process sequence via the process() method's addToSequence
     * parameter being true, which is the default. The generator will create the entity and
     * start the process that is listed first in its process sequence.  If there are no
     * processes in the sequence then nothing happens.
     *
     * @param entityCreator the thing that creates the entities of the particular type. Typically,
     * a reference to the constructor of the class
     * @param timeUntilTheFirstEntity the time until the first entity creation
     * @param timeBtwEvents the time between entity creation
     * @param maxNumberOfEvents the maximum number of entities to create
     * @param timeOfTheLastEvent the time of the last entity creation
     * @param name a name for the generator
     */
    protected inner class EntityGenerator<T : Entity>(
        private val entityCreator: () -> T,
        timeUntilTheFirstEntity: RandomIfc = ConstantRV.ZERO,
        timeBtwEvents: RandomIfc = ConstantRV.POSITIVE_INFINITY,
        maxNumberOfEvents: Long = Long.MAX_VALUE,
        timeOfTheLastEvent: Double = Double.POSITIVE_INFINITY,
        var activationPriority: Int = KSLEvent.DEFAULT_PRIORITY + 1,
        name: String? = null
    ) : EventGenerator(
        this@ProcessModel, null, timeUntilTheFirstEntity,
        timeBtwEvents, maxNumberOfEvents, timeOfTheLastEvent, name
    ) {
        override fun generate() {
            val entity = entityCreator()
            val event: KSLEvent<KSLProcess>? = startProcessSequence(entity, priority = activationPriority)
            if (event == null) {
                logger.warn { "The $entity does not have any processes on its process sequence!" }
            }
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
            val event = activate(entity.processSequenceIterator.next(), timeUntilActivation, priority)
            event.entity = entity
            return event
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
        val event = c.activate(timeUntilActivation, priority)
        event.entity = c.entity
        return event
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

    override fun afterReplication() {
        // make a copy of the set
        val set = suspendedEntities.toHashSet()
        Model.logger.info { "After Replication for $this.name: terminating ${set.size} suspended entities" }
        for (entity in set) {
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
    open inner class Entity(aName: String? = null) : QObject(aName),
        SpatialElementIfc by SpatialElement(this@ProcessModel), VelocityIfc {

        /**
         * The default velocity for the entity's movement within the spatial model
         * of its ProcessModel
         */
        override var velocity: GetValueIfc = this@ProcessModel.spatialModel.defaultVelocity

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

//        var defaultFailureActions: ResourceFailureActionsIfc = DefaultFailureActions()

        private var processCounter = 0
        val processModel = this@ProcessModel
        private val myCreatedState = CreatedState()
        private val myScheduledState = Scheduled()
        private val myWaitingForSignalState = WaitingForSignal()
        private val myInHoldQueueState = InHoldQueue()
        private val myActiveState = Active()
        private val myWaitingForResourceState = WaitingForResource()
        private val myWaitingForConveyorState = WaitingForConveyor()
        private val myProcessEndedState = ProcessEndedState()
        private val myBlockedReceivingState = BlockedReceiving()
        private val myBlockedSendingState = BlockedSending()
        private val myWaitForProcessState = WaitForProcess()
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
        val isWaitingForConveyor: Boolean
            get() = state == myWaitingForConveyorState
        val isProcessEnded: Boolean
            get() = state == myProcessEndedState
        val isBlockedSending: Boolean
            get() = state == myBlockedSendingState
        val isBlockedReceiving: Boolean
            get() = state == myBlockedReceivingState
        val isWaitingForProcess: Boolean
            get() = state == myWaitForProcessState

        /**
         * If the entity is in a HoldQueue return the queue
         */
        fun holdQueue(): HoldQueue? {
            return if (isInHoldQueue) {
                when (this.queue) {
                    null -> {
                        null
                    }

                    is HoldQueue -> {
                        this.queue as HoldQueue
                    }

                    else -> {
                        null
                    }
                }
            } else {
                null
            }
        }

        val isSuspended: Boolean
            get() {
                if (myCurrentProcess != null) {
                    return myCurrentProcess!!.isSuspended
                }
                return isScheduled || isWaitingForSignal || isInHoldQueue || isWaitingForResource
                        || isBlockedSending || isBlockedReceiving || isWaitingForProcess
            }

        var currentSuspendName: String? = null
            private set
        var currentSuspendType: SuspendType = SuspendType.NONE
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
         * Facilitates the creation of requests for the entity by clients that
         * have access to a reference to the entity or via the entity itself.
         *
         * @param amountNeeded the amount needed to fill the request
         * @return the constructed Request
         */
        fun createRequest(amountNeeded: Int = 1, resource: Resource): Request {
            val request = Request(amountNeeded)
            request.resource = resource
            return request
        }

        /**
         * Facilitates the creation of requests for the entity by clients that
         * have access to a reference to the entity or via the entity itself.
         *
         * @param amountNeeded the amount needed to fill the request
         * @return the constructed Request
         */
        fun createRequest(amountNeeded: Int = 1, resourcePool: ResourcePool): Request {
            val request = Request(amountNeeded)
            request.resourcePool = resourcePool
            return request
        }

        /**
         *  Represents some amount of units needed from 1 or more resources
         *
         * @param amountNeeded the amount needed to fill the request
         */
        inner class Request(amountNeeded: Int = 1) : QObject() {
            init {
                require(amountNeeded >= 1) { "The amount needed for the request must be >= 1" }
            }

            var resource: Resource? = null
                internal set
            var resourcePool: ResourcePool? = null
                internal set
            val entity = this@Entity
            val amountRequested = amountNeeded
        }

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
            return if (!isUsing(resource)) {
                0
            } else {
                var sum = 0
                for (allocation in resourceAllocations[resource]!!) {
                    sum = sum + allocation.amount
                }
                sum
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

        var conveyorRequest: ConveyorRequestIfc? = null
            private set

        /**
         *  This function is used to define via a builder for a process for the entity.
         *
         *  Creates the coroutine and immediately suspends it.  To start executing
         *  the created coroutine use the methods for activating processes.
         *
         *  Note that by default, a process defined by this function, will be
         *  added automatically to the entity's processSequence.  If you want a defined process to
         *  not be part of the entity's process sequence, then set the addToSequence argument to false.
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
         *  @param timeUntilResumption the time until the resumption will occur
         *  scheduled resumption events, if multiple events are scheduled at the same time
         */
        fun resumeProcess(timeUntilResumption: Double = 0.0, priority: Int = KSLEvent.DEFAULT_PRIORITY) {
            // entity must be in a process and suspended
            if (myCurrentProcess != null) {
                myResumeAction.schedule(timeUntilResumption, priority = priority)
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
         *
         *  @param afterTermination a function to invoke after the process is successfully terminated
         */
        fun terminateProcess(afterTermination: ((entity: ProcessModel.Entity) -> Unit)? = null) {
            if (myCurrentProcess != null) {
                myCurrentProcess!!.terminate(afterTermination)
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
            logger.trace { "$time > entity $id completed process = $completedProcess" }
            afterRunningProcess(completedProcess)
            val np = determineNextProcess(completedProcess)
            if (np != null) {
                previousProcess = completedProcess
                logger.trace { "$time > entity $id to activate process = $np next" }
                activate(np)
            } else {
                // no next process to run, entity must not have any allocations
                dispose(completedProcess)
                if (hasAllocations) {
                    val msg = StringBuilder()
                    msg.append("$time > entity $id had allocations when ending process $completedProcess with no next process!")
                    msg.appendLine()
                    msg.append(allocationsAsString())
                    logger.error { msg.toString() }
                    throw IllegalStateException(msg.toString())
                }
                // okay to dispose of the entity
                if (autoDispose) {
                    logger.trace { "$time > entity $id is being disposed by ${processModel.name}" }
                    dispose(this)
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

        internal fun resourceBecameInactiveWhileWaitingInQueueWithSeizeRequestInternal(
            requestQ: RequestQ,
            resourceWithQ: ResourceWithQ,
            request: ProcessModel.Entity.Request
        ) {
            resourceBecameInactiveWhileWaitingInQueueWithSeizeRequest(requestQ, resourceWithQ, request)
        }

        /**
         * Subclasses of entity can override this method to provide behavior if a request associated
         * with the entity has its requested resource become inactive while its request
         * was waiting in the request queue.  This is not a trivial thing to do since the entity
         * will be suspended after seizing a resource.  If the entity wants to stop waiting, then
         * the process will have to be terminated and the termination logic will need to handle
         * what to do after the termination.
         *
         * @param queue the queue holding the request
         * @param resource the involved resource
         * @param request the involved request
         */
        protected open fun resourceBecameInactiveWhileWaitingInQueueWithSeizeRequest(
            queue: RequestQ, resource: ResourceWithQ, request: ProcessModel.Entity.Request
        ) {
        }

        /**
         *  A state pattern implementation to ensure that the entity only transitions to
         *  valid states from its current state.
         */
        private abstract inner class EntityState(val name: String) {
            open fun create() {
                errorMessage("create the entity")
            }

            open fun activate() {
                errorMessage("activate the entity")
            }

            open fun schedule() {
                errorMessage("schedule the entity")
            }

            open fun waitForSignal() {
                errorMessage("wait for signal for the entity")
            }

            open fun holdInQueue() {
                errorMessage("hold in queue for the entity")
            }

            open fun waitForResource() {
                errorMessage("wait for resource for the entity")
            }

            open fun waitForConveyor() {
                errorMessage("wait for resource for the entity")
            }

            open fun processEnded() {
                errorMessage("end process of the entity")
            }

            open fun blockedSending() {
                errorMessage("block sending the entity")
            }

            open fun blockedReceiving() {
                errorMessage("block receiving the entity")
            }

            open fun waitForProcess() {
                errorMessage("wait for process for the entity")
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

            override fun waitForConveyor() {
                state = myWaitingForConveyorState
            }

            override fun processEnded() {
                state = myProcessEndedState
            }

            override fun blockedSending() {
                state = myBlockedSendingState
            }

            override fun blockedReceiving() {
                state = myBlockedReceivingState
            }

            override fun waitForProcess() {
                state = myWaitForProcessState
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

        private inner class WaitingForConveyor : EntityState("WaitingForConveyor") {
            override fun activate() {
                state = myActiveState
            }
        }

        private inner class BlockedSending : EntityState("BlockedSending") {
            override fun activate() {
                state = myActiveState
            }
        }

        private inner class BlockedReceiving : EntityState("BlockedReceiving") {
            override fun activate() {
                state = myActiveState
            }
        }

        private inner class WaitForProcess : EntityState("WaitForProcess") {
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

            internal var callingProcess: ProcessCoroutine? = null
            internal var calledProcess: ProcessCoroutine? = null

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

            /**
             *  Activates the process. Causes the process to be scheduled to start at the present time or some time
             *  into the future. This schedules an event
             *
             *  @param timeUntilActivation the time into the future at which the process should be activated (started) for
             *  the supplied entity
             *  @param priority used to indicate priority of activation if there are activations at the same time.
             *  Lower priority goes first.
             *  @return KSLEvent the event used to schedule the activation
             */
            internal fun activate(
                timeUntilActivation: Double = 0.0,
                priority: Int = KSLEvent.DEFAULT_PRIORITY
            ): KSLEvent<KSLProcess> {
                check(!hasPendingProcess) { "The $this process cannot be activated for the entity because the entity already has a pending process" }
                check(!hasCurrentProcess) { "The $this process cannot be activated for the entity because the entity is already running a process" }
                myPendingProcess = this
                entity.state.schedule()
                logger.trace { "$time > entity ${entity.id} scheduled to start process $this at time ${time + timeUntilActivation}" }
                return myActivationAction.schedule(timeUntilActivation, this, priority)
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
                logger.trace { "$time > entity ${entity.id} activating and running process, ($this)" }
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
                logger.trace { "$time > entity ${entity.id} has hit the first suspension point of process, ($this)" }
            }

//            //TODO how to run a sub-process from within another process (coroutine)?
//            // what happens if the subProcess is placed within a loop? i.e. called more than once
//            private fun runSubProcess(subProcess: KSLProcess) {
//                //TODO check if the process is a sub-process if so run it, if not throw an IllegalArgumentException
//                val p = subProcess as ProcessCoroutine
//                if (p.isCreated) {
//                    // must start it
//                    p.start() // coroutine run until its first suspension point
//                }
//                TODO("not fully implemented/tested 9-14-2022")
//            }

            internal fun resume() {
                state.resume()
            }

            /**
             * @param afterTermination a function to invoke after the process is successfully terminated
             */
            internal fun terminate(afterTermination: ((entity: ProcessModel.Entity) -> Unit)? = null) {
                state.terminate(afterTermination)
            }

            override suspend fun suspend(suspensionObserver: SuspensionObserver, suspensionName: String?) {
                currentSuspendName = suspensionName
                currentSuspendType = SuspendType.SUSPEND
                logger.trace { "$time > entity ${entity.id} suspended process, ($this) using suspension observe, ${suspensionObserver.name}" }
                suspensionObserver.attach(entity)
                suspend()
                suspensionObserver.detach(entity)
                logger.trace { "$time > entity ${entity.id} suspended process, ($this) resumed by suspension observe, ${suspensionObserver.name}" }
                currentSuspendName = null
                currentSuspendType = SuspendType.NONE
            }

            /**
             *  The critical method. This method uses suspendCoroutineUninterceptedOrReturn() to capture
             *  the continuation for future resumption. Places the state of the process into the suspended state.
             */
            private suspend fun suspend() {
                logger.trace { "$time > entity ${entity.id} suspending process, ($this) ..." }
                state.suspend()
                logger.trace { "$time > entity ${entity.id} suspended process, ($this) ..." }
                return suspendCoroutineUninterceptedOrReturn<Unit> { cont ->
                    continuation = cont
                    COROUTINE_SUSPENDED
                }
            }

            override suspend fun waitFor(
                process: KSLProcess,
                timeUntilActivation: Double,
                priority: Int,
                suspensionName: String?
            ) {
                require(currentProcess != process) { "The process ${process.name} is the same as the current process! " }
                val p = process as ProcessCoroutine
                require(p.callingProcess == null) { "The process to wait on already has a calling process" }
                p.callingProcess = this
                calledProcess = p
                currentSuspendName = suspensionName
                currentSuspendType = SuspendType.WAIT_FOR_PROCESS
                logger.trace { "$time > entity ${entity.id} waiting for ${process.name} in process, ($this)" }
                p.activate(timeUntilActivation, priority)
                entity.state.waitForProcess()
                suspend()
                calledProcess = null
                entity.state.activate()
                logger.trace { "$time > entity ${entity.id} ended wait for ${process.name} in process, ($this)" }
                currentSuspendName = null
                currentSuspendType = SuspendType.NONE
            }

            override suspend fun waitFor(
                signal: Signal,
                waitPriority: Int,
                waitStats: Boolean,
                suspensionName: String?
            ) {
                currentSuspendName = suspensionName
                currentSuspendType = SuspendType.WAIT_FOR_SIGNAL
                logger.trace { "$time > entity ${entity.id} waiting for ${signal.name} in process, ($this)" }
                entity.state.waitForSignal()
                signal.hold(entity, waitPriority)
                suspend()
                signal.release(entity, waitStats)
                entity.state.activate()
                logger.trace { "$time > entity ${entity.id} released from ${signal.name} in process, ($this)" }
                currentSuspendName = null
                currentSuspendType = SuspendType.NONE
            }

            override suspend fun hold(queue: HoldQueue, priority: Int, suspensionName: String?) {
                currentSuspendName = suspensionName
                currentSuspendType = SuspendType.HOLD
                logger.trace { "$time > entity ${entity.id} being held in ${queue.name} in process, ($this)" }
                entity.state.holdInQueue()
                queue.enqueue(entity, priority)
                suspend()
                entity.state.activate()
                logger.trace { "$time > entity ${entity.id} exited ${queue.name} in process, ($this)" }
                currentSuspendName = null
                currentSuspendType = SuspendType.NONE
            }

            override suspend fun <T : QObject> waitForItems(
                blockingQ: BlockingQueue<T>,
                amount: Int,
                predicate: (T) -> Boolean,
                blockingPriority: Int,
                suspensionName: String?
            ): List<T> {
                currentSuspendName = suspensionName
                currentSuspendType = SuspendType.WAIT_FOR_ITEMS
                val request = blockingQ.requestItems(entity, predicate, amount, blockingPriority)
                return blockingQWait(blockingQ, request)
            }

            private suspend fun <T : QObject> blockingQWait(
                blockingQ: BlockingQueue<T>,
                request: BlockingQueue<T>.ChannelRequest
            ): List<T> {
                if (request.canNotBeFilled) {
                    // must wait until it can be filled
                    logger.trace { "$time > entity ${entity.id} blocked receiving to ${blockingQ.name} in process, ($this)" }
                    entity.state.blockedReceiving()
                    suspend()
                    entity.state.activate()
                    logger.trace { "$time > entity ${entity.id} unblocked receiving to ${blockingQ.name} in process, ($this)" }
                }
                // the request should be able to be filled
                val list = blockingQ.fill(request)// this also removes request from queue
                currentSuspendName = null
                currentSuspendType = SuspendType.NONE
                return list
            }

            override suspend fun <T : QObject> waitForAnyItems(
                blockingQ: BlockingQueue<T>,
                predicate: (T) -> Boolean,
                blockingPriority: Int,
                suspensionName: String?
            ): List<T> {
                currentSuspendName = suspensionName
                currentSuspendType = SuspendType.WAIT_FOR_ANY_ITEMS
                val request = blockingQ.requestItems(entity, predicate, blockingPriority)
                return blockingQWait(blockingQ, request)
            }

            override suspend fun <T : QObject> send(
                item: T,
                blockingQ: BlockingQueue<T>,
                blockingPriority: Int,
                suspensionName: String?
            ) {
                currentSuspendName = suspensionName
                currentSuspendType = SuspendType.SEND
                // always enqueue to capture wait statistics of those that do not wait
                blockingQ.enqueueSender(entity, blockingPriority)
                if (blockingQ.isFull) {
                    logger.trace { "$time > entity ${entity.id} blocked sending to ${blockingQ.name} in process, ($this)" }
                    entity.state.blockedSending()
                    suspend()
                    entity.state.activate()
                    logger.trace { "$time > entity ${entity.id} unblocked sending to ${blockingQ.name} in process, ($this)" }
                }
                blockingQ.dequeSender(entity)
                logger.trace { "$time > entity ${entity.id} sending ${item.name} to ${blockingQ.name} in process, ($this)" }
                blockingQ.sendToChannel(item)
                currentSuspendName = null
                currentSuspendType = SuspendType.NONE
            }

            override suspend fun seize(
                resource: Resource,
                amountNeeded: Int,
                seizePriority: Int,
                queue: RequestQ,
                suspensionName: String?
            ): Allocation {
                require(amountNeeded >= 1) { "The amount to allocate must be >= 1" }
                currentSuspendName = suspensionName
                currentSuspendType = SuspendType.SEIZE
                logger.trace { "$time > entity ${entity.id} SEIZE $amountNeeded units of ${resource.name} in process, ($this)" }
                delay(0.0, seizePriority, "$suspensionName:SeizeDelay")
                //create the request based on the current resource state
                val request = createRequest(amountNeeded, resource)
                request.priority = entity.priority
                queue.enqueue(request) // put the request in the queue
                if (!resource.canAllocate(request.amountRequested)) {
                    // it must wait, request is already in the queue waiting for the resource, just suspend the entity's process
                    logger.trace { "$time > entity ${entity.id} waiting for $amountNeeded units of ${resource.name} in process, ($this)" }
                    entity.state.waitForResource()
                    suspend()
                    entity.state.activate()
                }
                // entity has been told to resume
                queue.remove(request) // take the request out of the queue after possible wait
                logger.trace { "$time > entity ${entity.id} allocated $amountNeeded units of ${resource.name} in process, ($this)" }
                currentSuspendName = null
                currentSuspendType = SuspendType.NONE
                return resource.allocate(entity, amountNeeded, queue, suspensionName)
            }

            override suspend fun seize(
                resourcePool: ResourcePool,
                amountNeeded: Int,
                seizePriority: Int,
                queue: RequestQ,
                suspensionName: String?
            ): ResourcePoolAllocation {
                require(amountNeeded >= 1) { "The amount to allocate must be >= 1" }
                currentSuspendName = suspensionName
                currentSuspendType = SuspendType.SEIZE
                logger.trace { "$time > entity ${entity.id} SEIZE $amountNeeded units of ${resourcePool.name} in process, ($this)" }
                delay(0.0, seizePriority, "$suspensionName:SeizeDelay")
                //create the request based on the current resource state
                val request = createRequest(amountNeeded, resourcePool)
                request.priority = entity.priority
                queue.enqueue(request) // put the request in the queue
                if (!resourcePool.canAllocate(request.amountRequested)) {
                    // it must wait, request is already in the queue waiting for the resource, just suspend the entity's process
                    logger.trace { "$time > entity ${entity.id} waiting for $amountNeeded units of ${resourcePool.name} in process, ($this)" }
                    entity.state.waitForResource()
                    suspend()
                    entity.state.activate()
                }
                // entity has been told to resume
                queue.remove(request) // take the request out of the queue after possible wait
                logger.trace { "$time > entity ${entity.id} allocated $amountNeeded units of ${resourcePool.name} in process, ($this)" }
                currentSuspendName = null
                currentSuspendType = SuspendType.NONE
                // make the pooled allocation and return it
                return resourcePool.allocate(entity, amountNeeded, queue, suspensionName)
            }

            override suspend fun delay(delayDuration: Double, delayPriority: Int, suspensionName: String?) {
                require(delayDuration >= 0.0) { "The duration of the delay must be >= 0.0 in process, ($this)" }
                require(delayDuration.isFinite()) { "The duration of the delay must be finite (cannot be infinite) in process, ($this)" }
                currentSuspendName = suspensionName
                currentSuspendType = SuspendType.DELAY
                // capture the event for possible cancellation
                entity.state.schedule()
                val eName = "Delay Event: duration = $delayDuration suspension name = $suspensionName"
                myDelayEvent = delayAction.schedule(delayDuration, priority = delayPriority, name = eName)
                myDelayEvent!!.entity = entity
                logger.trace { "$time > entity ${entity.id} DELAY for $delayDuration, suspending process, ($this) ..." }
                suspend()
                entity.state.activate()
                currentSuspendName = null
                currentSuspendType = SuspendType.NONE
            }

            override suspend fun move(
                fromLoc: LocationIfc,
                toLoc: LocationIfc,
                velocity: Double,
                movePriority: Int,
                suspensionName: String?
            ) {
                require(!isMoving) { "The entity ${entity.id} is already moving" }
                require(velocity > 0.0) { "The velocity of the movement must be > 0.0 in process, ($this)" }
                if (currentLocation != fromLoc) {
                    currentLocation = fromLoc
                }
                val d = fromLoc.distanceTo(toLoc)
                val t = d / velocity
                logger.trace { "$time > entity ${entity.id} MOVING from ${fromLoc.name} to ${toLoc.name} suspending process, ($this) ..." }
                isMoving = true
                delay(t, movePriority, suspensionName)
                currentLocation = toLoc
                isMoving = false
                logger.trace { "$time > entity ${entity.id} completed move from ${fromLoc.name} to ${toLoc.name}" }
            }

            override suspend fun move(
                spatialElement: SpatialElementIfc,
                toLoc: LocationIfc,
                velocity: Double,
                movePriority: Int,
                suspensionName: String?
            ) {
                require(!spatialElement.isMoving) { "Spatial element ${spatialElement.spatialName} is already moving!" }
                require(velocity > 0.0) { "The velocity of the movement must be > 0.0 in process, ($this)" }
                val d = spatialElement.currentLocation.distanceTo(toLoc)
                val t = d / velocity
                logger.trace { "$time > entity ${entity.id} is moving ${spatialElement.spatialName} from ${spatialElement.currentLocation.name} to ${toLoc.name} suspending process, ($this) ..." }
                spatialElement.isMoving = true
                delay(t, movePriority, suspensionName)
                spatialElement.currentLocation = toLoc
                spatialElement.isMoving = false
                logger.trace { "$time > spatial element ${spatialElement.spatialName} completed move to ${toLoc.name}" }
            }

            override suspend fun moveWith(
                spatialElement: SpatialElementIfc,
                toLoc: LocationIfc,
                velocity: Double,
                movePriority: Int,
                suspensionName: String?
            ) {
                require(!isMoving) { "The entity ${entity.id} is already moving" }
                require(currentLocation.isLocationEqualTo(spatialElement.currentLocation)) { "The location of the entity and the spatial element must be the same" }
                isMoving = true
                move(spatialElement, toLoc, velocity, movePriority, suspensionName)
                isMoving = false
                currentLocation = toLoc
            }

            override suspend fun moveWith(
                movableResource: MovableResource,
                toLoc: LocationIfc,
                velocity: Double,
                movePriority: Int,
                suspensionName: String?
            ) {
                require(entity.isUsing(movableResource)) { "The entity is not using the movable resource. Thus, it cannot move with it." }
                movableResource.isTransporting = true
                moveWith(movableResource as SpatialElementIfc, toLoc, velocity, movePriority, suspensionName)
                movableResource.isTransporting = false
            }

            override suspend fun moveWith(
                movableResourceWithQ: MovableResourceWithQ,
                toLoc: LocationIfc,
                velocity: Double,
                movePriority: Int,
                suspensionName: String?
            ) {
                require(entity.isUsing(movableResourceWithQ)) { "The entity is not using the movable resource. Thus, it cannot move with it." }
                movableResourceWithQ.isTransporting = true
                moveWith(movableResourceWithQ as SpatialElementIfc, toLoc, velocity, movePriority, suspensionName)
                movableResourceWithQ.isTransporting = false
            }

            override fun release(allocation: Allocation, releasePriority: Int) {
                logger.trace { "$time > entity ${entity.id} RELEASE ${allocation.amount} units of ${allocation.resource.name} in process, ($this)" }
                // we cannot assume that a resource has a queue
                allocation.resource.deallocate(allocation)
                // get the queue from the allocation being released and process any waiting requests
                // note that the released amount may allow multiple requests to proceed
                // this may be a problem depending on how numAvailableUnits is defined
                if (!executive.isEnded) {
                    allocation.queue.processWaitingRequests(allocation.resource.numAvailableUnits, releasePriority)
                }
            }

            override fun release(resource: Resource, releasePriority: Int) {
                logger.trace { "$time > entity ${entity.id} releasing all ${entity.totalAmountAllocated(resource)} units of ${resource.name} allocated in process, ($this)" }
                // get the allocations of this entity for this resource
                val list = resource.allocations(entity)
                for (allocation in list) {
                    release(allocation, releasePriority)
                }
            }

            override fun releaseAllResources(releasePriority: Int) {
                logger.trace { "$time > entity ${entity.id} releasing all units of every allocated resource in process, ($this)" }
                val rList = resourceAllocations.keys.toList()
                for (r in rList) {
                    release(r, releasePriority)
                }
            }

            override fun release(pooledAllocation: ResourcePoolAllocation, releasePriority: Int) {
                logger.trace { "$time > entity ${entity.id} releasing ${pooledAllocation.amount} units of ${pooledAllocation.resourcePool.name} in process, ($this)" }
                // ask the resource pool to deallocate the resources
                pooledAllocation.resourcePool.deallocate(pooledAllocation)
                // then check the queue for additional work
                // get the queue from the allocation being released
                pooledAllocation.queue.processWaitingRequests(
                    pooledAllocation.resourcePool.numAvailableUnits,
                    releasePriority
                )
            }

            override suspend fun interruptDelay(
                process: KSLProcess,
                delayName: String,
                interruptTime: Double,
                interruptPriority: Int,
                postInterruptDelayTime: Double
            ) {
                require(this != process) { "A process cannot interrupt itself!" }
                require(interruptTime >= 0.0) { "The interrupt time must be >= 0.0" }
                require(postInterruptDelayTime >= 0.0) { "The post interrupt delay time must be >= 0.0" }
                if (!process.entity.isScheduled) {
                    return
                }
                if (delayName != process.entity.currentSuspendName) {
                    return
                }
                val delayEvent = process.entity.myDelayEvent ?: return
                // the process is experiencing the named delay
                delayEvent.cancel = true
                delay(interruptTime, interruptPriority)
                process.entity.resumeProcess(postInterruptDelayTime, delayEvent.priority)
            }

            override suspend fun interruptDelayAndRestart(
                process: KSLProcess,
                delayName: String,
                interruptTime: Double,
                interruptPriority: Int
            ) {
                val delayEvent = process.entity.myDelayEvent ?: return
                interruptDelay(process, delayName, interruptTime, interruptPriority, delayEvent.interEventTime)
            }

            override suspend fun interruptDelayAndContinue(
                process: KSLProcess,
                delayName: String,
                interruptTime: Double,
                interruptPriority: Int
            ) {
                val delayEvent = process.entity.myDelayEvent ?: return
                interruptDelay(process, delayName, interruptTime, interruptPriority, delayEvent.timeRemaining)
            }

            override suspend fun interruptDelayWithProcess(
                process: KSLProcess,
                delayName: String,
                interruptingProcess: KSLProcess,
                interruptPriority: Int,
                postInterruptDelayTime: Double
            ) {
                require(this != process) { "A process cannot interrupt itself!" }
                require(this != interruptingProcess) { "This process cannot be used as the interrupting process" }
                require(interruptingProcess != process) { "The interrupting process cannot be the same as the process being interrupted" }
                require(postInterruptDelayTime >= 0.0) { "The post interrupt delay time must be >= 0.0" }
                if (!process.entity.isScheduled) {
                    return
                }
                if (delayName != process.entity.currentSuspendName) {
                    return
                }
                val delayEvent = process.entity.myDelayEvent ?: return
                // the process is experiencing the named delay
                delayEvent.cancel = true
                waitFor(interruptingProcess, priority = interruptPriority)
                process.entity.resumeProcess(postInterruptDelayTime, delayEvent.priority)
            }

            override suspend fun interruptDelayWithProcessAndRestart(
                process: KSLProcess,
                delayName: String,
                interruptingProcess: KSLProcess,
                interruptPriority: Int
            ) {
                val delayEvent = process.entity.myDelayEvent ?: return
                interruptDelayWithProcess(
                    process,
                    delayName,
                    interruptingProcess,
                    interruptPriority,
                    delayEvent.interEventTime
                )
            }

            override suspend fun interruptDelayWithProcessAndContinue(
                process: KSLProcess,
                delayName: String,
                interruptingProcess: KSLProcess,
                interruptPriority: Int
            ) {
                val delayEvent = process.entity.myDelayEvent ?: return
                interruptDelayWithProcess(
                    process,
                    delayName,
                    interruptingProcess,
                    interruptPriority,
                    delayEvent.timeRemaining
                )
            }

            //TODO conveyor process commands

            override suspend fun requestConveyor(
                conveyor: Conveyor,
                entryLocation: IdentityIfc,
                numCellsNeeded: Int,
                requestPriority: Int,
                requestResumePriority: Int,
                suspensionName: String?
            ): ConveyorRequestIfc {
                require(entity.conveyorRequest == null) {
                    "Attempted to access ${conveyor.name} when already allocated to conveyor: ${entity.conveyorRequest?.conveyor?.name}." +
                            "An entity can access only one conveyor at a time. Use exit() to stop accessing a conveyor."
                }
                require(conveyor.entryLocations.contains(entryLocation)) { "The location (${entryLocation.name}) " +
                        "is not an entry location for (${conveyor.name})" }
                require(numCellsNeeded >= 1) { "The amount of cells to allocate must be >= 1" }
                require(numCellsNeeded <= conveyor.maxEntityCellsAllowed) {
                    "The entity requested more cells ($numCellsNeeded) than " +
                            "the allowed maximum (${conveyor.maxEntityCellsAllowed}) for for conveyor (${conveyor.name}"
                }
                currentSuspendName = suspensionName
                currentSuspendType = SuspendType.ACCESS
                logger.info { "$time > entity (${entity.name}) ACCESSING $numCellsNeeded cells of ${conveyor.name} in process, ($this)" }
                delay(0.0, requestPriority, "$suspensionName:AccessDelay")
                // make the conveyor request
                val request = conveyor.requestConveyor(entity, numCellsNeeded, entryLocation, requestResumePriority)
                // always enter the queue to get statistics on waiting to enter the conveyor
                conveyor.enqueueRequest(request)
                if (request.mustWait()) {
                    // entry is not possible at this time, the entity will suspend
                    logger.info { "$time > entity (${entity.name}) waiting for $numCellsNeeded cells of ${conveyor.name} in process, ($this)" }
                    entity.state.waitForConveyor()
                    suspend()
                    entity.state.activate()
                }
                //TODO where/when do we check for the resumption
                // entry is now possible, deque the request from waiting and control entry into the conveyor
                conveyor.dequeueRequest(request)
                currentSuspendName = null
                currentSuspendType = SuspendType.NONE
                // ensure that the entity remembers that it is now "using" the conveyor
                entity.conveyorRequest = request
                // cause the request to block the entry location
                request.blockEntryLocation()
                logger.info { "$time > entity (${entity.name}) has blocked the entry cell of ${conveyor.name} at location (${entryLocation.name}) in process, ($this)" }
                return request
            }

            override suspend fun rideConveyor(
                conveyorRequest: ConveyorRequestIfc,
                destination: IdentityIfc,
                suspensionName: String?
            ) {
                require(entity.conveyorRequest != null) {
                    "Attempted to ride without having requested the conveyor."
                }
                require(entity.conveyorRequest == conveyorRequest) {
                    "Attempted to ride without owning the supplied conveyor request."
                }
                require(conveyorRequest.isBlockingEntry || conveyorRequest.isBlockingExit)
                { "The supplied request is not blocking an entry or exit location" }
                currentSuspendName = suspensionName
                currentSuspendType = SuspendType.RIDE
                val conveyor = conveyorRequest.conveyor
                val origin = conveyorRequest.currentLocation
                require(conveyor.isReachable(origin, destination))
                    { "The destination (${destination.name} is not reachable from entry location (${origin.name})" }
                logger.info { "$time > entity (${entity.name}) asking to ride conveyor (${conveyor.name}) from ${origin.name} to ${destination.name}"}
                // causes event(s) to be scheduled that will eventually resume the entity after the ride
                val request = conveyorRequest as Conveyor.ConveyorRequest
                request.rideConveyorTo(destination)
                logger.info { "$time > entity (${entity.name}) riding conveyor (${conveyor.name}) from ${origin.name} to ${destination.name} suspending process, ($this) ..." }
                isMoving = true
                // holds here while request rides on the conveyor
                hold(conveyor.conveyorHoldQ, suspensionName = "$suspensionName:RIDE:${conveyor.conveyorHoldQ.name}")
                isMoving = false
                logger.info { "$time > entity (${entity.name}) completed ride from ${origin.name} to ${destination.name}" }
                currentSuspendName = null
                currentSuspendType = SuspendType.NONE
            }

            override suspend fun exitConveyor(
                conveyorRequest: ConveyorRequestIfc,
                suspensionName: String?
            ) {
                require(entity.conveyorRequest != null) { "The entity attempted to exit without using the conveyor." }
                require(entity.conveyorRequest == conveyorRequest) { "The exiting entity does not own the supplied conveyor request" }
                require(conveyorRequest.isBlockingEntry || conveyorRequest.isBlockingExit)
                { "The supplied request is not blocking an entry (${conveyorRequest.isBlockingEntry}) or exit (${conveyorRequest.isBlockingExit}) location" }
                currentSuspendName = suspensionName
                currentSuspendType = SuspendType.EXIT
                val conveyor = conveyorRequest.conveyor
                logger.info { "$time > exitConveyor(): Entity (${entity.name}) is exiting ${conveyor.name}" }
                val request = conveyorRequest as Conveyor.ConveyorRequest
                if (request.isBlockingEntry) {
                    // the request cannot be riding or completed, if just blocking the entry, it must just complete
                    request.exitConveyor()
                    logger.info { "$time > exitConveyor(): Entity (${entity.name}) released blockage at entry location ${request.currentLocation.name} for (${conveyor.name})" }
                } else {
                    // must be blocking the exit
                    isMoving = true
                    conveyor.startExitingProcess(request)
                    logger.info { "$time > exitConveyor(): Entity (${entity.name}) started exiting process for (${conveyor.name}) at location (${conveyorRequest.destination?.name})" }
                    logger.info { "$time > exitConveyor(): Entity (${entity.name}) suspending for exiting process" }
                    hold(conveyor.conveyorHoldQ, suspensionName = "$suspensionName:EXIT:${conveyor.conveyorHoldQ.name}")
                    isMoving = false
                }
                entity.conveyorRequest = null
                logger.info { "$time > exitConveyor(): Entity (${entity.name}) exited ${conveyor.name}" }
                currentSuspendName = null
                currentSuspendType = SuspendType.NONE
            }

            override fun resumeWith(result: Result<Unit>) {
                // Resumes the execution of the corresponding coroutine passing a successful or failed result
                // as the return value of the last suspension point.
                logger.trace { "$time > The coroutine process ${this@ProcessCoroutine} completed with result = $result" }
                result.onSuccess {
                    state.complete()
                    // check if the current process was activated (called) by other process in a waitFor
                    if (callingProcess != null) {
                        // it had a calling process
                        // the calling process must be suspended for it to be resumed
                        // if it was not suspended then it may have been terminated
                        if (callingProcess!!.isSuspended) {
                            // schedules calling process to resume at the current time
                            callingProcess!!.entity.resumeProcess()
                        }
                        // called process is completed. it no longer can have calling process
                        callingProcess = null
                    }
                    afterSuccessfulProcessCompletion(this)
                }.onFailure {
                    if (it is ProcessTerminatedException) {
                        // check if the current process was activated (called) by other process in a waitFor
                        // if so, then because this process was terminated with an exception, we should terminate its caller
                        if (callingProcess != null) {
                            // in here means that this process was called by another process
                            // the calling process must be suspended if it was the caller, but just in case I am checking
                            if (callingProcess!!.isSuspended) {
                                // make the calling process think that it has not called
                                // this is to prevent the calling process from trying to re-terminate the called process
                                // When it is terminated
                                callingProcess!!.calledProcess = null
                                callingProcess!!.terminate()
                            }
                            callingProcess = null
                        }
                        //commenting allows sub-process to finish
                        // uncommented causes sub-process to terminate
                        // if the main process is waiting on a called process, then terminate that process also
                        if (calledProcess != null) {
                            // the main process has been terminated. We need to terminate the process
                            // that it is waiting on.  That is, it's calledProcess
                            // the called process should be suspended, but just in case I am checking
                            if (calledProcess!!.isSuspended) {
                                // the called process has a calling process, that's the process that
                                // is right now, in this method, being terminated. We set its reference
                                // to null so that when the called process is terminated, it does not
                                // try to terminate its calling process, which has already terminated
                                calledProcess!!.callingProcess = null
                                // now terminate the sub-process
                                calledProcess!!.terminate()
                            }
                            calledProcess = null
                        }
                        afterTerminatedProcessCompletion()
                        handleTerminatedProcess(this)
                        if (it.afterTermination != null) {
                            it.afterTermination.invoke(this@Entity)
                        }
                    } else {
                        // some other exception, rethrow it
                        throw it
                    }
                }
            }

            /** This method is called from resumeWith to clean up the entity when
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
            private fun afterTerminatedProcessCompletion() {
                if (hasAllocations) {
                    logger.trace { "$time > Process $this was terminated for Entity $entity releasing all resources." }
                    releaseAllResources()
                }
                if (isQueued) {
                    //remove it from its queue with no stats
                    @Suppress("UNCHECKED_CAST")
                    // since this is an entity, it must be in a HoldQueue which must hold EntityType.Entity
                    val q = queue!! as Queue<ProcessModel.Entity>
                    q.remove(entity, false)
                    logger.trace { "$time > Process $this was terminated for Entity $entity removed from queue ${q.name} ." }
                } else if (isScheduled) {
                    if (myDelayEvent != null) {
                        if (myDelayEvent!!.isScheduled) {
                            logger.trace { "$time > Process $this was terminated for Entity $entity delay event was cancelled." }
                            myDelayEvent?.cancel = true
                        }
                    }
                }
            }

            override fun toString(): String {
                return "Process(id=$id, name='$name', state = ${state.processStateName})"
            }

            private inner class DelayAction : EventAction<Nothing>() {
                override fun action(event: KSLEvent<Nothing>) {
                    logger.trace { "$time > entity ${entity.id} exiting delay, resuming process, (${this@ProcessCoroutine}) ..." }
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

                /**
                 *  @param afterTermination a function to invoke after the process is successfully terminated
                 */
                open fun terminate(afterTermination: ((entity: ProcessModel.Entity) -> Unit)? = null) {
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
                    logger.trace { "$time > entity ${entity.id} resumed process, (${this@ProcessCoroutine}) ..." }
                    continuation?.resume(Unit)
                }

                /**
                 *  @param afterTermination a function to invoke after the process is successfully terminated
                 */
                override fun terminate(afterTermination: ((entity: ProcessModel.Entity) -> Unit)?) {
                    state = terminated
                    //un-capture suspended entities here
                    suspendedEntities.remove(entity)
                    logger.trace { "$time > entity ${entity.id} terminated process, (${this@ProcessCoroutine}) ..." }
                    //resume with exception
//                    continuation?.resumeWith(Result.failure(ProcessTerminatedException())) // same as below
                    continuation?.resumeWithException(ProcessTerminatedException(afterTermination))
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