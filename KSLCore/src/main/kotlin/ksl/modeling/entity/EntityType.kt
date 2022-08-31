package ksl.modeling.entity

import ksl.modeling.queue.QObject
import ksl.simulation.ModelElement

open class EntityType(parent: ModelElement, name: String?) : ModelElement(parent, name) {
    //TODO need to implement

//TODO need careful method to create and start entity in processes

    //TODO need to automatically dispose of entity at end of processes and check if it still has allocations
    // it is an error to dispose of an entity that has allocations

    open inner class Entity(aName: String? = null) : QObject(time, aName) {
        val entityType = this@EntityType

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
            return if (!isUsing(resource)){
                0
            } else {
                resourceAllocations[resource]!!.size
            }
        }

        /**
         * Computes the total number of units of the specified resource that are allocated
         * to the entity.
         */
        fun totalAmountAllocated(resource: Resource) : Int {
            if (!isUsing(resource)){
                return 0
            } else {
                var sum = 0
                for(allocation in resourceAllocations[resource]!!){
                    sum = sum + allocation.amount
                }
                return sum
            }
        }

        internal fun allocate(allocation: Allocation){
            if (!resourceAllocations.contains(allocation.resource)){
                resourceAllocations[allocation.resource] = mutableListOf()
            }
            resourceAllocations[allocation.resource]!!.add(allocation)
            //TODO the entity has been given resources, we should schedule it to resume at the current time
        }

        internal fun deallocate(allocation: Allocation){
            resourceAllocations[allocation.resource]!!.remove(allocation)
            if (resourceAllocations[allocation.resource]!!.isEmpty()){
                resourceAllocations.remove(allocation.resource)
            }
        }
    }
}