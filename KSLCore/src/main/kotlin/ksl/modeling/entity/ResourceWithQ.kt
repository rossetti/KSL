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

interface ResourceWithQCIfc : ResourceCIfc{
    val waitingQ : QueueCIfc<ProcessModel.Entity.Request>
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
    collectStateStatistics: Boolean = false
) : Resource(parent, name, capacity, collectStateStatistics), ResourceWithQCIfc {

    private val mySchedules: MutableMap<CapacitySchedule, CapacityChangeListenerIfc> = mutableMapOf()

    /**
     * Holds the entities that are waiting for allocations of the resource's units
     */
    internal val myWaitingQ: RequestQ
    init {
        myWaitingQ = queue ?: RequestQ(this, "${this.name}:Q")
    }
    override val waitingQ : QueueCIfc<ProcessModel.Entity.Request>
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
    fun hasSchedules(): Boolean {
        return mySchedules.isNotEmpty()
    }

    /**
     * Tells the resource to listen and react to capacity changes in the supplied
     * Schedule.
     *
     * @param schedule the schedule to use, must not be null
     */
    fun useSchedule(schedule: CapacitySchedule) {
        if (isUsingSchedule(schedule)) {
            return
        }
        val scheduleListener = CapacityChangeListener()
        mySchedules[schedule] = scheduleListener
        schedule.addCapacityChangeListener(scheduleListener)
    }

    /**
     * @return true if already using the supplied schedule
     */
    fun isUsingSchedule(schedule: CapacitySchedule): Boolean {
        return mySchedules.containsKey(schedule)
    }

    /**
     * If the resource is using a schedule, the resource stops listening for
     * capacity changes and is no longer using a schedule
     */
    fun stopUsingSchedule(schedule: CapacitySchedule) {
        if (!isUsingSchedule(schedule)) {
            return
        }
        val listenerIfc: CapacityChangeListenerIfc = mySchedules.remove(schedule)!!
        schedule.deleteCapacityChangeListener(listenerIfc)
    }

    inner class CapacityChangeNotice(
        val capacity: Int = 0,
        val duration: Double = Double.POSITIVE_INFINITY,
        var priority: Int = KSLEvent.DEFAULT_PRIORITY
    ) {
        init {
            require(capacity >= 0) { "The capacity cannot be negative" }
            require(duration > 0.0) { "The duration must be > 0.0" }
        }

        val createTime: Double = time
        var startTime: Double = Double.NaN
    }

    private fun handleCapacityChange(notice: CapacityChangeNotice) {
        // determine if increase or decrease
        if (capacity == notice.capacity) {
            return
        } else if (notice.capacity > capacity) {
            // increasing the capacity
            //TODO need to adjust state when setting capacity
            capacity = notice.capacity
            myWaitingQ.processNextRequest(numAvailableUnits, notice.priority)
        } else {
            // notice.capacity < capacity
            // decreasing the capacity
            val amountNeeded = capacity - notice.capacity
            if (numAvailableUnits >= amountNeeded) {
                // there are enough available units to handle the change
                //TODO need to adjust state when setting capacity
                capacity = capacity - amountNeeded
            } else {
                // not enough available
                // numAvailableUnits < amountNeeded
                // take away all available
                //TODO need to adjust state when setting capacity
                capacity = capacity - numAvailableUnits
                //TODO how and when to allocate the still needed
                val stillNeeded = amountNeeded - numAvailableUnits
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
            val notice = CapacityChangeNotice(item.capacity, item.duration)
            // maybe capacity item indicates whether it can wait or not
            // tell resource to handle it
        }

        override fun scheduleItemEnded(item: CapacitySchedule.CapacityItem) {
            println("time = ${item.schedule.time} scheduled item ${item.name} ended with capacity ${item.capacity}")
            // nothing to do when the item ends
        }

    }
}