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
import ksl.modeling.variable.AggregateTWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement

enum class CapacityChangeRule {
    WAIT, IGNORE
}

interface ResourceWithQCIfc : ResourceCIfc {
    val waitingQ: QueueCIfc<ProcessModel.Entity.Request>

    /**
     * The number waiting and in service
     */
    val wip: TWResponseCIfc

    /**
     * Tells the resource to listen and react to capacity changes in the supplied
     * schedule.  The model cannot be running when changing the schedule.
     *
     * @param schedule the schedule to use
     * @param changeRule the rule to follow. By default, it is CapacityChangeRule.IGNORE.
     */
    fun useSchedule(schedule: CapacitySchedule, changeRule: CapacityChangeRule = CapacityChangeRule.IGNORE)
}

/**
 *  The request queue notification rule for controlling the order
 *  in which queues are notified for processing requests after a capacity change.
 */
fun interface RequestQueueNotificationRuleIfc {
    fun ruleIterator(set: Set<RequestQ>): Iterator<RequestQ>
}

/**
 *  This request queue notification rule causes the queues holding requests associated with a
 *  resource to be notified in the order in which they were entered into their queues.  That is, a queue
 *  holding a request for the resource will be notified before a queue holding a request the entered
 *  its queue later (in time).
 */
object DefaultRequestQueueNotificationRule : RequestQueueNotificationRuleIfc {
    override fun ruleIterator(set: Set<RequestQ>): Iterator<RequestQ> {
        return set.iterator()
    }
}

/**
 *  A resource is considered busy if at least 1 unit is allocated.  A resource is considered idle if no
 *  units have been allocated. The capacity can be changed during a replication; however, the capacity of
 *  every replication starts at the same initial capacity.
 *
 *  A resource is considered inactive if all of its units of capacity are inactive. That is, a resource is
 *  inactive if its capacity is zero and there are no busy units.  Capacity can only become 0 via the use of a CapacitySchedule or
 *  via the use of a CapacityChangeNotice.  A resource that is inactive can be seized.  If a request for units occurs
 *  when the resource is inactive, the request waits (as usual) until it can be fulfilled.
 *
 *  Define b(t) as the number of units allocated and c(t) as the current capacity of the resource at time t.
 *
 *  If (b(t) = 0 and c(t) = 0) then the resource is considered inactive
 *  If b(t) > 0 and c(t) >= 0, then the resource is busy
 *  If b(t) = 0 and c(t) > 0, then the resource is idle
 *
 *  Note that a resource may be busy when the capacity is 0 because of the timing of capacity changes.
 *
 * @param parent the containing model element
 * @param capacity the capacity for the resource at the beginning of each replication, must be at least 1
 * @param name the name for the resource
 * @param queue the queue for waiting entities. If a request for units cannot immediately be met, then this is where
 * the request waits.  If a queue is not supplied, a default queue will be created.  Supplying a queue allows
 * resources to share request queues.
 */
open class ResourceWithQ(
    parent: ModelElement,
    name: String? = null,
    capacity: Int = 1,
    queue: RequestQ? = null,
) : Resource(parent, name, capacity), ResourceWithQCIfc {

    /**
     * Holds the entities that are waiting for allocations of the resource's units
     */
    internal val myWaitingQ: RequestQ = queue ?: RequestQ(this, "${this.name}:Q")
    init {
        registerCapacityChangeQueue(myWaitingQ)
    }
    override val waitingQ: QueueCIfc<ProcessModel.Entity.Request>
        get() = myWaitingQ

    /**
     *  The number waiting plus number in service: Q(t) + B(t)
     */
    protected val myWIP = AggregateTWResponse(this, "${this.name}:WIP")

    override val wip: TWResponseCIfc
        get() = myWIP

    init {
        myWIP.observe(myWaitingQ.numInQ)
        myWIP.observe(myNumBusy)
    }

    override var defaultReportingOption: Boolean
        get() = super.defaultReportingOption
        set(value) {
            super.defaultReportingOption = value
            myWaitingQ.defaultReportingOption = value
        }

    override fun toString(): String {
        return super.toString() + " q(t) = ${myWaitingQ.numInQ.value}"
    }

    companion object{

        /**
         *  Creates the required number of resources that have their own queue, each with the specified
         *  capacity.
         * @param parent the containing model element
         * @param numToCreate the number of resources to create, must be 1 or more
         * @param capacity the capacity for the resource at the beginning of each replication, must be at least 1. The
         * default is 1
         */
        fun createResourcesWithQueues(parent: ModelElement, numToCreate: Int, capacity: Int = 1): List<Resource> {
            require(capacity >= 1) { "The initial capacity of the resource must be >= 1" }
            require(numToCreate >= 1) { "The initial numToCreate must be >= 1" }
            val list = mutableListOf<Resource>()
            for(i in 1..numToCreate){
                list.add(ResourceWithQ(parent, capacity = capacity, name = "${parent.name}:R${i}"))
            }
            return list
        }
    }
}