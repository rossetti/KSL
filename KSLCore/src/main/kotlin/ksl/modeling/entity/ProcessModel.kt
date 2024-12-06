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
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.ConstantRV
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.IllegalStateException
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
 * An entity can experience only one process at a time. If using a sequence, after completing a process,
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

    /** Note that an EntityGenerator relies on the entity having an initial process or at least one process
     * that has been added to its process sequence via the process() method's addToSequence
     * parameter being true. The generator will create the entity and
     * activate the initial process or the process that is listed first in its process sequence.  If the
     * entity does not have an initial process, or it does not have any processes in its process sequence
     * then an illegal state exception will occur. If the entity has both an initial process defined and
     * a non-empty process sequence, the initial process will be activated.
     *
     * @param entityCreator the thing that creates the entities of the particular type. Typically,
     * a reference to the constructor of the class
     * @param timeUntilTheFirstEntity the time until the first entity creation
     * @param timeBtwEvents the time between entity creation
     * @param maxNumberOfEvents the maximum number of entities to create
     * @param timeOfTheLastEvent the time of the last entity creation
     * @param activationPriority the priority for the activation of the entity
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
            require(entity.defaultProcess != null) { "There was no default process specified for the entity. Ensure that the `defaultProcess` property is set via the process() function or directly." }
            activate(entity.defaultProcess!!, priority = activationPriority)
        }

    }

    /** Note that an EntitySequenceGenerator relies on the entity having at least one process
     * that has been added to its process sequence and the entity's addToSequence
     * property being true. The generator will create the entity and
     * activate the process that is listed first in its process sequence.  If the
     * entity does not have any processes in its process sequence
     * then an illegal state exception will occur.
     *
     * @param entityCreator the thing that creates the entities of the particular type. Typically,
     * a reference to the constructor of the class
     * @param timeUntilTheFirstEntity the time until the first entity creation
     * @param timeBtwEvents the time between entity creation
     * @param maxNumberOfEvents the maximum number of entities to create
     * @param timeOfTheLastEvent the time of the last entity creation
     * @param activationPriority the priority for the activation of the entity
     * @param name a name for the generator
     */
    protected inner class EntitySequenceGenerator<T : Entity>(
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
            require(entity.useProcessSequence) { "Cannot start the sequence because the entity ${entity.name} does not use a sequence" }
            require(entity.processSequence.isNotEmpty()) { "Use process sequence was on, but no processes are in the sequence" }
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
        require(entity.useProcessSequence) { "The entity.useProcessSequence property must be true to start the sequence." }
        require(entity.processSequence.isNotEmpty()) { "Use process sequence was on, but no processes are in the sequence" }
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

    /**
     *  It is essential that subclasses that override this function call the super.
     *  This ensures that suspended entities are cleaned up after a replication
     */
    override fun afterReplication() {
        // make a copy of the set for iteration purposes
        val set = suspendedEntities.toHashSet()
        Model.logger.info { "After Replication for ${this.name}: terminating ${set.size} suspended entities" }
        for (entity in set) {
            if (entity.isSuspended) {
                //TODO this check necessary because a terminating process may terminate its calling process and
                // that termination does not remove the suspended entity from the local copy of the set of suspended entities.
                // So, only terminate those processes that are suspended and skip those that are already terminated
                entity.terminateProcess()
            }
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
         *  If supplied, this process will be the process activated by an EntityGenerator
         *  that creates and activates the entity.
         */
        var defaultProcess: KSLProcess? = null

        /**
         *  Holds the named processes for the entity
         */
        private val myProcesses = mutableMapOf<String, KSLProcess>()

        /**
         *  Provides access to the entity's processes by name
         */
        val processes: Map<String, KSLProcess>
            get() = myProcesses

        /**
         *  Controls whether the entity uses an assigned process sequence via the processSequence property
         *  at the end of successfully completing a process to determine the next process to experience.
         *  The default is false.
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
        private val myBlockedUntilCompletion = BlockedUntilCompletion()
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
        val isBlockedUntilCompletion: Boolean
            get() = state == myBlockedUntilCompletion

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
         *  Note that a process defined by this function might not be
         *  the entity's default initial process. To use an EntityGenerator the entity
         *  must have a default initial process. There can only be 1 default process for an entity.
         *  Calling this function more than once with [isDefaultProcess] true will set the default
         *  process via the last called function.
         *
         *  @param processName the name of the process
         *  @param isDefaultProcess whether to set the process to the entity's default initial process. The default
         *  value is false. The process will not be used as the entity's default initial process.
         */
        protected fun process(
            processName: String? = null,
            isDefaultProcess: Boolean = false,
            block: suspend KSLProcessBuilder.() -> Unit
        ): KSLProcess {
            //TODO: ISSUE no name causes new name to be created, which will never be in the map
            // the map will never contain null because the key for the map is non-null and null causes a unique name to be created
            require(!myProcesses.contains(processName))
            { "The process name, $processName has already been used for the entity. The process name must be unique to the entity type." }
            val coroutine = ProcessCoroutine(processName)
            //TODO only add to the map if processName was not null???
            myProcesses[coroutine.name] = coroutine
            // if this is the first defined process, make it the initial process by default
            if (myProcesses.size == 1) {
                defaultProcess = coroutine
            } else {
                // not the first one, overwrite initial process only if told to do so
                if (isDefaultProcess) {
                    defaultProcess = coroutine
                }
            }
            coroutine.continuation = block.createCoroutineUnintercepted(receiver = coroutine, completion = coroutine)
            return coroutine
        }

        //TODO require the name of the suspension to ensure that the resume matches it????
        // will need an internal one for the 9 current uses of it that does not need the name
        // and an external facing one that requires the name

        /**
         *  If the entity is executing a process and the process is suspended, then
         *  the process is scheduled to resume at the current simulation time.
         *
         *  @param timeUntilResumption the time until the resumption will occur
         *  @param priority the priority parameter can be used to provide an ordering to the
         *  scheduled resumption events, if multiple events are scheduled at the same time
         */
        fun resumeProcess(timeUntilResumption: Double = 0.0, priority: Int = KSLEvent.DEFAULT_PRIORITY) {
            // entity must be in a process and suspended
            if (myCurrentProcess != null) {
                val event = myResumeAction.schedule(timeUntilResumption, priority = priority)
                logger.trace { "r = ${model.currentReplicationNumber} : $time > entity_id = $id: scheduled resume action for time ${time + timeUntilResumption} : event_id = ${event.id}" }
            }
        }

        /**
         *  This function causes the process to immediately resume the captured continuation. An underlying state pattern
         *  enforces that the process coroutine can only be resumed if it has been suspended. This function
         *  allows the entity to immediately resume a suspended process with no simulated time delay. That is, no event is scheduled
         *  and processed to perform the resumption. This is in contrast with the Entity.resumeProcess() function, which
         *  forces (at least a 0.0) time delay and thus release back to the event loop to cause the process
         *  to be resumed.  An understanding of when a process should be resumed within an event loop is necessary
         *  to effectively use this function.  In some cases, resuming through the event loop will not provide
         *  the fine-grained control for the sequence of calls necessary. Thus, this method can be used internally
         *  when needed in those (rare) cases.
         */
        internal fun immediateResume() {
            //TODO immediateResume() who calls this?
            // called from 1) within resourceSeizeAction(), 2)resourcePoolSeizeAction(), 3) HoldQueue.removeAndImmediateResume()
            if (myCurrentProcess != null) {
//                logger.trace { "r = ${model.currentReplicationNumber} : $time > entity_id = $id: called IMMEDIATE resume" }
                myCurrentProcess!!.resumeContinuation()
//                logger.trace { "r = ${model.currentReplicationNumber} : $time > entity_id = $id: after the IMMEDIATE resume call" }
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
                logger.trace { "r = ${model.currentReplicationNumber} : $time > EVENT : *** EXECUTING ... : event_id = ${event.id} : entity_id = $id : ResumeAction : before immediateResume()" }
                immediateResume()
                logger.trace { "r = ${model.currentReplicationNumber} : $time > EVENT : *** COMPLETED! : event_id = ${event.id} : entity_id = $id : ResumeAction : after immediateResume()" }
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
         *  the next process (if there is one). Called right before determineNextProcess()
         *
         *  By default, this method does nothing.
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
            logger.trace { "r = ${model.currentReplicationNumber} : $time > entity $id completed process = $completedProcess" }
            // must clear the current process so next can be run if there is one
            myCurrentProcess = null
            afterRunningProcess(completedProcess)
            val np = determineNextProcess(completedProcess)
            if (np != null) {
                previousProcess = completedProcess
                logger.trace { "r = ${model.currentReplicationNumber} : $time > entity $id to activate process = $np next" }
                activate(np)
            } else {
                // no next process to run, entity must not have any allocations
                dispose(completedProcess)
                if (hasAllocations) {
                    val msg = StringBuilder()
                    msg.append("r = ${model.currentReplicationNumber} : $time > entity $id had allocations when ending process $completedProcess with no next process!")
                    msg.appendLine()
                    msg.append(allocationsAsString())
                    logger.error { msg.toString() }
                    throw IllegalStateException(msg.toString())
                }
                // okay to dispose of the entity
                if (autoDispose) {
                    logger.trace { "r = ${model.currentReplicationNumber} : $time > entity $id is being disposed by ${processModel.name}" }
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

            open fun blockUntilCompletion() {
                errorMessage("block until the process is completed")
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

            override fun blockUntilCompletion() {
                state = myBlockedUntilCompletion
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

        private inner class BlockedUntilCompletion : EntityState("BlockedUntilCompletion") {
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

            internal var callingProcess: ProcessCoroutine? = null  // for normal waitFor
            internal var calledProcess: ProcessCoroutine? = null

            // can be used internally to control the priority of resuming after a suspension
            internal var resumptionPriority: Int = KSLEvent.DEFAULT_PRIORITY

            // need a set to hold processes that are blocked waiting on the completion of this process
            private var blockedUntilCompletionListeners: MutableSet<ProcessCoroutine>? = null

            // need a set to hold the processes that this process might be blocking until they complete
            private var blockingUntilCompletedSet: MutableSet<ProcessCoroutine>? =
                null  //TODO blockingUntilCompletedSet

            override var isActivated: Boolean = false
                private set
            private val created = Created()
            private val suspended = Suspended()
            private val terminated = Terminated()
            private val completed = Completed()
            private val running = Running()
            private var state: ProcessState = created
            override val currentStateName: String
                get() = state.processStateName
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
             *  into the future. This schedules an event.
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
                check(!hasPendingProcess) { "The $this process cannot be activated for the entity because the entity already has a pending process: ${pendingProcess?.name}" }
                // hasCurrentProcess gets set to false (currentProcess get set to null) in afterSuccessfulProcessCompletion()
                check(!hasCurrentProcess) { "The $this process cannot be activated for the entity because the entity is already running a process: ${currentProcess?.name}" }
                myPendingProcess = this
                entity.state.schedule()
                logger.trace { "r = ${model.currentReplicationNumber} : $time > entity_id = ${entity.id} scheduling process $this to start at time ${time + timeUntilActivation}" }
                return myActivationAction.schedule(timeUntilActivation, this, priority)
            }

            private inner class ActivateAction : EventAction<KSLProcess>() {
                override fun action(event: KSLEvent<KSLProcess>) {
                    logger.trace { "r = ${model.currentReplicationNumber} : $time > EVENT : *** EXECUTING ... : event_id = ${event.id} : ActivateAction : entity_id = ${entity.id}" }
                    beforeRunningProcess(myPendingProcess!!)
                    activateProcess()
                    logger.trace { "r = ${model.currentReplicationNumber} : $time > EVENT : *** COMPLETED! : event_id = ${event.id} : ActivateAction : entity_id = ${entity.id}" }
                }
            }

            /**
             * This method is called when the entity's current process is activated for the
             * first time.
             */
            private fun activateProcess() {
                myPendingProcess = null
                logger.trace { "r = ${model.currentReplicationNumber} : $time > entity_id = ${entity.id} activating and running process, ($this)" }
                entity.state.activate()// was scheduled, now entity is active for running
                start() // starts the coroutine from the created state
            }

            /**
             *  Must be called only when coroutine is in its created state. Causes the coroutine to
             *  resume its continuation and run until its first suspension point.
             */
            private fun start() {
                // The coroutine is told to resume its continuation. Thus, it runs until its first suspension point.
                logger.trace { "r = ${model.currentReplicationNumber} : $time > entity_id = ${entity.id} : has been told to start : process, ($this)" }
                state.start() // this is the coroutine state, can only start process (coroutine) from the created state
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

            /**
             *  This causes the process to immediately resume the captured continuation. A state pattern
             *  enforces that the process coroutine can only be resumed if it has been suspended. This process
             *  routine is immediately resumed with no simulated time delay. That is, no event is scheduled
             *  and processed to perform the resumption. This is in contrast with the Entity.resumeProcess() function, which
             *  forces (at least a 0.0) time delay and thus release back to the event loop to cause the process
             *  to be resumed.
             */
            internal fun resumeContinuation() {
                state.resume()
            }

            /**
             * @param afterTermination a function to invoke after the process is successfully terminated
             */
            internal fun terminate(afterTermination: ((entity: ProcessModel.Entity) -> Unit)? = null) {
                state.terminate(afterTermination)
            }

            //TODO require the name of the suspension to ensure that the resume matches it????

            override suspend fun suspend(suspensionName: String?) {
                currentSuspendName = suspensionName
                currentSuspendType = SuspendType.SUSPEND
                logger.trace { "r = ${model.currentReplicationNumber} : $time > entity_id = ${entity.id} suspended process, ($this) for suspension named: $currentSuspendName" }
                //  suspensionObserver.attach(entity)
                suspend()
                //  suspensionObserver.detach(entity)
                logger.trace { "r = ${model.currentReplicationNumber} : $time > entity_id = ${entity.id} suspended process, ($this) resumed from suspension named: $currentSuspendName" }
                currentSuspendName = null
                currentSuspendType = SuspendType.NONE
            }

            /**
             *  The critical method. This method uses suspendCoroutineUninterceptedOrReturn() to capture
             *  the continuation for future resumption. Places the state of the process into the suspended state.
             */
            private suspend fun suspend() {
                //logger.trace { "r = ${model.currentReplicationNumber} : $time > entity_id = ${entity.id}: suspension name = $currentSuspendName: suspending process, ($this) ..." }
                state.suspend()
                logger.trace { "r = ${model.currentReplicationNumber} : $time > ProcessCoroutine.suspend(): entity_id = ${entity.id}: *** COROUTINE SUSPEND ***: process = (${this@ProcessCoroutine})" }
                logger.trace { "r = ${model.currentReplicationNumber} : $time > ProcessCoroutine.suspend(): entity_id = ${entity.id}: suspension name = $currentSuspendName: suspending..." }
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
                require(process.isCreated) { "The supplied process ${process.name} must be in the created state. It's state was: ${process.currentStateName}" }
                val p = process as ProcessCoroutine
                require(p.callingProcess == null) { "The process to wait on already has a calling process" }
                p.callingProcess = this
                calledProcess = p
                currentSuspendName = suspensionName
                currentSuspendType = SuspendType.WAIT_FOR_PROCESS
                logger.trace { "r = ${model.currentReplicationNumber} : $time > entity_id = ${entity.id} waiting for ${process.name} in process, ($this)" }
                p.activate(timeUntilActivation, priority)
                entity.state.waitForProcess()
                suspend()
                calledProcess = null
                entity.state.activate()
                logger.trace { "r = ${model.currentReplicationNumber} : $time > entity_id = ${entity.id} ended wait for ${process.name} in process, ($this)" }
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
                logger.trace { "r = ${model.currentReplicationNumber} : $time > entity_id = ${entity.id} waiting for ${signal.name} in process, ($this)" }
                entity.state.waitForSignal()
                signal.hold(entity, waitPriority)
                suspend()
                signal.release(entity, waitStats)
                entity.state.activate()
                logger.trace { "r = ${model.currentReplicationNumber} : $time > entity_id = ${entity.id} released from ${signal.name} in process, ($this)" }
                currentSuspendName = null
                currentSuspendType = SuspendType.NONE
            }

            private fun attachBlockingCompletionListener(listener: KSLProcess) {
                if (blockedUntilCompletionListeners == null) {
                    blockedUntilCompletionListeners = mutableSetOf()
                }
                blockedUntilCompletionListeners!!.add(listener as ProcessCoroutine)
            }

            private fun detachBlockingCompletionListener(listener: KSLProcess) {
                blockedUntilCompletionListeners?.remove(listener)
            }

            override suspend fun blockUntilAllCompleted(
                processes: Set<KSLProcess>,
                resumptionPriority: Int,
                suspensionName: String?
            ) {
                for (process in processes) {
                    require(currentProcess != process) { "The supplied process ${process.name} is the same as the current process! " }
                    require(!process.isTerminated) { "The supplied process ${process.name} is terminated! Cannot block for a terminated process." }
                    require(process.entity.isScheduled || process.isActivated) { "The supplied process ${process.name} must be scheduled or activated in order to block the current process! " }
                }
                val processSet = mutableSetOf<ProcessCoroutine>()
                for (process in processes) {
                    if (!process.isCompleted) {
                        processSet.add(process as ProcessCoroutine)
                    }
                }
                if (processSet.isEmpty()) {
                    logger.trace { "r = ${model.currentReplicationNumber} : $time > entity_id = ${entity.id} did not block until all completed for $suspensionName, because all processes were completed, in process, ($this)" }
                    return
                }
                currentSuspendName = suspensionName
                currentSuspendType = SuspendType.BLOCK_UNTIL_COMPLETION
                logger.trace { "r = ${model.currentReplicationNumber} : $time > entity_id = ${entity.id} blocking until all complete for suspension $currentSuspendName, in process, ($this)" }
                entity.state.blockUntilCompletion()
                this.resumptionPriority = resumptionPriority
                blockingUntilCompletedSet = processSet
                // tell each completing process to remember that the current process (this) is blocking until it completes
                for (completingProcess in blockingUntilCompletedSet!!) {
                    completingProcess.attachBlockingCompletionListener(this)
                }
                // suspend this current process while the supplied processes complete
                suspend()
                // tell each completing process to forget that this current process was blocking for it
                for (completedProcess in blockingUntilCompletedSet!!) {
                    completedProcess.detachBlockingCompletionListener(this)
                }
                // done blocking on the process so make sure that set is clear
                blockingUntilCompletedSet?.clear()
                blockingUntilCompletedSet = null
                this.resumptionPriority = KSLEvent.DEFAULT_PRIORITY
                entity.state.activate()
                logger.trace { "r = ${model.currentReplicationNumber} : $time > entity_id = ${entity.id} stopped blocking until all complete for suspension $currentSuspendName, in process, ($this)" }
                currentSuspendName = null
                currentSuspendType = SuspendType.NONE
            }

            override suspend fun blockUntilCompleted(
                process: KSLProcess,
                resumptionPriority: Int,
                suspensionName: String?
            ) {
                blockUntilAllCompleted(setOf(process), resumptionPriority, suspensionName)
            }

            //TODO blockUntilCompletion function
//            override suspend fun blockUntilCompletion(
//                process: KSLProcess,
//                resumptionPriority: Int,
//                suspensionName: String?
//            ) {
//                require(currentProcess != process) { "The supplied process ${process.name} is the same as the current process! " }
//                require(!process.isTerminated) { "The supplied process ${process.name} is terminated! Cannot block for a terminated process." }
//                if (process.isCompleted) {
//                    logger.trace { "r = ${model.currentReplicationNumber} : $time > entity_id = ${entity.id} did not block for ${process.name}, because it was already completed, in process, ($this)" }
//                    return
//                }
//                require(process.entity.isScheduled || process.isActivated) { "The supplied process ${process.name} must be scheduled or activated in order to block the current process! " }
//                currentSuspendName = suspensionName
//                currentSuspendType = SuspendType.BLOCK_UNTIL_COMPLETION
//                logger.trace { "r = ${model.currentReplicationNumber} : $time > entity_id = ${entity.id} blocking until ${process.name} completes, in process, ($this)" }
//                entity.state.blockUntilCompletion()
//                this.resumptionPriority = resumptionPriority
//                val theCompletingProcess = process as ProcessCoroutine
//                theCompletingProcess.attachBlockingCompletionListener(this)
//                suspend() // theCompletingProcess will resume the blocked processes when it completes
//                theCompletingProcess.detachBlockingCompletionListener(this)
//                this.resumptionPriority = KSLEvent.DEFAULT_PRIORITY
//                entity.state.activate()
//                logger.trace { "r = ${model.currentReplicationNumber} : $time > entity_id = ${entity.id} stopped blocking until ${process.name} completes, in process, ($this)" }
//                currentSuspendName = null
//                currentSuspendType = SuspendType.NONE
//            }

            override suspend fun hold(queue: HoldQueue, priority: Int, suspensionName: String?) {
                currentSuspendName = suspensionName
                currentSuspendType = SuspendType.HOLD
                logger.trace { "r = ${model.currentReplicationNumber} : $time > BEGIN : HOLD : entity_id = ${entity.id} : entering holdQ = ${queue.name} : suspension name = $suspensionName : in process, ($this)" }
                entity.state.holdInQueue()
                queue.enqueue(entity, priority)
                suspend()
                entity.state.activate()
                logger.trace { "r = ${model.currentReplicationNumber} : $time > END : HOLD: entity_id = ${entity.id} : released from holdQ = ${queue.name} : suspension name = $suspensionName" }
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
                    logger.trace { "r = ${model.currentReplicationNumber} : $time > entity_id = ${entity.id} blocked receiving to ${blockingQ.name} in process, ($this)" }
                    entity.state.blockedReceiving()
                    suspend()
                    entity.state.activate()
                    logger.trace { "r = ${model.currentReplicationNumber} : $time > entity_id = ${entity.id} unblocked receiving to ${blockingQ.name} in process, ($this)" }
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
                    logger.trace { "r = ${model.currentReplicationNumber} : $time > entity_id = ${entity.id} blocked sending to ${blockingQ.name} in process, ($this)" }
                    entity.state.blockedSending()
                    suspend()
                    entity.state.activate()
                    logger.trace { "r = ${model.currentReplicationNumber} : $time > entity_id = ${entity.id} unblocked sending to ${blockingQ.name} in process, ($this)" }
                }
                blockingQ.dequeSender(entity)
                logger.trace { "r = ${model.currentReplicationNumber} : $time > entity_id = ${entity.id} sending ${item.name} to ${blockingQ.name} in process, ($this)" }
                blockingQ.sendToChannel(item)
                currentSuspendName = null
                currentSuspendType = SuspendType.NONE
            }

            private fun resourceSeizeAction(event: KSLEvent<Request>) {
                val request = event.message!!
                val resource = request.resource!!
                logger.trace { "r = ${model.currentReplicationNumber} : $time > EVENT : *** EXECUTING ... : event_id = ${event.id} : entity_id = ${request.entity.id} : seize action" }
                if (resource.canAllocate(request.amountRequested)) {
                    logger.trace { "r = ${model.currentReplicationNumber} : $time > ... executing event : event_id = ${event.id} : entity_id = ${request.entity.id} : amount = ${request.amountRequested}, available : immediate resume" }
                    request.entity.immediateResume()
                } else {
                    logger.trace { "r = ${model.currentReplicationNumber} : $time > ... executing event : event_id = ${event.id} : entity_id = ${request.entity.id} : amount = ${request.amountRequested}, not available : wait (suspended)" }
                }
                logger.trace { "r = ${model.currentReplicationNumber} : $time > EVENT : *** COMPLETED! : event_id = ${event.id} : entity_id = ${request.entity.id} : seize action " }
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
                logger.trace { "r = ${model.currentReplicationNumber} : $time > BEGIN : SEIZE: entity_id = ${entity.id}: suspension name = $currentSuspendName" }
                //create the request based on the current resource state
                val request = createRequest(amountNeeded, resource)
                request.priority = entity.priority
                queue.enqueue(request) // put the request in the queue
                logger.trace { "r = ${model.currentReplicationNumber} : $time > entity_id = ${entity.id}: enqueued request_id = ${request.id} for $amountNeeded units of ${resource.name}" }
                logger.trace { "r = ${model.currentReplicationNumber} : $time > ... scheduling the delay of 0.0 for seize priority ..." }
                schedule(::resourceSeizeAction, 0.0, priority = seizePriority, message = request)
                entity.state.waitForResource()
                suspend()
                entity.state.activate()
                // entity has been told to resume
                require(resource.canAllocate(amountNeeded)) { "r = ${model.currentReplicationNumber} : $time > Amount cannot be allocated! to entity_id = ${entity.id} resuming after waiting for $amountNeeded units of ${resource.name}" }
                queue.remove(request) // take the request out of the queue after possible wait
                logger.trace { "r = ${model.currentReplicationNumber} : $time > entity_id = ${entity.id} waited ${request.timeInQueue} units" }
                val allocation = resource.allocate(entity, amountNeeded, queue, suspensionName)
                logger.trace { "r = ${model.currentReplicationNumber} : $time > entity_id = ${entity.id}: allocated $amountNeeded units of ${resource.name} : allocation_id = ${allocation.id}" }
                logger.trace { "r = ${model.currentReplicationNumber} : $time > END : SEIZE: entity_id = ${entity.id}: suspension name = $currentSuspendName" }
                currentSuspendName = null
                currentSuspendType = SuspendType.NONE
                return allocation
            }

            private fun resourcePoolSeizeAction(event: KSLEvent<Request>) {
                val request = event.message!!
                val resource = request.resourcePool!!
                logger.trace { "r = ${model.currentReplicationNumber} : $time > EVENT : *** EXECUTING ... : event_id = ${event.id} : entity_id = ${request.entity.id} : seize action" }
                if (resource.canAllocate(request.amountRequested)) {
                    logger.trace { "r = ${model.currentReplicationNumber} : $time > ... executing event : event_id = ${event.id} : entity_id = ${request.entity.id} : amount = ${request.amountRequested}, available : immediate resume" }
                    request.entity.immediateResume()
                } else {
                    logger.trace { "r = ${model.currentReplicationNumber} : $time > ... executing event : event_id = ${event.id} : entity_id = ${request.entity.id} : amount = ${request.amountRequested}, not available : wait (suspended)" }
                }
                logger.trace { "r = ${model.currentReplicationNumber} : $time > EVENT : *** COMPLETED! : event_id = ${event.id} : entity_id = ${request.entity.id} : seize action " }
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
                logger.trace { "r = ${model.currentReplicationNumber} : $time > BEGIN : SEIZE: entity_id = ${entity.id}: suspension name = $currentSuspendName" }
                //create the request based on the current resource state
                val request = createRequest(amountNeeded, resourcePool)
                request.priority = entity.priority
                queue.enqueue(request) // put the request in the queue
                logger.trace { "r = ${model.currentReplicationNumber} : $time > entity_id = ${entity.id}: enqueued request_id = ${request.id} for $amountNeeded units of ${resourcePool.name}" }
                logger.trace { "r = ${model.currentReplicationNumber} : $time > ... scheduling the delay of 0.0 for seize priority ..." }
                schedule(::resourcePoolSeizeAction, 0.0, priority = seizePriority, message = request)
                entity.state.waitForResource()
                suspend()
                entity.state.activate()
                // entity has been told to resume
                require(resourcePool.canAllocate(amountNeeded)) { "r = ${model.currentReplicationNumber} : $time > Amount cannot be allocated! to entity_id = ${entity.id} resuming after waiting for $amountNeeded units of ${resourcePool.name}" }
                queue.remove(request) // take the request out of the queue after possible wait
                logger.trace { "r = ${model.currentReplicationNumber} : $time > entity_id = ${entity.id} waited ${request.timeInQueue} units" }
                val allocation = resourcePool.allocate(entity, amountNeeded, queue, suspensionName)
                logger.trace { "r = ${model.currentReplicationNumber} : $time > entity_id = ${entity.id}: allocated $amountNeeded units of ${resourcePool.name} : allocation_id = ${allocation.id}" }
                logger.trace { "r = ${model.currentReplicationNumber} : $time > END : SEIZE: entity_id = ${entity.id}: suspension name = $currentSuspendName" }
                currentSuspendName = null
                currentSuspendType = SuspendType.NONE
                return allocation
            }

            override suspend fun delay(delayDuration: Double, delayPriority: Int, suspensionName: String?) {
                require(delayDuration >= 0.0) { "The duration of the delay must be >= 0.0 in process, ($this)" }
                require(delayDuration.isFinite()) { "The duration of the delay must be finite (cannot be infinite) in process, ($this)" }
                currentSuspendName = suspensionName
                currentSuspendType = SuspendType.DELAY
                logger.trace { "r = ${model.currentReplicationNumber} : $time > BEGIN : DELAY: entity_id = ${entity.id}: suspension name = $currentSuspendName" }
                // capture the event for possible cancellation
                entity.state.schedule()
                val eName = "Delay Event: duration = $delayDuration suspension name = $suspensionName"
                myDelayEvent = delayAction.schedule(delayDuration, priority = delayPriority, name = eName)
                myDelayEvent!!.entity = entity
                logger.trace { "r = ${model.currentReplicationNumber} : $time > entity_id = ${entity.id}: SCHEDULED end of delay: event_id = ${myDelayEvent!!.id}, time = ${myDelayEvent!!.time} : DELAY for ($delayDuration) STARTED" }
                suspend()
                entity.state.activate()
                logger.trace { "r = ${model.currentReplicationNumber} : $time > END : DELAY: entity_id = ${entity.id}: suspension name = $currentSuspendName : event_id = ${myDelayEvent!!.id}, time = ${myDelayEvent!!.time}" }
                require(time == myDelayEvent!!.time) { "r = ${model.currentReplicationNumber} : $time > END : DELAY: suspension name = $currentSuspendName : entity_id = ${entity.id} : the actual event time ($time) was not the same as the scheduled delay event time (${myDelayEvent!!.time})" }
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
                require(!isMoving) { "The entity_id = ${entity.id} is already moving" }
                require(velocity > 0.0) { "The velocity of the movement must be > 0.0 in process, ($this)" }
                if (currentLocation != fromLoc) {
                    currentLocation = fromLoc
                }
                val d = fromLoc.distanceTo(toLoc)
                val t = d / velocity
                logger.trace { "r = ${model.currentReplicationNumber} : $time > entity_id = ${entity.id} MOVING from ${fromLoc.name} to ${toLoc.name} suspending process, ($this) ..." }
                isMoving = true
                delay(t, movePriority, suspensionName)
                currentLocation = toLoc
                isMoving = false
                logger.trace { "r = ${model.currentReplicationNumber} : $time > entity_id = ${entity.id} completed move from ${fromLoc.name} to ${toLoc.name}" }
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
                logger.trace { "r = ${model.currentReplicationNumber} : $time > entity_id = ${entity.id} is moving ${spatialElement.spatialName} from ${spatialElement.currentLocation.name} to ${toLoc.name} suspending process, ($this) ..." }
                spatialElement.isMoving = true
                delay(t, movePriority, suspensionName)
                spatialElement.currentLocation = toLoc
                spatialElement.isMoving = false
                logger.trace { "r = ${model.currentReplicationNumber} : $time > spatial element ${spatialElement.spatialName} completed move to ${toLoc.name}" }
            }

            override suspend fun moveWith(
                spatialElement: SpatialElementIfc,
                toLoc: LocationIfc,
                velocity: Double,
                movePriority: Int,
                suspensionName: String?
            ) {
                require(!isMoving) { "The entity_id = ${entity.id} is already moving" }
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
                logger.trace { "r = ${model.currentReplicationNumber} : $time > BEGIN: RELEASE : entity_id = ${entity.id}: ${allocation.amount} units of ${allocation.resource.name}" }
                // we cannot assume that a resource has a queue
                allocation.resource.deallocate(allocation)
                // get the queue from the allocation being released and process any waiting requests
                // note that the released amount may allow multiple requests to proceed
                // this may be a problem depending on how numAvailableUnits is defined
                if (!executive.isEnded) {
                    allocation.queue.processWaitingRequests(allocation.resource.numAvailableUnits, releasePriority)
                }
                logger.trace { "r = ${model.currentReplicationNumber} : $time > END : RELEASE: entity_id = ${entity.id} : allocation_id = ${allocation.id}" }

            }

            override fun release(resource: Resource, releasePriority: Int) {
                logger.trace {
                    "r = ${model.currentReplicationNumber} : $time > entity_id = ${entity.id} RELEASE all ${
                        entity.totalAmountAllocated(
                            resource
                        )
                    } units of ${resource.name} allocated in process, ($this)"
                }
                // get the allocations of this entity for this resource
                val list = resource.allocations(entity)
                for (allocation in list) {
                    release(allocation, releasePriority)
                }
            }

            override fun releaseAllResources(releasePriority: Int) {
                logger.trace { "r = ${model.currentReplicationNumber} : $time > entity_id = ${entity.id} RELEASE all units of every allocated resource in process, ($this)" }
                val rList = resourceAllocations.keys.toList()
                for (r in rList) {
                    release(r, releasePriority)
                }
            }

            override fun release(pooledAllocation: ResourcePoolAllocation, releasePriority: Int) {
                logger.trace { "r = ${model.currentReplicationNumber} : $time > entity_id = ${entity.id} RELEASE ${pooledAllocation.amount} units of ${pooledAllocation.resourcePool.name} in process, ($this)" }
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
                require(conveyor.entryLocations.contains(entryLocation)) {
                    "The location (${entryLocation.name}) " +
                            "is not an entry location for (${conveyor.name})"
                }
                require(numCellsNeeded >= 1) { "The amount of cells to allocate must be >= 1" }
                require(numCellsNeeded <= conveyor.maxEntityCellsAllowed) {
                    "The entity requested more cells ($numCellsNeeded) than " +
                            "the allowed maximum (${conveyor.maxEntityCellsAllowed}) for for conveyor (${conveyor.name}"
                }
                currentSuspendName = suspensionName
                currentSuspendType = SuspendType.ACCESS
                logger.trace { "r = ${model.currentReplicationNumber} : $time > BEGIN : REQUEST CONVEYOR : entity_id = ${entity.id} : requesting $numCellsNeeded cells of ${conveyor.name} : suspension name = $currentSuspendName" }
                // schedules the conveyor to receive the request for cells at the current time ordered by request priority
                val conveyorRequest = conveyor.receiveEntity(
                    entity,
                    numCellsNeeded,
                    entryLocation,
                    requestPriority,
                    requestResumePriority
                )
                // holds the entity until the entry cell is blocked for entry
                hold(
                    conveyor.conveyorHoldQ,
                    suspensionName = "$suspensionName:HoldForCells:${conveyor.conveyorHoldQ.name}"
                )
                // ensure that the entity remembers that it is now "using" the conveyor
                entity.conveyorRequest = conveyorRequest
                // entity via the request now blocks (controls) the access cell for entry
                conveyorRequest.blockEntryLocation()
                logger.trace { "r = ${model.currentReplicationNumber} : $time > END : REQUEST CONVEYOR : entity_id = ${entity.id} : suspension name = $currentSuspendName" }
                currentSuspendName = null
                currentSuspendType = SuspendType.NONE
                return conveyorRequest
            }

            override suspend fun rideConveyor(
                conveyorRequest: ConveyorRequestIfc,
                destination: IdentityIfc,
                ridePriority: Int,
                suspensionName: String?
            ): Double {
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
                { "The destination (${destination.name}) is not reachable from entry location (${origin.name})" }
                logger.trace { "r = ${model.currentReplicationNumber} : $time > BEGIN: RIDE CONVEYOR : entity_id = ${entity.id} : conveyor (${conveyor.name}) : from ${origin.name} to ${destination.name} : suspension name = $currentSuspendName" }
                // schedules the need to ride the conveyor
                conveyor.scheduleConveyAction(conveyorRequest as Conveyor.ConveyorRequest, destination, ridePriority)
                isMoving = true
                // holds here while request rides on the conveyor
                val timeStarted = time
                hold(conveyor.conveyorHoldQ, suspensionName = "$suspensionName:RIDE:${conveyor.conveyorHoldQ.name}")
                isMoving = false
                if (destination is LocationIfc) {
                    currentLocation = destination
                }
                logger.trace { "r = ${model.currentReplicationNumber} : $time > END: RIDE CONVEYOR : entity_id = ${entity.id} : conveyor (${conveyor.name}) : from ${origin.name} to ${destination.name} : suspension name = $currentSuspendName" }
                currentSuspendName = null
                currentSuspendType = SuspendType.NONE
                return (time - timeStarted)
            }

            override suspend fun exitConveyor(
                conveyorRequest: ConveyorRequestIfc,
                exitPriority: Int,
                suspensionName: String?
            ) {
                require(entity.conveyorRequest != null) { "The entity attempted to exit without using the conveyor." }
                require(entity.conveyorRequest == conveyorRequest) { "The exiting entity does not own the supplied conveyor request" }
                require(conveyorRequest.isBlockingEntry || conveyorRequest.isBlockingExit)
                { "The supplied request is not blocking an entry (${conveyorRequest.isBlockingEntry}) or exit (${conveyorRequest.isBlockingExit}) location" }
                currentSuspendName = suspensionName
                currentSuspendType = SuspendType.EXIT
                val conveyor = conveyorRequest.conveyor
                logger.trace { "r = ${model.currentReplicationNumber} : $time > BEGIN: EXIT CONVEYOR : entity_id = ${entity.id} : conveyor = ${conveyor.name} : suspension name = $currentSuspendName" }
                // schedules the need to exit the conveyor
                conveyor.scheduleExitAction(conveyorRequest as Conveyor.ConveyorRequest, exitPriority)
                isMoving = true
                // hold here while entity exits the conveyor
                hold(conveyor.conveyorHoldQ, suspensionName = "$suspensionName:EXIT:${conveyor.conveyorHoldQ.name}")
                isMoving = false
                entity.conveyorRequest = null
                logger.trace { "r = ${model.currentReplicationNumber} : $time > END: EXIT CONVEYOR : entity_id = ${entity.id} : conveyor = ${conveyor.name} : suspension name = $currentSuspendName" }
                currentSuspendName = null
                currentSuspendType = SuspendType.NONE
            }

            /**
             *  This function is called from the Kotlin coroutine resumeWith() function
             *  after a KSL process completes.  If the completing process has processes
             *  that are blocked (until) its completion, then those processes are notified
             *  that the completing process has completed (successfully).  The blocked
             *  processes can then react accordingly.
             */
            private fun notifyBlockedCompletionListenersOfCompletion() {
                // push completion notification to suspended entities
                if (blockedUntilCompletionListeners != null) {
                    for (blockedProcess in blockedUntilCompletionListeners!!) {
                        // call the blocked process with the reference to the completing process
                        blockedProcess.blockingUntilProcessCompleted(this)
                    }
                }
            }

            /**
             *   This function is called when a completing process has processes that are blocked
             *   (until) completion. This function handles what the **blocked** process should do
             *   when the process it is waiting on completes. The functionality in this routine
             *   applied to the blocked process.  The behavior is to resume if all processes
             *   for which this process was blocked for have completed. If so, it resumes itself.
             */
            private fun blockingUntilProcessCompleted(completedProcess: ProcessCoroutine) {
                // ***** we are inside the blocked process *****
                if (blockingUntilCompletedSet != null) {
                    // completed process is completed so remove it from set that this process is blocking for
                    blockingUntilCompletedSet!!.remove(completedProcess)
                    // if all have completed, we can resume
                    if (blockingUntilCompletedSet!!.isEmpty()) {
                        // all processes that this process was blocked for have completed
                        // this process can resume
                        if (isSuspended && entity.isBlockedUntilCompletion) {
                            entity.resumeProcess(priority = resumptionPriority)
                        }
                    }
                }
            }

            /**
             *  This function is called from within the internal Kotlin coroutine library when the
             *  coroutine has completed. That is, the coroutine has reached it end and returned
             *  a value from its last suspension point (or returned). The result indicates if the coroutine stopped successfully
             *  or with a failure. After this call occurs the coroutine is done.
             */
            override fun resumeWith(result: Result<Unit>) {
                //TODO the coroutine resumeWith function

                // Resumes the execution of the corresponding coroutine passing a successful or failed result
                // as the return value of the last suspension point.
                logger.trace { "r = ${model.currentReplicationNumber} : $time > The coroutine process ${this@ProcessCoroutine} completed with result = $result" }
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
                    // notify any blocked completion listeners that the current process completed
                    notifyBlockedCompletionListenersOfCompletion()
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
                                // since the calling process is suspended, it will be in the suspendedEntities list
                                // termination, leaves it in the list
                                // thus terminated entities may be in the suspendedEntities list
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
                                // since the called process is suspended, it will be in the suspendedEntities list
                                // thus terminated entities may be in the suspendedEntities list
                                calledProcess!!.terminate()
                            }
                            calledProcess = null
                        }
                        // need to terminate any processes that are blocking until its completion via blockUntilCompleted() calls
                        // this terminates any "child" processes that are blocking first and then the parent
                        if (blockedUntilCompletionListeners != null) {
                            for (blockedProcess in blockedUntilCompletionListeners!!) {
                                //TODO call the process with notification of termination
                                if (blockedProcess.isSuspended) {
                                    //println("terminating process $p")
                                    // since the blocked process is suspended, it will be in the suspendedEntities list
                                    // thus terminated entities may be in the suspendedEntities list
                                    blockedProcess.terminate()
                                }
                            }
                            blockedUntilCompletionListeners!!.clear()
                            blockedUntilCompletionListeners = null
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
                    logger.trace { "r = ${model.currentReplicationNumber} : $time > Process $this was terminated for Entity $entity releasing all resources." }
                    releaseAllResources()
                }
                if (isQueued) {
                    //remove it from its queue with no stats
                    @Suppress("UNCHECKED_CAST")
                    // since this is an entity, it must be in a HoldQueue which must hold EntityType.Entity
                    val q = queue!! as Queue<ProcessModel.Entity>
                    q.remove(entity, false)
                    logger.trace { "r = ${model.currentReplicationNumber} : $time > Process $this was terminated for Entity $entity removed from queue ${q.name} ." }
                } else if (isScheduled) {
                    if (myDelayEvent != null) {
                        if (myDelayEvent!!.isScheduled) {
                            logger.trace { "r = ${model.currentReplicationNumber} : $time > Process $this was terminated for Entity $entity delay event was cancelled." }
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
                    logger.trace { "r = ${model.currentReplicationNumber} : $time > EVENT : *** EXECUTING ... : event_id = ${event.id} : DelayAction : entity_id = ${entity.id} : suspension name = $currentSuspendName: BEFORE resume()" }
                    resumeContinuation()
                    logger.trace { "r = ${model.currentReplicationNumber} : $time > EVENT : *** COMPLETED! : event_id = ${event.id} : DelayAction : entity_id = ${entity.id} : suspension name = $currentSuspendName: AFTER resume()" }
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
                    logger.trace { "r = ${model.currentReplicationNumber} : $time > ProcessCoroutine.Created.start() : entity_id = ${entity.id} : ---> resuming initial continuation = $continuation" }
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
                    logger.trace { "r = ${model.currentReplicationNumber} : $time > ProcessCoroutine.Suspended.resume() : entity_id = ${entity.id} : suspension name = $currentSuspendName : resuming..." }
                    logger.trace { "r = ${model.currentReplicationNumber} : $time > ProcessCoroutine.Suspended.resume() : entity_id = ${entity.id} : *** before COROUTINE RESUME *** : process = (${this@ProcessCoroutine})" }
                    logger.trace { "r = ${model.currentReplicationNumber} : $time > ProcessCoroutine.Suspended.resume() : ---> before resuming continuation = $continuation" }
                    continuation?.resume(Unit)
                    logger.trace { "r = ${model.currentReplicationNumber} : $time > ProcessCoroutine.Suspended.resume() : entity_id = ${entity.id} : ---> after resuming continuation = $continuation" }
                    //                    logger.trace { "r = ${model.currentReplicationNumber} : $time > ProcessCoroutine.Suspended.resume() : entity_id = ${entity.id}: *** after COROUTINE RESUME ***: continuation = ${continuation}"}
                    //                    logger.trace { "r = ${model.currentReplicationNumber} : $time > ProcessCoroutine.Suspended.resume() : entity_id = ${entity.id}: suspension name = $currentSuspendName: resumed" }
                }

                /**
                 *  @param afterTermination a function to invoke after the process is successfully terminated
                 */
                override fun terminate(afterTermination: ((entity: ProcessModel.Entity) -> Unit)?) {
                    state = terminated
                    //un-capture suspended entities here
                    suspendedEntities.remove(entity)
                    if (blockingUntilCompletedSet != null) {
                        // note that the termination of any process for which it was blocking will have caused its termination
                        // if this process was blocking until other processes completed, then its termination
                        // should cause it to not be blocking and thus its set should be cleared
                        blockingUntilCompletedSet!!.clear()
                        blockingUntilCompletedSet = null
                    }
                    logger.trace { "r = ${model.currentReplicationNumber} : $time > ProcessCoroutine.Suspended.terminate() : entity_id = ${entity.id} terminated process, (${this@ProcessCoroutine}) ..." }
                    //resume with exception
//                    continuation?.resumeWith(Result.failure(ProcessTerminatedException())) // same as below
                    continuation?.resumeWithException(ProcessTerminatedException(afterTermination))
                }
            }

            private inner class Terminated : ProcessState("Terminated")

            private inner class Completed : ProcessState("Completed")
        }

    }

    companion object {
        val logger = KotlinLogging.logger {}
    }
}