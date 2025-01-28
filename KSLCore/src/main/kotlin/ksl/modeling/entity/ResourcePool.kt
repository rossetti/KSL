/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.modeling.entity

import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.permute
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom

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
 * The default is to allocate all available from each resource until amount needed is met
 * in the order in which the resources are listed within the list.
 */
class AllocateInOrderListedRule : ResourceAllocationRuleIfc {
    override fun selectResourceForAllocation(amountNeeded: Int, resourceList: MutableList<Resource>): Map<Resource, Int> {
        return allocateInOrder(amountNeeded, resourceList)
    }
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

/**
 * A ResourcePool represents a list of Resources from which
 * resources can be selected to fill requests made by Entities.
 *
 * Resources are selected according to a ResourceSelectionRule.
 * The assumption is that any of the resources
 * within the pool may be used to fill the request.
 *
 * If no selection rule is supplied the pool selects a list of resources
 * that can fully satisfy the request and makes allocations to the resources based on
 * the order in which they are listed in the pool.
 *
 * @param parent the parent model element
 * @param poolResources a list of resources to be included in the pool
 * @param name the name of the pool
 * @author rossetti
 */
open class ResourcePool(
    parent: ModelElement,
    poolResources: List<Resource>,
    name: String? = null
) : AbstractResourcePool<Resource>(parent, name) {

    init {
        for (r in poolResources) {
            addResource(r)
        }
    }

    /**
     *  Adds a resource to the pool. The model must not be running when adding a resource.
     *  @param resource the resource to add
     */
    override fun addResource(resource: Resource) {
        super.addResource(resource)
        resource.myResourcePools.add(this)
    }

    var initialDefaultResourceSelectionRule: ResourceSelectionRuleIfc = ResourceSelectionRule()
        set(value) {
            require(model.isNotRunning) {"Changing the initial resource selection rule during a replication will cause replications to not have the same starting conditions"}
            field = value
        }

    var defaultResourceSelectionRule: ResourceSelectionRuleIfc = initialDefaultResourceSelectionRule
        set(value) {
            field = value
            if (model.isRunning){
                Model.logger.warn { "Changing the initial resource selection rule during a replication will only effect the current replication." }
            }
        }

    var initialDefaultResourceAllocationRule: ResourceAllocationRuleIfc = AllocateInOrderListedRule()
        set(value) {
            require(model.isNotRunning) {"Changing the initial resource allocation rule during a replication will cause replications to not have the same starting conditions"}
            field = value
        }

    var defaultResourceAllocationRule: ResourceAllocationRuleIfc = initialDefaultResourceAllocationRule
        set(value) {
            field = value
            if (model.isRunning){
                Model.logger.warn { "Changing the initial resource allocation rule during a replication will only effect the current replication." }
            }
        }

    override fun initialize() {
        require(myResources.isNotEmpty()) { "There were no resources in resource pool ${this.name} during initialization" }
        defaultResourceSelectionRule = initialDefaultResourceSelectionRule
        defaultResourceAllocationRule = initialDefaultResourceAllocationRule
    }

    /** Uses the pool's resource selection rule to select resources from those
     *  that are available that have enough units available to satisfy the request in full.
     *  If there are insufficient resources in the pool to satisfy the full amount, then
     *  the returned list will be empty.  In general, the returned list may have more
     *  units available than the requested amount.
     *
     * @param amountNeeded the amount needed by a request
     * @param resourceSelectionRule the resource selection rule to use for selecting the resources
     * @return a list, which may be empty, that has resources that can satisfy the requested amount
     */
    protected open fun selectResources(
        resourceSelectionRule: ResourceSelectionRuleIfc,
        amountNeeded: Int
    ): MutableList<Resource> {
        // this is where the selection rule is applied
        return resourceSelectionRule.selectResources(amountNeeded, findAvailableResources())
    }

    protected open fun makeAllocations(
        resourceAllocationRule: ResourceAllocationRuleIfc,
        amountNeeded: Int,
        resourceList: MutableList<Resource>
    ): Map<Resource, Int> {
        require(resourceList.isNotEmpty()) { "There must be at least one resource available to make an allocation" }
        // this is where the allocation rule is applied
        return resourceAllocationRule.selectResourceForAllocation(amountNeeded, resourceList)
    }

    /** For use, before calling allocate()
     *
     * @param amountNeeded amount needed by the request
     * @return true if and only if resources can be selected according to the current resource selection rule
     * that will have sufficient amount available to fill the request
     */
    fun canAllocate(resourceSelectionRule: ResourceSelectionRuleIfc, amountNeeded: Int): Boolean {
        // this causes the selection rule to be invoked to see if resources are available
        return selectResources(resourceSelectionRule, amountNeeded).isNotEmpty()
    }

    /**
     * It is an error to attempt to allocate resource units to an entity if there are insufficient
     * units available. Thus, the amount requested must be less than or equal to the number of units
     * available at the time of this call.
     *
     * @param entity the entity that is requesting the units
     * @param amountNeeded that amount to allocate, must be greater than or equal to 1
     * @param allocationName an optional name for the allocation
     * @param queue the queue associated with the allocation.  That is, where the entities would have had
     * to wait if the allocation was not immediately filled
     * @param resourceSelectionRule The rule to use to select resources to allocate from
     * @param resourceAllocationRule The rule to use to determine the resources to allocate from given the selected resources
     * @return an allocation representing that the units have been allocated to the entity. The reference
     * to this allocation is necessary in order to deallocate the allocated units.
     */
    fun allocate(
        entity: ProcessModel.Entity,
        amountNeeded: Int = 1,
        queue: RequestQ,
        resourceSelectionRule: ResourceSelectionRuleIfc = defaultResourceSelectionRule,
        resourceAllocationRule: ResourceAllocationRuleIfc = defaultResourceAllocationRule,
        allocationName: String? = null
    ): ResourcePoolAllocation {
        // This causes both the selection rule and the allocation rule to be invoked
        require(amountNeeded >= 1) { "The amount to allocate must be >= 1" }
        check(numAvailableUnits >= amountNeeded) { "The amount requested, $amountNeeded must be <= the number of units available, $numAvailableUnits" }
        // this should select enough resources to meet the request based on how much they have available
        val list = selectResources(resourceSelectionRule, amountNeeded)
        check(list.isNotEmpty()) { "There were no resources selected to allocate the $amountNeeded units requested, using the current selection rule" }
        ProcessModel.logger.trace { "There were ${list.size} resources selected that can allocate $amountNeeded units to the request, using the current selection rule." }
        val a = ResourcePoolAllocation(entity, this, amountNeeded, queue, allocationName)
        val resourceIntMap = makeAllocations(resourceAllocationRule, amountNeeded, list)
        ProcessModel.logger.trace { "There were ${resourceIntMap.size} allocations made to meet the $amountNeeded units needed." }
        for ((resource, amt) in resourceIntMap) {
            val ra = resource.allocate(entity, amt, queue, allocationName)
            a.myAllocations.add(ra)
            ProcessModel.logger.trace { "Resource ${resource.name} allocated $amt unit from the pool." }
        }
        return a
    }

    fun deallocate(poolAllocation: ResourcePoolAllocation) {
        for (allocation in poolAllocation.allocations) {
            ProcessModel.logger.trace { "Resource Pool $name is deallocating from resource ${allocation.myResource.name}" }
            allocation.myResource.deallocate(allocation)
        }
    }

}