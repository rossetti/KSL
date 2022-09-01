package ksl.modeling.entity

/**
 *  An allocation represents a distinct usage of a resource by an entity with an amount allocated.
 *  Entities can have multiple allocations for the same resource. An allocation is in response
 *  to separate requests for units. Multiple requests by the same entity for units of the
 *  resource result in multiple allocations (when filled).  An allocation is not created until
 *  the requested amount is available.
 */
class Allocation(val entity: EntityType.Entity, val resource: Resource, theAmount: Int = 1) {
    init {
        require(theAmount >= 1) { "The initial allocation must be >= 1 " }
    }

    var amount: Int = theAmount
        internal set(value) {
            require(value >= 0) { "The amount allocated must be >= 0" }
            field = value
        }

    val isAllocated: Boolean
        get() = amount > 0

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