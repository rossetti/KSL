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

    open inner class Entity(aName: String? = null) : QObject(time, aName), ProcessScope, Continuation<Unit> {

        var continuation: Continuation<Unit>? = null //set with suspending
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

        internal fun allocate(allocation: Allocation) {
            if (!resourceAllocations.contains(allocation.resource)) {
                resourceAllocations[allocation.resource] = mutableListOf()
            }
            resourceAllocations[allocation.resource]!!.add(allocation)
            //TODO the entity has been given resources, we should schedule it to resume at the current time
        }

        internal fun deallocate(allocation: Allocation) {
            resourceAllocations[allocation.resource]!!.remove(allocation)
            if (resourceAllocations[allocation.resource]!!.isEmpty()) {
                resourceAllocations.remove(allocation.resource)
            }
        }

        override suspend fun suspend() {
            // whenever suspended this creates a new continuation, which must be captured for resumption
            return suspendCoroutineUninterceptedOrReturn<Unit> { cont ->
                continuation = cont
                COROUTINE_SUSPENDED }
//            TODO("Not yet implemented")
        }

        override fun resume() {
            //TODO what to do if the process is not suspended
            continuation?.resume(Unit)
            //TODO("Not yet implemented")
        }

//        override suspend fun waitFor(signal: Signal, priority: Int) {
//            TODO("Not yet implemented")
//        }

        override suspend fun seize(resource: Resource, numRequested: Int, priority: Int): Allocation {
            TODO("Not yet implemented")
        }

        override suspend fun delay(time: Double, priority: Int) {
            TODO("Not yet implemented")
        }

        override fun release(resource: Resource, numReleased: Int) {
            TODO("Not yet implemented")
        }

        override val context: CoroutineContext
            get() = EmptyCoroutineContext

        override fun resumeWith(result: Result<Unit>) {
            //TODO not sure what to do with this
//            println("before result.getOrThrow()")
            result.getOrThrow()
//            println("after result.getOrThrow()")
        }

    }

    inner class TestEntity : Entity("test") {
        suspend fun someProcess(){
            this.suspend()//TODO this is allowing me to call these but do I really want that
        }
    }
}