package ksl.modeling.entity

import ksl.modeling.queue.Queue
import ksl.modeling.queue.QueueCIfc
import ksl.simulation.ModelElement

interface ResourceWithQCIfc : ResourceCIfc{
    val waitingQ : QueueCIfc<ProcessModel.Entity.Request>
}

/**
 *  A resource is considered busy if at least 1 unit is allocated.  A resource is considered idle if no
 *  units have been allocated.
 *
 * @param parent the containing model element
 * @param capacity the capacity for the resource at the beginning of each replication, must be at least 1
 * @param name the name for the resource
 * @param queue the queue for waiting entities
 * @param collectStateStatistics whether individual state statistics are collected
 */
class ResourceWithQ(
    parent: ModelElement,
    name: String? = null,
    capacity: Int = 1,
    queue: Queue<ProcessModel.Entity.Request>? = null,
    collectStateStatistics: Boolean = false
) : Resource(parent, name, capacity, collectStateStatistics), ResourceWithQCIfc {

    /**
     * Holds the entities that are waiting for allocations of the resource's units
     */
    internal val myWaitingQ: Queue<ProcessModel.Entity.Request>
    init {
        myWaitingQ = queue ?: Queue(this, "${this.name}:Q")
    }
    override val waitingQ : QueueCIfc<ProcessModel.Entity.Request>
        get() = myWaitingQ

    override var defaultReportingOption: Boolean
        get() = super.defaultReportingOption
        set(value) {
            super.defaultReportingOption = value
            myWaitingQ.defaultReportingOption = value
        }

//    override fun deallocate(allocation: Allocation) {
//        require(allocation.amount >= 1) { "The allocation does not have any amount to deallocate" }
//        require(allocation.resource === this) { "The allocations was not on this resource." }
//        require(entityAllocations.contains(allocation.entity)) { "The entity associated with the allocation is not using this resource." }
//        val b: Boolean = entityAllocations[allocation.entity]!!.contains(allocation)
//        require(b) { "The supplied allocation is not currently allocated for this resource." }
//        //need to remove from allocations for the specific entity
//        entityAllocations[allocation.entity]!!.remove(allocation)
//        if (entityAllocations[allocation.entity]!!.isEmpty()) {
//            // no more allocations for this entity, remove it from the map also
//            entityAllocations.remove(allocation.entity)
//        }
//        // give back to the resource
//        myNumBusy.decrement(allocation.amount.toDouble())
//        myUtil.value = fractionBusy
//        if (myNumBusy.value == 0.0) {
//            myState.exit(time)
//            myState = myIdleState
//            myState.enter(time)
//        }
//        // need to also deallocate from the entity
//        allocation.entity.deallocate(allocation)
//        // deallocate the allocation, so it can't be used again
//        allocation.amount = 0
//        allocation.timeDeallocated = time
//        // need to check the queue
//        //TODO there will no longer be a queue, could get the queue from the allocation???
//        if (myWaitingQ.isNotEmpty){
//            val entity = myWaitingQ.removeNext()
//            // resume the entity's process
//            entity!!.resumeProcess()
//        }
//    }

}