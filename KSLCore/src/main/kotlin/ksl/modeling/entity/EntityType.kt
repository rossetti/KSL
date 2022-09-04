package ksl.modeling.entity

import ksl.modeling.queue.QObject
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.GetValueIfc
import ksl.utilities.exceptions.IllegalStateException
import java.util.Scanner
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.createCoroutineUnintercepted
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

open class EntityType(parent: ModelElement, name: String?) : ModelElement(parent, name) {
    //TODO need to implement

//TODO need careful method to create and start entity in processes

    //TODO need to automatically dispose of entity at end of processes and check if it still has allocations
    // it is an error to dispose of an entity that has allocations


    open inner class Entity(aName: String? = null) : QObject(time, aName) {
        val entityType = this@EntityType
        private val myCreatedState = CreatedState()
        private val myScheduledState = ScheduledState()
        private val myConditionDelayedState = ConditionDelayedState()
        private val myDormantState = DormantState()
        private val myActiveState = ActiveState()
        private val myDisposedState = DisposedState()
        private var state : EntityState = myCreatedState

        val isCreated: Boolean
            get() = state == myCreatedState
        val isTimeDelayed: Boolean
            get() = state == myScheduledState
        val isConditionDelayed : Boolean
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

        /**
         *  Used to invoke activation of a process
         */
        private val myActivationAction: ActivateAction = ActivateAction()
        private var myCurrentProcess: Process? = null // track the currently executing process

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
            //TODO the entity has been given resources, we should schedule it to resume at the current time
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
        protected fun process(block: suspend ProcessBuilder.() -> Unit): Process {
            val coroutine = ProcessCoroutine()
            coroutine.continuation = block.createCoroutineUnintercepted(receiver = coroutine, completion = coroutine)
            return coroutine
        }

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
        protected fun activate(process: Process, activationTime: GetValueIfc, priority: Int = KSLEvent.DEFAULT_PRIORITY): KSLEvent<Process>{
            return activate(process, activationTime.value, priority)
        }

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
        protected fun activate(process: Process, activationTime: Double = 0.0, priority: Int = KSLEvent.DEFAULT_PRIORITY) : KSLEvent<Process> {
            //TODO maybe pass the entity and save the current process in a field, update state
            // need to prevent call to process's activate until after the scheduled event occurs
            // an entity can only be in 1 process at a time
            return myActivationAction.schedule(activationTime, process, priority)
        }

        private inner class ActivateAction: EventAction<Process>() {
            override fun action(event: KSLEvent<Process>) {
                runProcess(this@Entity, event.message!!)
            }

        }

        // need a way to resume a suspended process

        protected fun resumeProcess(){
            // entity must be in a process and it must be suspended
            // how to resume it

        }

        private fun runProcess(entity: Entity, process: Process) {
            //TODO
            // check if already in a process that is running
           process.activate() // must ensure that this can only be called from here
            // determine what to do next, if nothing then dispose of entity

        }

        protected inner class ProcessCoroutine : ProcessBuilder, Process, Continuation<Unit> {//TODO maybe private or protected
        var continuation : Continuation<Unit>? = null //set with suspending
            val entity: Entity = this@Entity // to facility which entity is in the process routine
            var resumer: ProcessResumer? = null
            private val delayAction = DelayAction()
            val selfResumer: ProcessResumer = SelfResumer()

            override fun activate() {
                println("In activate within ProcessCoroutine")
                //TODO set the entity state and coroutine state
                // capture stuff so that entity can resume
                resume()
            }

            override fun resume() { // maybe protected or internal so that only entity can call it and can be called by this instance
                //TODO need to capture state of entity and of coroutine
                // what to do if the process is not suspended
                continuation?.resume(Unit)
            }

            override suspend fun suspend(resumer: ProcessResumer) {
                // whenever suspended this creates a new continuation, which must be captured for resumption
                //TODO need to capture the state of the entity
                this.resumer = resumer
                return suspendCoroutineUninterceptedOrReturn<Unit> { cont ->
                    continuation = cont
                    COROUTINE_SUSPENDED }
            }

            override suspend fun waitFor(signal: Signal, priority: Int) {
                // if signal is on/true then just return
                // if signal is off/false then suspend
                // need to register with the signal before suspending
                TODO("Not yet implemented")
            }

            override suspend fun seize(resource: Resource, numRequested: Int, priority: Int): Allocation {
                // if the request/task has been allocated then just return
                // otherwise suspend
                TODO("Not yet implemented")
            }

            override suspend fun delay(delayDuration: Double, delayPriority: Int) {
                require(delayDuration >= 0.0) {"The duration of the delay must be >= 0.0"}
                require(delayDuration.isFinite()) {"The duration of the delay must be finite (cannot be infinite)"}
                if (delayDuration == 0.0){
                    return
                }
                // capture the event for possible cancellation
                myDelayEvent = delayAction.schedule(delayDuration, priority = delayPriority)
                //TODO set entity state and coroutine state
                suspend(selfResumer)
            }

            override fun release(allocation: Allocation) {
                allocation.resource.deallocate(allocation)
            }

            override val context: CoroutineContext get() = EmptyCoroutineContext

            override fun resumeWith(result: Result<Unit>) {
                //not sure what to do with this
                println("before result.getOrThrow()")
                result.getOrThrow()
                println("after result.getOrThrow()")
            }

            protected inner class DelayAction: EventAction<Nothing>() {
                override fun action(event: KSLEvent<Nothing>) {
                    selfResumer.resume(entity)
                }

            }

            private inner class SelfResumer: ProcessResumer {
                override fun resume(entity: Entity) {
                    //TODO capture state?
                    continuation?.resume(Unit)
                }
            }

        }

        private open inner class EntityState(val name: String){
            open fun create(){
                errorMessage("create entity")
            }

            open fun active(){
                errorMessage("activate entity")
            }

            open fun scheduled(){
                errorMessage("time delay entity")
            }

            open fun conditionDelay(){
                errorMessage("condition delay entity")
            }

            open fun dormant(){
                errorMessage("make dormant entity")
            }

            open fun dispose(){
                errorMessage("dispose entity")
            }

            private fun errorMessage(routineName: String){
                val sb = StringBuilder()
                sb.appendLine()
                sb.append("Tried to $routineName ")
                sb.append(name)
                sb.append(" from an illegal state: ")
                sb.append(state.toString())
                sb.appendLine()
                sb.append(this@EntityState.toString())
//TODO                ksl.simulation.logger.error { sb.toString() }
                throw IllegalStateException(sb.toString())
            }

            override fun toString(): String {
                return name
            }
        }

        private inner class CreatedState: EntityState("Created") {

            override fun scheduled() {
                state = myScheduledState
            }
        }

        private inner class ActiveState: EntityState("Active") {

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

        private inner class ScheduledState: EntityState("Scheduled") {
            override fun active() {
                state = myActiveState
            }
        }

        private inner class ConditionDelayedState: EntityState("ConditionDelayed") {
            override fun active() {
                state = myActiveState
            }
        }

        private inner class DormantState: EntityState("Dormant") {
            override fun active() {
                state = myActiveState
            }
        }

        private inner class DisposedState : EntityState("Disposed")

    }

}