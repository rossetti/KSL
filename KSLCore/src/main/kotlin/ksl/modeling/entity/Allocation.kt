package ksl.modeling.entity

import ksl.modeling.queue.Queue
import ksl.simulation.KSLEvent

/**
 *  An allocation represents a distinct usage of a resource by an entity with an amount allocated.
 *  Entities can have multiple allocations for the same resource. An allocation is in response
 *  to separate requests for units. Multiple requests by the same entity for units of the
 *  resource result in multiple allocations (when filled).  An allocation is not created until
 *  the requested amount is available.
 *  
 *  @param entity the entity associated with the allocation
 *  @param resource the resource associated with the allocation
 *  @param theAmount the amount allocated of the resource to the entity
 *  @param queue the queue that the entity had to wait in when requesting the allocation
 *  @param allocationName the name of the allocation
 */
class Allocation(
    val entity: ProcessModel.Entity,
    val resource: Resource,
    theAmount: Int = 1,
    val queue: RequestQ,
    allocationName: String? = null
) {
    init {
        require(theAmount >= 1) { "The initial allocation must be >= 1 " }
    }

    var allocationPriority: Int = KSLEvent.DEFAULT_PRIORITY - 8

    /**
     *  The time that the allocation was allocated to its resource
     */
    val timeAllocated: Double = resource.time
    var timeDeallocated: Double = Double.NaN //TODO not doing anything with these
        internal set

    /**
     *  An optional name for the allocation
     */
    var name: String? = allocationName
        private set

    /**
     *  The amount of the allocation representing the units allocated of the resource
     */
    var amount: Int = theAmount
        internal set(value) {
            require(value >= 0) { "The amount allocated must be >= 0" }
            field = value
        }

    /**
     *  True if the allocation is currently allocated to a resource
     */
    val isAllocated: Boolean
        get() = amount > 0

    /**
     *  True if no units are allocated
     */
    val isDeallocated: Boolean
        get() = !isAllocated

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("Entity ")
        sb.append(entity.id)
        sb.append(" holds ")
        sb.append(amount)
        sb.append(" units of resource ")
        sb.append(resource.name)
        return sb.toString()
    }
}