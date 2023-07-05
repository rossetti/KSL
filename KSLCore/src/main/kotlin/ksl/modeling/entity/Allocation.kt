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

import ksl.simulation.KSLEvent

private var allocationCounter = 0

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
 *  processed by the resource. The default actions are supplied by the entity associated with the allocation.
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

    val id = allocationCounter++

    var allocationPriority: Int = KSLEvent.DEFAULT_PRIORITY - 8

    /**
     *  The time that the allocation was allocated to its resource
     */
    val timeAllocated: Double = resource.time
    var timeDeallocated: Double = Double.NaN
        private set

    /**
     * The total elapsed time since allocation if not yet deallocated.  If
     * deallocated, the total time between de-allocation and allocation
     */
    val totalTimeAllocated: Double
        get() {
            return if (timeDeallocated.isNaN()){
                resource.time - timeAllocated
            } else {
                timeDeallocated - timeAllocated
            }
        }

    /**
     *  An optional name for the allocation
     */
    var name: String? = allocationName
        private set

    /**
     *  The amount of the allocation representing the units allocated of the resource
     */
    var amount: Int = theAmount
        private set(value) {
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

    var amountReleased = 0
        private set
    internal fun deallocate(){
        amountReleased = amount
        amount = 0
        timeDeallocated = resource.time
    }

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