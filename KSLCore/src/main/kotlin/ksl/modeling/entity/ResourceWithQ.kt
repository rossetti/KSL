/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
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

import ksl.modeling.queue.QueueCIfc
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import java.util.PriorityQueue

enum class CapacityChangeRule {
    WAIT, IGNORE
}

interface ResourceWithQCIfc : ResourceCIfc {
    val waitingQ: QueueCIfc<ProcessModel.Entity.Request>
}

/**
 *  A resource is considered busy if at least 1 unit is allocated.  A resource is considered idle if no
 *  units have been allocated.
 *
 * @param parent the containing model element
 * @param capacity the capacity for the resource at the beginning of each replication, must be at least 1
 * @param name the name for the resource
 * @param queue the queue for waiting entities
 * @param collectStateStatistics whether individual state statistics are collected
 */
class ResourceWithQ(
    parent: ModelElement,
    name: String? = null,
    capacity: Int = 1,
    queue: RequestQ? = null,
    collectStateStatistics: Boolean = false,
    val capacityChangeRule: CapacityChangeRule = CapacityChangeRule.IGNORE
) : Resource(parent, name, capacity, collectStateStatistics), ResourceWithQCIfc {

    private var myNoticeCount = 0
    private var myCapacitySchedule: CapacitySchedule? = null
    private var myCapacityChangeListener: CapacityChangeListenerIfc? = null
    private val myCapacityChangeAction = CapacityChangeAction()
    private val myWaitingChangeNotices = PriorityQueue<CapacityChangeNotice>()

    /**
     * Holds the entities that are waiting for allocations of the resource's units
     */
    internal val myWaitingQ: RequestQ

    init {
        myWaitingQ = queue ?: RequestQ(this, "${this.name}:Q")
    }

    override val waitingQ: QueueCIfc<ProcessModel.Entity.Request>
        get() = myWaitingQ

    override var defaultReportingOption: Boolean
        get() = super.defaultReportingOption
        set(value) {
            super.defaultReportingOption = value
            myWaitingQ.defaultReportingOption = value
        }

    /**
     *
     * @return true if the resource unit has schedules registered
     */
    fun hasSchedule(): Boolean {
        return myCapacitySchedule != null
    }

    /**
     * Tells the resource to listen and react to capacity changes in the supplied
     * Schedule.
     *
     * @param schedule the schedule to use, must not be null
     */
    fun useSchedule(schedule: CapacitySchedule) {
        stopUsingSchedule()
        myCapacityChangeListener = CapacityChangeListener()
        myCapacitySchedule = schedule
        schedule.addCapacityChangeListener(myCapacityChangeListener!!)
    }

    /**
     * @return true if already using the supplied schedule
     */
    fun isUsingSchedule(schedule: CapacitySchedule): Boolean {
        return myCapacitySchedule == schedule
    }

    /**
     * If the resource is using a schedule, the resource stops listening for
     * capacity changes and is no longer using a schedule. The current capacity
     * will be used for the remainder of the replication.
     */
    fun stopUsingSchedule() {
        if (myCapacitySchedule != null) {
            myCapacitySchedule!!.deleteCapacityChangeListener(myCapacityChangeListener!!)
            myCapacityChangeListener = null
            myCapacitySchedule = null
        }
    }

    override fun deallocate(allocation: Allocation) {
        super.deallocate(allocation)
        val x = 1
        //TODO deallocation completed need to check for pending capacity change
    }

    /**
     *  Handles the start of a change in capacity. If the capacity is increased over its current
     *  value, then the capacity is immediately increased and requests that are waiting
     *  for the resource will be processed to receive allocations from the resource.  If the
     *  capacity is decreased from its current value, then the amount of the decrease is first filled
     *  from idle units.  If there are not enough idle units to complete the decrease, then the change
     *  is processes according to the capacity change rule.
     *
     *  @param notice the value to which the capacity should be set and the duration of the change
     */
    fun changeCapacity(notice: CapacityChangeNotice) {
        // determine if increase or decrease
        if (capacity == notice.capacity) {
            return
        } else if (notice.capacity > capacity) {
            // increasing the capacity
            capacity = notice.capacity
            // this causes the newly available capacity to be allocated to any
            // waiting requests
            myWaitingQ.processWaitingRequests(numAvailableUnits, notice.priority)
            //TODO determine the current state
        } else {
            // notice.capacity < capacity
            // decreasing the capacity
            val amountNeeded = capacity - notice.capacity
            if (numAvailableUnits >= amountNeeded) {
                // there are enough available units to handle the change
                capacity = capacity - amountNeeded
                //TODO determine current state
            } else {
                // not enough available, this means that at least part of the change will need to wait
                // when the capacity is changed depends on the rule!!!!
                handleWaitingChange(amountNeeded, notice)
            }
        }
    }

    private fun handleWaitingChange(amountNeeded: Int, notice: CapacityChangeNotice){

        // numAvailableUnits < amountNeeded
        // take away all available
        //TODO when the capacity is changed actually depends on the rule!!!!
        if (capacityChangeRule == CapacityChangeRule.IGNORE){
            // immediately decrease the capacity by the full amount of the change
            capacity = capacity - amountNeeded
            // handle ignore rule
            // schedule the completion of the change
            // add change to list
        } else {
            // handle wait rule
            // add change to list, end of change does not get scheduled
            // change occurs when all units necessary become released
            // problem! if this change is delayed what happens to the next change?
        }
    }

    private inner class CapacityChangeAction : EventAction<CapacityChangeNotice>(){
        override fun action(event: KSLEvent<CapacityChangeNotice>) {
            TODO("Not yet implemented")
        }
    }

    inner class CapacityChangeNotice(
        val capacity: Int = 0,
        val duration: Double = Double.POSITIVE_INFINITY,
        val priority: Int = KSLEvent.DEFAULT_PRIORITY
    ) : Comparable<CapacityChangeNotice> {
        val id = ++myNoticeCount

        init {
            require(capacity >= 0) { "The capacity cannot be negative" }
            require(duration > 0.0) { "The duration must be > 0.0" }
        }

        var changeEvent : KSLEvent<CapacityChangeNotice>? = null

        val createTime: Double = time
        var amountNeeded: Int = 0
            internal set(value) {
                require(value >= 0){"The amount needed must be >= 0"}
                field = value
            }
        var startTime: Double = createTime
            internal set(value) {
                require(value >= createTime) { "The start time must be >= to the creation time $createTime" }
                field = value
            }

        val endTime
            get() = startTime + duration

        override fun toString(): String {
            return "CapacityChangeNotice(capacity=$capacity, duration=$duration, priority=$priority, createTime=$createTime, startTime=$startTime)"
        }

        /**
         * Returns a negative integer, zero, or a positive integer if this object is
         * less than, equal to, or greater than the specified object.
         *
         * Natural ordering: time, then priority, then order of creation
         *
         * Lower time, lower priority, lower order of creation goes first
         *
         * Throws ClassCastException if the specified object's type prevents it from
         * being compared to this object.
         *
         * Throws RuntimeException if the id's of the objects are the same, but the
         * references are not when compared with equals.
         *
         * Note: This class may have a natural ordering that is inconsistent with
         * equals.
         *
         * @param other The event to compare this event to
         * @return Returns a negative integer, zero, or a positive integer if this
         * object is less than, equal to, or greater than the specified object.
         */
        override operator fun compareTo(other: CapacityChangeNotice): Int {
            // compare time first
            if (endTime < other.endTime) {
                return -1
            }
            if (endTime > other.endTime) {
                return 1
            }

            // times are equal, check priorities
            if (priority < other.priority) {
                return -1
            }
            if (priority > other.priority) {
                return 1
            }

            // time and priorities are equal, compare ids
            // lower id, implies created earlier
            if (id < other.id) {
                return -1
            }
            if (id > other.id) {
                return 1
            }

            // if the ids are equal then the object references must be equal
            // if this is not the case there is a problem
            return if (this == other) {
                0
            } else {
                throw RuntimeException("Id's were equal, but references were not, in CapacityChangeNotice compareTo")
            }
        }
    }

    inner class CapacityChangeListener : CapacityChangeListenerIfc {
        override fun scheduleStarted(schedule: CapacitySchedule) {
            println("time = ${schedule.time} Schedule Started")
            // nothing to do when the schedule starts
        }

        override fun scheduleEnded(schedule: CapacitySchedule) {
            println("time = ${schedule.time} Schedule Ended")
            // nothing to do when the schedule ends
        }

        override fun scheduleItemStarted(item: CapacitySchedule.CapacityItem) {
            println("time = ${item.schedule.time} scheduled item ${item.name} started with capacity ${item.capacity}")
            // make the capacity change notice using information from CapacityItem
            val notice = CapacityChangeNotice(item.capacity, item.duration, item.priority)
            // tell resource to handle it
            changeCapacity(notice)
        }

        override fun scheduleItemEnded(item: CapacitySchedule.CapacityItem) {
            println("time = ${item.schedule.time} scheduled item ${item.name} ended with capacity ${item.capacity}")
            // nothing to do when the item ends
        }

    }
}