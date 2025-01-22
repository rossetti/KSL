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

private var allocationCounter = 0

interface AllocationIfc {

    val id: Int

    /**
     *  The time that the allocation was allocated to its resource
     */
    val timeAllocated: Double

    /**
     *  The time that the allocation was deallocated
     */
    val timeDeallocated: Double

    /**
     * The total elapsed time since allocation if not yet deallocated.  If
     * deallocated, the total time between de-allocation and allocation
     */
    val totalTimeAllocated: Double

    /**
     *  An optional name for the allocation
     */
    val name: String?

    /**
     *  The amount of the allocation representing the units allocated of the resource
     */
    val amount: Int

    /**
     *  True if the allocation is currently allocated to a resource
     */
    val isAllocated: Boolean

    /**
     *  True if no units are allocated
     */
    val isDeallocated: Boolean

    /**
     *  The amount of the allocation that has been released
     */
    val amountReleased: Int

    /**
     *  The queue that held the request associated with the allocation
     */
    val queue: QueueCIfc<ProcessModel.Entity.Request>

    /**
     *  The resource associated with the allocation
     */
    val resource: ResourceCIfc
}

/**
 *  An allocation represents a distinct usage of a resource by an entity with an amount allocated.
 *  Entities can have multiple allocations for the same resource. An allocation is in response
 *  to separate requests for units. Multiple requests by the same entity for units of the
 *  resource result in multiple allocations (when filled).  An allocation is not created until
 *  the requested amount is available.
 *
 *  @param myEntity the entity associated with the allocation
 *  @param myResource the resource associated with the allocation
 *  @param theAmount the amount allocated of the resource to the entity
 *  @param myQueue the queue that the entity had to wait in when requesting the allocation
 *  @param allocationName the name of the allocation
 *  processed by the resource.
 */
class Allocation internal constructor(
    internal val myEntity: ProcessModel.Entity,
    internal val myResource: Resource,
    theAmount: Int = 1,
    internal val myQueue: RequestQ,
    allocationName: String? = null
) : AllocationIfc {
    init {
        require(theAmount >= 1) { "The initial allocation must be >= 1 " }
    }

    override val queue: QueueCIfc<ProcessModel.Entity.Request>
        get() = myQueue

    override val resource: ResourceCIfc
        get() = myResource

    override val id = allocationCounter++

    override val timeAllocated: Double = myResource.time

    override var timeDeallocated: Double = Double.NaN
        private set

    override val totalTimeAllocated: Double
        get() {
            return if (timeDeallocated.isNaN()){
                myResource.time - timeAllocated
            } else {
                timeDeallocated - timeAllocated
            }
        }

    override var name: String? = allocationName
        private set

    override var amount: Int = theAmount
        private set(value) {
            require(value >= 0) { "The amount allocated must be >= 0" }
            field = value
        }

    override val isAllocated: Boolean
        get() = amount > 0

    override val isDeallocated: Boolean
        get() = !isAllocated

    override var amountReleased = 0
        private set

    internal fun deallocate(){
        amountReleased = amount
        amount = 0
        timeDeallocated = myResource.time
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("Entity ")
        sb.append(myEntity.id)
        sb.append(" holds ")
        sb.append(amount)
        sb.append(" units of resource ")
        sb.append(myResource.name)
        return sb.toString()
    }
}