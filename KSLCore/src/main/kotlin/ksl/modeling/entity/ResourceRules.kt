package ksl.modeling.entity

import ksl.utilities.random.permute
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom

/**
 * An allocation listener is notified whenever the resource is allocated and when the resource
 * is deallocated. This allows general actions to occur when the resource's state changes
 * at these instances in time.
 */
interface AllocationListenerIfc {

    /**
     * @param allocation the allocation that was allocated
     */
    fun allocate(allocation: Allocation)

    /**
     * @param allocation the allocation that was deallocated
     */
    fun deallocate(allocation: Allocation)
}

/**
 * Provides for a method to select resources from a list such that
 * the returned list will contain resources that can fully fill the amount needed
 * or the list will be empty.
 */
fun interface ResourceSelectionRuleIfc {
    /**
     * @param amountNeeded the amount needed from resources
     * @param list of resources to consider selecting from
     * @return the selected list of resources. It may be empty
     */
    fun selectResources(amountNeeded: Int, list: List<Resource>): MutableList<Resource>
}

/**
 *  Function to determine how to allocate requirement for units across
 *  a list of resources that have sufficient available units to meet
 *  the amount needed.
 */
fun interface ResourceAllocationRuleIfc {

    /** The method assumes that the provided list of resources has
     *  enough units available to satisfy the needs of the request.
     *
     * @param amountNeeded the amount needed from resources
     * @param resourceList list of resources to be allocated from. The supplied list must not be empty.
     * @return the amount to allocate from each resource as a map
     */
    fun selectResourceForAllocation(amountNeeded: Int, resourceList: MutableList<Resource>): Map<Resource, Int>
}

/**
 * The default is to allocate all available from each resource until amount needed is met
 * in the order in which the resources are listed within the list.
 */
class AllocateInOrderListedRule : ResourceAllocationRuleIfc {
    override fun selectResourceForAllocation(amountNeeded: Int, resourceList: MutableList<Resource>): Map<Resource, Int> {
        return allocateInOrder(amountNeeded, resourceList)
    }
}

/**
 *  Returns the first resource that can (individually) entirely supply the requested amount.
 *  The return list will have 0 or 1 item.
 */
class FirstFullyAvailableResource : ResourceSelectionRuleIfc {
    override fun selectResources(amountNeeded: Int, list: List<Resource>): MutableList<Resource> {
        require(amountNeeded >= 1) { "The amount needed must be >= 1" }
        val rList = mutableListOf<Resource>()
        for (r in list) {
            if (r.numAvailableUnits >= amountNeeded) {
                rList.add(r)
                break
            }
        }
        return rList
    }
}

/**
 *  Returns a list of resources that have enough available to meet the request. The returned
 *  list will have resources such that the total number of available units is greater than
 *  or equal to the amount of the request. If the returned list is empty, this means that
 *  there were not sufficient available resource units to fully meet the request. It is
 *  important to note that the returned list may have more units available than requested.
 *  Resource allocation rules are used to select from the returned list to specify which of the
 *  list of resources may be allocated to meet the request.
 *
 */
class ResourceSelectionRule : ResourceSelectionRuleIfc {
    override fun selectResources(amountNeeded: Int, list: List<Resource>): MutableList<Resource> {
        require(amountNeeded >= 1) { "The amount needed must be >= 1" }
        if (list.isEmpty()) {
            return mutableListOf()
        }
        var sum = 0
        val rList = mutableListOf<Resource>()
        for (resource in list) {
            if (resource.numAvailableUnits == 0) {
                continue
            } else {
                sum = sum + resource.numAvailableUnits
                rList.add(resource)
            }
        }
        return if (sum >= amountNeeded) {
            rList
        } else {
            mutableListOf()
        }
    }

}

/** Checks if the number of available units in the list is greater than or equal to the
 *  amount needed.
 *
 *  @param amountNeeded must be greater than 0
 *  @param resourceList the list to consider.
 */
fun checkAvailableUnits(amountNeeded: Int, resourceList: List<Resource>): Boolean {
    require(amountNeeded >= 1) { "The amount needed must be >= 1" }
    return resourceList.numAvailableUnits() >= amountNeeded
}

/**
 *  Returns the total number of units that are available within the resources contained in the list.
 */
fun List<Resource>.numAvailableUnits(): Int {
    var sum = 0
    for (resource in this) {
        sum = sum + resource.numAvailableUnits
    }
    return sum
}

/** Filters the list such that the returned list has resources that have
 *  units available for allocation. The returned list may be empty which
 *  indicates that there are no resources in the list that have available units.
 *
 * @return returns a (new) list of resources that have available units. It may be empty.
 */
fun List<Resource>.availableResources(): MutableList<Resource> {
    return findAvailableResources(this)
}

/**
 *  @param amountNeeded must be greater than 0
 *  @return true if the list of resources has available units greater than or equal to the amount needed
 */
fun List<Resource>.hasSufficientAvailableUnits(amountNeeded: Int): Boolean {
    return checkAvailableUnits(amountNeeded, this)
}

/**
 *  Checks if all resources in the list are available.
 *  Throws an exception if any of the resources in the list do not have available units
 */
fun requireAllAvailable(resourceList: List<Resource>) {
    require(resourceList.isNotEmpty()) { "The supplied list of resources was empty. Cannot have any available units" }
    for (resource in resourceList) {
        require(resource.numAvailableUnits > 0) { "A supplied resource, ${resource.name} in the resource list does not have any units available." }
    }
}

/** Returns a map of how many units to allocate to each resource. If a resource is not in the returned
 *  map, then it will not have any units allocated.
 *
 *  @param amountNeeded must be greater than 0
 *  @param resourceList the list to consider. All resources must have available unit and
 *  the total amount available within the list must be greater than or equal to the amount needed
 */
fun allocateInOrder(amountNeeded: Int, resourceList: List<Resource>): Map<Resource, Int> {
    require(amountNeeded >= 1) { "The amount needed must be >= 1" }
    var sum = 0
    for (resource in resourceList) {
        require(resource.numAvailableUnits > 0) { "A supplied resource, ${resource.name} in the resource list does not have any units available." }
        sum = sum + resource.numAvailableUnits
    }
    require(sum >= amountNeeded) { "The resources in the supplied resource list do not have enough units available to make the allocations." }
    val allocations = mutableMapOf<Resource, Int>()
    var needed = amountNeeded
    for (resource in resourceList) {
        val na = minOf(resource.numAvailableUnits, needed)
        allocations[resource] = na
        needed = needed - na
        if (needed == 0) {
            break
        }
    }
    // if value is false
    check(needed == 0) { "There was not enough available to meet amount needed" }
    return allocations
}

/**
 *  This rule first randomly permutes the list and then allocates in the order of the permutation.
 *  In essence, this approach randomly picks from the list.
 *  @param stream the stream to use for randomness
 */
class RandomAllocationRule(val stream: RNStreamIfc) : ResourceAllocationRuleIfc {

    /**
     *  This rule first randomly permutes the list and then allocates in the order of the permutation.
     *  In essence, this approach randomly picks from the list.
     *  @param streamNum the stream number of the stream to use for randomness
     */
    constructor(streamNum: Int) : this(KSLRandom.rnStream(streamNum))

    override fun selectResourceForAllocation(amountNeeded: Int, resourceList: MutableList<Resource>): Map<Resource, Int> {
        resourceList.permute(stream)
        return allocateInOrder(amountNeeded, resourceList)
    }
}

/**
 *  This rule will sort the list according to the comparator and then allocate in the sorted order.
 */
open class ResourceAllocationRule(var comparator: Comparator<Resource>) : ResourceAllocationRuleIfc {
    override fun selectResourceForAllocation(amountNeeded: Int, resourceList: MutableList<Resource>): Map<Resource, Int> {
        resourceList.sortWith(comparator)
        return allocateInOrder(amountNeeded, resourceList)
    }

}

/**
 *  This rule sorts the resources such that list is ordered from least to most utilized and
 *  then allocates in the order listed.
 */
class LeastUtilizedResourceAllocationRule : ResourceAllocationRule(LeastUtilizedComparator())

/**
 * This rule sorts the resources such that this is ordered from least seized to most seized and
 * then allocates in the order listed.
 */
class LeastSeizedResourceAllocationRule : ResourceAllocationRule(LeastSeizedComparator())

/**
 *  When the resources have capacity greater than one, then the resources are sorted
 *  from most capacity available to the least capacity available, and then allocated in the
 *  order listed.
 */
class MostAvailableResourceAllocationRule : ResourceAllocationRule(MostAvailableComparator())

/**
 * @return returns a (new) list of idle resources. It may be empty.
 */
fun <T: Resource>findIdleResources(list: List<T>): MutableList<T> {
    val rList = mutableListOf<T>()
    for (ru in list) {
        if (ru.isIdle) {
            rList.add(ru)
        }
    }
    return rList
}

/** Filters the supplied list such that the returned list has resources that have
 *  units available for allocation.
 *
 * @return returns a (new) list of resources that have available units. It may be empty.
 */
fun <T: Resource>findAvailableResources(list: List<T>): MutableList<T> {
    val rList = mutableListOf<T>()
    for (ru in list) {
        if (ru.hasAvailableUnits) {
            rList.add(ru)
        }
    }
    return rList
}
