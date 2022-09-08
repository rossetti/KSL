package ksl.modeling.entity

import ksl.modeling.queue.QObject
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.GetValueIfc
import ksl.utilities.exceptions.IllegalStateException
import mu.KLoggable
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.createCoroutineUnintercepted
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

open class EntityType(parent: ModelElement, name: String?) : ModelElement(parent, name) {
    //TODO need to implement

//TODO need careful method to create and start entity in processes

    open inner class Entity(aName: String? = null) : QObject(time, aName) {
        private var processCounter = 0
        val entityType = this@EntityType
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

        /**
         *  When an entity enters a time delayed state, this property captures the event associated
         *  with the delay action
         */
        private var myDelayEvent: KSLEvent<Nothing>? = null //TODO add fun to cancel
        private val myResumeAction = ResumeAction()

        private var myCurrentProcess: ProcessCoroutine? = null // track the currently executing process
        val hasCurrentProcess: Boolean
            get() = myCurrentProcess != null
        val currentProcessName : String?
            get() = myCurrentProcess?.name
        private var myPendingProcess: ProcessCoroutine? = null // if a process has been scheduled to activate
        val hasPendingProcess: Boolean
            get() = myPendingProcess != null
        val pendingProcessName : String?
            get() = myPendingProcess?.name

        /**  An entity can be using 0 or more resources.
         *  The key to this map represents the resources that are allocated to this entity.
         *  The element of this map represents the list of allocations allocated to the entity for the give resource.
         */
        private val resourceAllocations: MutableMap<Resource, MutableList<Allocation>> = mutableMapOf()

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
        private fun releaseResource(resource: Resource){
            if (isUsing(resource)){
                val allocations: MutableList<Allocation>? = resourceAllocations[resource]
                val copies = ArrayList(allocations!!)
                for(copy in copies){
                    resource.deallocate(copy)
                }
            }
        }

        /**
         *  Causes the entity to release all allocations for any resource that it
         *  may have
         */
        private fun releaseAllResources(){
            for( r in resourceAllocations.keys){
                releaseResource(r)
            }
        }

        /**
         *  This function is used to define via a builder a process for the entity.
         *
         *  Creates the coroutine and immediately suspends it.  To start executing
         *  the created coroutine use the method activate().
         */
        protected fun process(processName: String? = null, block: suspend KSLProcessBuilder.() -> Unit): KSLProcess {
            val coroutine = ProcessCoroutine(processName)
            coroutine.continuation = block.createCoroutineUnintercepted(receiver = coroutine, completion = coroutine)
            return coroutine
        }

        /**
         *  If the entity is executing a process and the process is suspended, then
         *  the process is scheduled to resume at the current simulation time.
         *  @param priority the priority parameter can be used to provide an ordering to the
         *  scheduled events, if multiple events are scheduled at the same time
         */
        fun resumeProcess(priority: Int = KSLEvent.DEFAULT_PRIORITY) {
            // entity must be in a process and suspended
            if (myCurrentProcess != null){
                myResumeAction.schedule(0.0, priority = priority)
            }
        }

        private inner class ResumeAction: EventAction<Nothing>() {
            override fun action(event: KSLEvent<Nothing>) {
                if (myCurrentProcess != null){
                    myCurrentProcess!!.resume()
                }
            }

        }

        internal inner class ProcessCoroutine(processName: String? = null) : KSLProcessBuilder, KSLProcess, Continuation<Unit> {
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

            val entity: Entity = this@Entity // to facility which entity is in the process routine

            var resumer: ProcessResumer? = null
            private val delayAction = DelayAction()
            val selfResumer: ProcessResumer = SelfResumer()

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
                    runProcess()
                }
            }

            /**
             * This method is called when the entity's current process is activated for the
             * first time.
             */
            private fun runProcess() {
                myPendingProcess = null
                logger.trace { "time = $time : entity ${entity.id} activating and running process, ($this)" }
                entity.state.activate()
                run() //this returns when the first suspension point of the process occurs
                logger.trace { "time = $time : entity ${entity.id} has hit the first suspension point of process, ($this)" }
            }

            internal fun run() {
                state.run()
            }

            //TODO need to manage entity state specifications within these methods

            override fun resume() {
                logger.trace { "time = $time : entity ${entity.id} resumed process, ($this) ..." }
                //TODO maybe protected or internal so that only entity can call it and can be called by this instance
                state.resume()
            }

            override suspend fun suspend(resumer: ProcessResumer) {
                logger.trace { "time = $time : entity ${entity.id} suspended process, ($this) ..." }
                state.suspend(resumer)
                return suspendCoroutineUninterceptedOrReturn<Unit> { cont ->
                    continuation = cont
                    COROUTINE_SUSPENDED
                }
            }

            override suspend fun waitFor(signal: Signal, priority: Int, waitStats: Boolean) {
                logger.trace { "time = $time : entity ${entity.id} waiting for ${signal.name} in process, ($this)" }
                entity.state.waitForSignal()
                signal.hold(entity, priority)
                suspend(selfResumer)
                signal.release(entity, waitStats)
                entity.state.activate()
                logger.trace { "time = $time : entity ${entity.id} released from ${signal.name} in process, ($this)" }
            }

            override suspend fun hold(queue: HoldQueue, priority: Int) {
                entity.state.holdInQueue()
                queue.enqueue(entity, priority)
                suspend(selfResumer)
                entity.state.activate()
            }

            override suspend fun seize(resource: Resource, amountNeeded: Int, priority: Int): Allocation {
                logger.trace { "time = $time : entity ${entity.id} seizing $amountNeeded units of ${resource.name} in process, ($this)" }
                resource.enqueue(entity)
                entity.state.schedule()
                mySeizeAction.schedule(0.0, priority = priority)
                suspend(selfResumer)
                entity.state.activate()
                if (amountNeeded > resource.numAvailableUnits){
                    // entity is already in the queue waiting for the resource, just suspend
                    logger.trace { "time = $time : entity ${entity.id} waiting for $amountNeeded units of ${resource.name} in process, ($this)" }
                    entity.state.waitForResource()
                    suspend(selfResumer) //TODO how to resume
                    entity.state.activate()
                }
                resource.dequeue(entity)
                logger.trace { "time = $time : entity ${entity.id} allocated $amountNeeded units of ${resource.name} in process, ($this)" }
                return resource.allocate(entity, amountNeeded)
            }

            override suspend fun delay(delayDuration: Double, delayPriority: Int) {
                require(delayDuration >= 0.0) { "The duration of the delay must be >= 0.0 in process, ($this)" }
                require(delayDuration.isFinite()) { "The duration of the delay must be finite (cannot be infinite) in process, ($this)" }
//                if (delayDuration == 0.0) {//TODO maybe just allow a zero delay to go on the calendar
//                    return
//                }
                // capture the event for possible cancellation
                myDelayEvent = delayAction.schedule(delayDuration, priority = delayPriority)
                entity.state.schedule()
                logger.trace { "time = $time : entity ${entity.id} delaying for $delayDuration, suspending process, ($this) ..." }
                suspend(selfResumer)
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

                //TODO need to capture failed processes and exceptions correctly
                result.getOrThrow()
                state.complete()
                afterProcess(entity, this)
            }

            override fun toString(): String {
                return "Process(id=$id, name='$name')"
            }

            private inner class DelayAction : EventAction<Nothing>() {
                override fun action(event: KSLEvent<Nothing>) {
                    logger.trace { "time = $time : entity ${entity.id} exiting delay, resuming process, (${this@ProcessCoroutine}) ..." }
                    selfResumer.resume(entity)
                }

            }

            private inner class SeizeAction : EventAction<Nothing>() {
                override fun action(event: KSLEvent<Nothing>) {
                    selfResumer.resume(entity)
                }

            }

            private inner class SelfResumer : ProcessResumer {
                override fun resume(entity: Entity) {
                    resume()
                }
            }

            private abstract inner class ProcessState(val processStateName: String) {

                open fun run() {
                    errorMessage("run process")
                }

                open fun suspend(resumer: ProcessResumer) {
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
                    sb.append("Tried to $routineName ")
                    sb.append(processStateName)
                    sb.append(" from an illegal state: ")
                    sb.append(state.toString())
                    sb.appendLine()
                    sb.append(this@Entity.toString())
                    logger.error { sb.toString() }
                    throw IllegalStateException(sb.toString())
                }
            }

            private inner class Created : ProcessState("Created") {
                override fun run() {
                    myCurrentProcess = this@ProcessCoroutine
                    isActivated = true
                    state = running
                    // this starts the coroutine for the first time, because I used createCoroutineUnintercepted()
                    continuation?.resume(Unit)
                }
            }

            private inner class Running : ProcessState("Running") {
                override fun suspend(resumer: ProcessResumer) {
                    this@ProcessCoroutine.resumer = resumer
                    state = suspended
                }

                override fun complete() {
                    state = completed
                }
            }

            private inner class Suspended : ProcessState("Suspended") {
                override fun resume() {
                    state = running
                    continuation?.resume(Unit)
                }

                override fun terminate() {
                    state = terminated
                }
            }

            private inner class Terminated : ProcessState("Terminated")

            private inner class Completed : ProcessState("Completed")
        }

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

            open fun processEnded(){
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

        private inner class WaitingForResource : EntityState("WaitingForResource"){
            override fun activate() {
                state = myActiveState
            }
        }

    }

    fun activate(
        process: KSLProcess,
        activationTime: GetValueIfc,
        priority: Int = KSLEvent.DEFAULT_PRIORITY
    ): KSLEvent<KSLProcess> {
        return activate(process, activationTime.value, priority)
    }

    fun activate(
        process: KSLProcess,
        activationTime: Double = 0.0,
        priority: Int = KSLEvent.DEFAULT_PRIORITY
    ): KSLEvent<KSLProcess> {
        val c = process as Entity.ProcessCoroutine
        return c.activate(activationTime, priority)
    }

    //TODO need to automatically dispose of entity at end of processes and check if it still has allocations
    // it is an error to dispose of an entity that has allocations

    private fun afterProcess(entity: Entity, process: KSLProcess) {
        logger.trace { "time = $time : entity ${entity.id} completed process = $process" }
    }

    companion object : KLoggable {
        override val logger = logger()
    }
}