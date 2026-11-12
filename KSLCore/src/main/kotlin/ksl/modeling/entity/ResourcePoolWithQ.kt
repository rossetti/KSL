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

import ksl.modeling.queue.QueueCIfc
import ksl.simulation.ModelElement

class ResourcePoolWithQ @JvmOverloads constructor(
    parent: ModelElement,
    resources: List<Resource>,
    queue: RequestQ? = null,
    name: String? = null
) : ResourcePool(parent, resources, name) {
    /**
     * Holds the entities that are waiting for allocations of the resource's units in the pool
     */
    internal val myWaitingQ: RequestQ = queue ?: RequestQ(this, "${this.name}:Q")

    /**
     *  A trigger for units of this pool, waiting in its own queue, for use in a wait for the first of
     *  several things (`waitForFirst`). As the form of `ResourcePool` that takes a queue.
     *
     *  @param amountNeeded the number of units wanted; at least 1
     *  @param seizePriority the priority of the yield a wait with this trigger makes before it starts
     *  @param resourceSelectionRule the rule that selects the members, as for `seize`
     *  @param resourceAllocationRule the rule that allocates among them, as for `seize`
     *  @param recordRenegedWait whether a request that leaves without service records its wait in the
     *  queue's time in queue
     */
    fun seizeTrigger(
        amountNeeded: Int = 1,
        seizePriority: Int = ProcessModel.SEIZE_PRIORITY,
        resourceSelectionRule: ResourceSelectionRuleIfc = defaultResourceSelectionRule,
        resourceAllocationRule: ResourceAllocationRuleIfc = defaultResourceAllocationRule,
        recordRenegedWait: Boolean = true
    ): WaitTrigger<ResourcePoolAllocation> = seizeTrigger(
        amountNeeded, seizePriority, myWaitingQ, resourceSelectionRule, resourceAllocationRule, recordRenegedWait
    )
    init {
        for (resource in myResources) {
            resource.registerCapacityChangeQueue(myWaitingQ)
        }
    }

    val waitingQ: QueueCIfc<ProcessModel.Entity.Request>
        get() = myWaitingQ

    val resourcesWithQ: List<ResourceWithQCIfc>
        get() {
            val list = mutableListOf<ResourceWithQCIfc>()
            for(resource in myResources){
                if (resource is ResourceWithQ){
                    list.add(resource)
                }
            }
            return list
        }


}