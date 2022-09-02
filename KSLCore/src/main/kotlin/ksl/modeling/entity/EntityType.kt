package ksl.modeling.entity

import ksl.modeling.queue.QObject
import ksl.simulation.ModelElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume

open class EntityType(parent: ModelElement, name: String?) : ModelElement(parent, name) {
    //TODO need to implement

//TODO need careful method to create and start entity in processes

    //TODO need to automatically dispose of entity at end of processes and check if it still has allocations
    // it is an error to dispose of an entity that has allocations

    //TODO issue of multiple inheritance and process, maybe implement ProcessIfc and delegate to ProcessCoroutine
    // maybe the process builder is an inner class to Entity and can only be used there to defined process
    // routines

    open inner class Entity(aName: String? = null) : QObject(time, aName) {

        val entityType = this@EntityType

        //TODO need to track whether entity is suspended or not

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

        // need a function to enable the creation of process routines
        // need to have some function that uses createCoroutineUnintercepted()

        protected inner class ProcessRoutine : ProcessIfc, Continuation<Unit> {//TODO maybe private or protected
            private var continuation : Continuation<Unit>? = null //set with suspending
            val entity: Entity = this@Entity // to facility which entity is in the process routine

            override fun resume() {
                // what to do if the process is not suspended
                continuation?.resume(Unit)
                //TODO("Not yet implemented")
            }

            override suspend fun suspend() {
                // whenever suspended this creates a new continuation, which must be captured for resumption
                return suspendCoroutineUninterceptedOrReturn<Unit> { cont ->
                    continuation = cont
                    COROUTINE_SUSPENDED }
            }

//    override suspend fun waitFor(signal: Signal, priority: Int) {
//        // if signal is on/true then just return
//        // if signal is off/false then suspend
//        // need to register with the signal before suspending
//        TODO("Not yet implemented")
//    }

            override suspend fun seize(resource: Resource, numRequested: Int, priority: Int): Allocation {
                // if the request/task has been allocated then just return
                // otherwise suspend
                TODO("Not yet implemented")
            }

            override suspend fun delay(time: Double, priority: Int) {
                // if time < 0 throw error
                // if time = 0 don't delay, just return
                // if time > 0, then schedule a resume after the delay, and then suspend
                // need to think about what happens if the event associated with this delay is cancelled
                // probably needs to return the event
                TODO("Not yet implemented")
            }

            //TODO consider scheduleResumeAfterDelay()
            // https://github.com/Kotlin/kotlinx.coroutines/blob/3cb61fc44bec51f85abde11f83bc5f556e5e313a/kotlinx-coroutines-core/common/src/Delay.kt

            override fun release(allocation: Allocation) {
                // this is not really a suspending function
                TODO("Not yet implemented")
            }

            override val context: CoroutineContext get() = EmptyCoroutineContext

            override fun resumeWith(result: Result<Unit>) {
                //not sure what to do with this
                println("before result.getOrThrow()")
                result.getOrThrow()
                println("after result.getOrThrow()")
            }
        }

        protected fun testSomething() : ProcessRoutine {
            val coroutine = ProcessRoutine()

            return coroutine
        }
    }

    protected inner class TestEntity : Entity("test") {

    }
}