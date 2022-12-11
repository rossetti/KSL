/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
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

import ksl.modeling.queue.Queue
import ksl.modeling.variable.AggregateTWResponse
import ksl.simulation.ModelElement

interface ResourceSelectionRuleIfc {
    /**
     * @param amountNeeded the amount needed from resources
     * @param list of resources to consider selecting from
     * @return the selected list of resources. It may be empty
     */
    fun selectResources(amountNeeded: Int, list: List<Resource>): List<Resource>
}

/**
 *  Function to determine how to allocate requirement for units across
 *  a list of resources that have sufficient available units to meet
 *  the amount needed.
 */
interface AllocationRuleIfc {

    /** The method assumes that the provided list of resources has
     *  enough units available to satisfy the needs of the request.
     *
     * @param amountNeeded the amount needed from resources
     * @param resourceList of resources to be allocated from
     * @return the amount to allocate to each resource as a map
     */
    fun makeAllocations(amountNeeded: Int, resourceList: List<Resource>): Map<Resource, Int>
}

/**
 *  Returns the first resource that can fully meet the amount needed
 */
class DefaultResourceSelectionRule : ResourceSelectionRuleIfc {
    override fun selectResources(amountNeeded: Int, list: List<Resource>): List<Resource> {
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
 *  Returns a list of resources that have enough available to meet the request
 */
class ResourceSelectionRule : ResourceSelectionRuleIfc {
    override fun selectResources(amountNeeded: Int, list: List<Resource>): List<Resource> {
        require(amountNeeded >= 1) { "The amount needed must be >= 1" }
        val rList = mutableListOf<Resource>()
        var needed = amountNeeded
        for (resource in list) {
            val na = minOf(resource.numAvailableUnits, needed)
            rList.add(resource)
            needed = needed - na
            if (needed == 0) {
                break
            }
        }
        return rList
    }

}

/**
 * The default is to allocate all available from each resource until amount needed is met
 * in the order in which the resources are listed.
 */
class DefaultAllocationRule : AllocationRuleIfc {
    override fun makeAllocations(amountNeeded: Int, resourceList: List<Resource>): Map<Resource, Int> {
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

}

/**
 * @return returns a list of idle resources. It may be empty.
 */
fun findIdleResources(list: List<Resource>): List<Resource> {
    val rList = mutableListOf<Resource>()
    for (ru in list) {
        if (ru.isIdle) {
            rList.add(ru)
        }
    }
    return rList
}

/**
 * @return returns a list of resources that have available capacity. It may be empty.
 */
fun findAvailableResources(list: List<Resource>): List<Resource> {
    val rList = mutableListOf<Resource>()
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
 * If no selection rule is supplied the pool selects the first idle resource
 * that can fully satisfy the request by default.
 *
 * @param parent the parent model element
 * @param resources a list of resources to be included in the pool
 * @param name the name of the pool
 * @author rossetti
 */
open class ResourcePool(parent: ModelElement, resources: List<Resource>, name: String? = null) :
    ModelElement(parent, name) {
    private val myNumBusy: AggregateTWResponse = AggregateTWResponse(this, "${this.name}:NumBusy")
    private val myResources: MutableList<Resource> = mutableListOf()

    val resources: List<Resource>
        get() = myResources.toList()

    var resourceSelectionRule: ResourceSelectionRuleIfc = ResourceSelectionRule()
    var resourceAllocationRule: AllocationRuleIfc = DefaultAllocationRule()
    protected val waitingRequests : MutableList<ProcessModel.Entity.Request> = mutableListOf()

    init {
        for (r in resources) {
            addResource(r)
        }
    }

    /** Makes the specified number of single unit resources and includes them in the pool.
     *
     * @param parent the parent model element
     * @param numResources number of single unit resources to include in the pool
     * @param name the name of the pool
     * @author rossetti
     */
    constructor(parent: ModelElement, numResources: Int = 1, name: String? = null) : this(
        parent,
        mutableListOf(),
        name
    ) {
        for (i in 1..numResources) {
            addResource(Resource(this, "${this.name}:R${i}"))
        }
    }

    internal fun addRequest(request: ProcessModel.Entity.Request){
        waitingRequests.add(request)
    }

    internal fun removeRequest(request: ProcessModel.Entity.Request){
        waitingRequests.remove(request)
    }

    protected fun addResource(resource: Resource) {
        myResources.add(resource)
        myNumBusy.observe(resource.numBusyUnits)
        //TODO stat stuff such as utilization
    }

    val numAvailableUnits: Int
        get() {
            var sum = 0
            for (r in myResources) {
                sum = sum + r.numAvailableUnits
            }
            return sum
        }

    val hasAvailableUnits: Boolean
        get() = numAvailableUnits > 0

    val capacity: Int
        get() {
            var sum = 0
            for (r in myResources) {
                sum = sum + r.capacity
            }
            return sum
        }

    val fractionBusy: Double
        get() {
            return if (capacity == 0) {
                0.0
            } else {
                myNumBusy.value / capacity
            }
        }

    override fun initialize() {
        super.initialize()
        waitingRequests.clear()
    }

    /**
     * @return returns a list of idle resources. It may be empty.
     */
    fun findIdleResources(): List<Resource> {
        return findIdleResources(myResources)
    }

    /**
     * @return returns a list of resources that have available capacity. It may be empty.
     */
    fun findAvailableResources(): List<Resource> {
        return findAvailableResources(myResources)
    }

    /**
     * @param amountNeeded the amount needed by a request
     * @return a list, which may be empty, that has resources that can satisfy the requested amount
     */
    fun selectResources(amountNeeded: Int): List<Resource> {
        return resourceSelectionRule.selectResources(amountNeeded, myResources)
    }

    /** For use, before calling allocate()
     *
     * @param amountNeeded amount needed by the request
     * @return true if and only if resources can be selected according to the current resource selection rule
     * that will have sufficient amount available to fill the request
     */
    fun canFill(amountNeeded: Int): Boolean {
        return selectResources(amountNeeded).isNotEmpty()
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
     * @return an allocation representing that the units have been allocated to the entity. The reference
     * to this allocation is necessary in order to deallocate the allocated units.
     */
    fun allocate(
        entity: ProcessModel.Entity,
        amountNeeded: Int = 1,
        queue: RequestQ,
        allocationName: String? = null
    ): ResourcePoolAllocation {
        require(amountNeeded >= 1) { "The amount to allocate must be >= 1" }
        check(numAvailableUnits >= amountNeeded) { "The amount requested, $amountNeeded must be <= the number of units available, $numAvailableUnits" }
        // default rule only tries to find a single resource, but numAvailableUnits is based on all contained resources
        val list = selectResources(amountNeeded)
        check(list.isNotEmpty()) { "There were no resources selected to allocate the $amountNeeded units requested, using the current selection rule" }
        val a = ResourcePoolAllocation(entity, this, amountNeeded, queue, allocationName)
        val resourceIntMap = resourceAllocationRule.makeAllocations(amountNeeded, list)
        for ((resource, amt) in resourceIntMap) {
            val ra = resource.allocate(entity, amt, queue, allocationName)
            a.myAllocations.add(ra)
        }
        return a
    }

    fun deallocate(poolAllocation: ResourcePoolAllocation) {
        for (allocation in poolAllocation.allocations) {
            allocation.resource.deallocate(allocation)
        }
    }

}