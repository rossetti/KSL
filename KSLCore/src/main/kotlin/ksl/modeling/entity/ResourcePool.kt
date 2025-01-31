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

    /** Makes the specified number of single unit resources and includes them in the pool.
     *
     * @param parent the parent model element
     * @param numResources number of single unit resources to include in the pool
     * @param name the name of the pool
     * @author rossetti
     */
    constructor(
        parent: ModelElement,
        numResources: Int = 1,
        name: String? = null
    ) : this(parent, Resource.createResources(parent, numResources, 1), name)

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