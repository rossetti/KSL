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
        private val myScheduledState = ScheduledState()
        private val myConditionDelayedState = ConditionDelayedState()
        private val myDormantState = DormantState()
        private val myActiveState = ActiveState()
        private val myDisposedState = DisposedState()
        private var state: EntityState = myCreatedState

        val isCreated: Boolean
            get() = state == myCreatedState
        val isTimeDelayed: Boolean
            get() = state == myScheduledState
        val isConditionDelayed: Boolean
            get() = state == myConditionDelayedState
        val isDormant: Boolean
            get() = state == myDormantState
        val isActive: Boolean
            get() = state == myActiveState
        val isDisposed: Boolean
            get() = state == myDisposedState
        //TODO need to track whether entity is suspended or not
        //TODO need a way to track if entity is running a process or not

        /**
         *  When an entity enters a time delayed state, this property captures the event associated
         *  with the delay action
         */
        private var myDelayEvent: KSLEvent<Nothing>? = null //TODO add fun to cancel

        private var myCurrentProcess: ProcessCoroutine? = null // track the currently executing process
        val hasCurrentProcess: Boolean
            get() = myCurrentProcess != null
        private var myPendingProcess: ProcessCoroutine? = null // if a process has been scheduled to activate
        val hasPendingProcess: Boolean
            get() = myPendingProcess != null

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
         *  This function is used to define via a builder a process for the entity.
         *
         *  Creates the coroutine and immediately suspends it.  To start executing
         *  the created coroutine invoke resume(Unit) on the returned Continuation.
         *  This is the purpose of activate()
         */
        protected fun process(block: suspend KSLProcessBuilder.() -> Unit): KSLProcess {
            val coroutine = ProcessCoroutine()
            coroutine.continuation = block.createCoroutineUnintercepted(receiver = coroutine, completion = coroutine)
            return coroutine
        }

        fun resumeProcess() {
            // entity must be in a process and it must be suspended
            if (myCurrentProcess != null){
                //TODO how to resume it, maybe schedule a resumption at the current time?
                myCurrentProcess!!.resume()
            }

        }

        internal inner class ProcessCoroutine : KSLProcessBuilder, KSLProcess, Continuation<Unit> {
            override val id = (++processCounter)
            //TODO name for process
            internal var continuation: Continuation<Unit>? = null //set with suspending
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
                check(!hasPendingProcess) { "The process cannot be activated for the entity because the entity already has a pending process" }
                check(!hasCurrentProcess) { "The process cannot be activated for the entity because the entity is already running a process" }
                myPendingProcess = this
                logger.trace { "time = $time : entity ${entity.id} scheduled to start process = $id at time ${time + activationTime}" }
                println("time = $time : entity ${entity.id} scheduled to start process = $id at time ${time + activationTime}")
                return myActivationAction.schedule(activationTime, this, priority)
            }

            private inner class ActivateAction : EventAction<KSLProcess>() {
                override fun action(event: KSLEvent<KSLProcess>) {
                    runProcess()
                }
            }

            /**
             * This method is called when the entity's process is activated for the
             * first time.
             */
            private fun runProcess() {
                myPendingProcess = null
                logger.trace { "time = $time : entity ${entity.id} running process" }
                println("time = $time : entity ${entity.id} running process")
                run() //this returns when the first suspension point of the process occurs
                println("right after the run() in runProcess()")
                //TODO after the process runs it ends up here
                // determine what to do next, if nothing then dispose of entity
            }

            internal fun run() {
                state.run()
            }

            //TODO need to manage entity state specifications within these methods

            override fun resume() {
                logger.trace { "time = $time : entity ${entity.id} resumed ..." }
                println("time = $time : entity ${entity.id} resumed ...")
                //TODO maybe protected or internal so that only entity can call it and can be called by this instance
                state.resume()
            }

            override suspend fun suspend(resumer: ProcessResumer) {
                logger.trace { "time = $time : entity ${entity.id} suspended ..." }
                println("\t time = $time : entity ${entity.id} suspended ...")
                state.suspend(resumer)
                return suspendCoroutineUninterceptedOrReturn<Unit> { cont ->
                    continuation = cont
                    COROUTINE_SUSPENDED
                }
            }

            override suspend fun waitFor(signal: Signal, priority: Int) {
                // if signal is on/true then just return
                // if signal is off/false then suspend
                // need to register with the signal before suspending
                TODO("Not yet implemented")
            }

            override suspend fun seize(resource: Resource, numRequested: Int, priority: Int): Allocation {
                logger.trace { "time = $time : entity ${entity.id} seizing $numRequested units of ${resource.name}" }
                resource.enqueue(entity)
                mySeizeAction.schedule(0.0, priority = priority)
                suspend(selfResumer)
                if (numRequested > resource.numAvailableUnits){
                    // entity is already in the queue waiting for the resource, just suspend
                    suspend(selfResumer) //TODO how to resume
                }
                resource.dequeue(entity)
                return resource.allocate(entity, numRequested)
            }

            override suspend fun delay(delayDuration: Double, delayPriority: Int) {
                require(delayDuration >= 0.0) { "The duration of the delay must be >= 0.0" }
                require(delayDuration.isFinite()) { "The duration of the delay must be finite (cannot be infinite)" }
                if (delayDuration == 0.0) {//TODO maybe just allow a zero delay to go on the calendar
                    return
                }
                // capture the event for possible cancellation
                myDelayEvent = delayAction.schedule(delayDuration, priority = delayPriority)
                logger.trace { "time = $time : entity ${entity.id} delaying for $delayDuration, suspending..." }
                println("\t time = $time : entity ${entity.id} delaying for $delayDuration, suspending...")
                //TODO set entity state
                suspend(selfResumer)
            }

            override fun release(allocation: Allocation) {
                logger.trace { "time = $time : entity ${entity.id} releasing ${allocation.amount} units of ${allocation.resource.name}" }
                allocation.resource.deallocate(allocation)
            }

            override fun resumeWith(result: Result<Unit>) {
                // Resumes the execution of the corresponding coroutine passing a successful or failed result
                // as the return value of the last suspension point.

                //TODO not sure what to do with this
                println("before result.getOrThrow()")
                result.getOrThrow()
                state.complete()
                println("after result.getOrThrow()")
                afterProcess(entity, this)
            }

            private inner class DelayAction : EventAction<Nothing>() {
                override fun action(event: KSLEvent<Nothing>) {
                    logger.trace { "time = $time : entity ${entity.id} exiting delay, resuming ..." }
                    println("time = $time : entity ${entity.id} exiting delay, resuming ...")
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

            open fun active() {
                errorMessage("activate entity")
            }

            open fun scheduled() {
                errorMessage("time delay entity")
            }

            open fun conditionDelay() {
                errorMessage("condition delay entity")
            }

            open fun dormant() {
                errorMessage("make dormant entity")
            }

            open fun dispose() {
                errorMessage("dispose entity")
            }

            private fun errorMessage(routineName: String) {
                val sb = StringBuilder()
                sb.appendLine()
                sb.append("Tried to $routineName ")
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

            override fun scheduled() {
                state = myScheduledState
            }
        }

        private inner class ActiveState : EntityState("Active") {

            override fun scheduled() {
                state = myScheduledState
            }

            override fun conditionDelay() {
                state = myConditionDelayedState
            }

            override fun dormant() {
                state = myDormantState
            }

            override fun dispose() {
                state = myDisposedState
            }
        }

        private inner class ScheduledState : EntityState("Scheduled") {
            override fun active() {
                state = myActiveState
            }
        }

        private inner class ConditionDelayedState : EntityState("ConditionDelayed") {
            override fun active() {
                state = myActiveState
            }
        }

        private inner class DormantState : EntityState("Dormant") {
            override fun active() {
                state = myActiveState
            }
        }

        private inner class DisposedState : EntityState("Disposed")

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
        logger.trace { "time = $time : entity ${entity.id} completed process = ${process.id}" }
        println("time = $time : entity ${entity.id} completed process = ${process.id}")
    }

    companion object : KLoggable {
        override val logger = logger()
    }
}