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

enum class CapacityChangeRule {
    WAIT, IGNORE
}

interface ResourceWithQCIfc : ResourceCIfc {
    val waitingQ: QueueCIfc<ProcessModel.Entity.Request>
}

/**
 *  A resource is considered busy if at least 1 unit is allocated.  A resource is considered idle if no
 *  units have been allocated. The capacity can be changed during a replication; however, the capacity of
 *  every replication starts at the same initial capacity.
 *
 *  A resource is considered inactive if all of its units of capacity are inactive. That is, a resource is
 *  inactive if its capacity is zero.  Capacity can only become 0 via the use of a CapacitySchedule or
 *  via the use of a CapacityChangeNotice.  A resource that is inactive can be seized.  If a request for units occurs
 *  when the resource is inactive, the request waits (as usual) until it can be fulfilled.
 *
 * @param parent the containing model element
 * @param capacity the capacity for the resource at the beginning of each replication, must be at least 1
 * @param name the name for the resource
 * @param queue the queue for waiting entities. If a request for units cannot immediately be met, then this is where
 * the request waits.  If a queue is not supplied, a default queue will be created.  Supplying a queue allows
 * resources to share request queues.
 * @param collectStateStatistics whether individual state statistics are collected
 */
open class ResourceWithQ(
    parent: ModelElement,
    name: String? = null,
    capacity: Int = 1,
    queue: RequestQ? = null,
    collectStateStatistics: Boolean = false,
) : Resource(parent, name, capacity, collectStateStatistics), ResourceWithQCIfc {

    private var myNoticeCount = 0
    private var myCapacitySchedule: CapacitySchedule? = null
    private var myCapacityChangeListener: CapacityChangeListenerIfc? = null
    private val myWaitingChangeNotices = mutableListOf<CapacityChangeNotice>()
    private var myCurrentChangeNotice: CapacityChangeNotice? = null
    private var myEndCapacityChangeEvent: KSLEvent<CapacityChangeNotice>? = null

    /**
     * The default rule is IGNORE. This can be changed when a CapacitySchedule
     * is used.
     */
    var capacityChangeRule: CapacityChangeRule = CapacityChangeRule.IGNORE
        private set

    /**
     * Indicates whether capacity changes are pending. The resource cannot
     * allocate units when capacity changes are pending because released
     * busy units will be used to fill the capacity change.
     */
    val isPendingCapacityChange
        get() = myCurrentChangeNotice != null

    override val numAvailableUnits: Int
        get() = if (isInactive || isPendingCapacityChange) {
            0
        } else {
            // because capacity can be decrease when there are busy units
            // we need to prevent the number of available units from being negative
            // the capacity may be reduced but the state not yet changed to inactive
            maxOf(0, capacity - numBusy)
        }

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

    override fun afterReplication() {
        super.afterReplication()
        myWaitingChangeNotices.clear()
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
    fun useSchedule(schedule: CapacitySchedule, changeRule: CapacityChangeRule = CapacityChangeRule.IGNORE) {
        check(model.isNotRunning) { "$time > Tried to change the schedule of $name during replication ${model.currentReplicationNumber}." }
        stopUsingSchedule()
        capacityChangeRule = changeRule
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
        // deallocation completed need to check for pending capacity change
        if (isPendingCapacityChange) {
            // a capacity change is pending and needs units that were deallocated
            val amountNeeded = myCurrentChangeNotice!!.amountNeeded
            // capacity needs to go down by amount needed
            // number busy went down and number available went up by amount released
            val amountReleased = allocation.amount
            val amountToDecrease = minOf(amountReleased, amountNeeded)
            //TODO how is the current state determined
            capacity = capacity - amountToDecrease
            // give the units to the pending change
            myCurrentChangeNotice!!.amountNeeded = myCurrentChangeNotice!!.amountNeeded - amountToDecrease
            // check if pending change has been completely filled
            if (myCurrentChangeNotice!!.amountNeeded == 0) {
                // the capacity change has been filled
                if (capacityChangeRule == CapacityChangeRule.WAIT) {
                    // it does not schedule its duration until it gets all the needed change
                    // schedule the end of its processing
                    myEndCapacityChangeEvent = schedule(
                        this::capacityChangeAction, myCurrentChangeNotice!!.duration,
                        message = myCurrentChangeNotice, priority = myCurrentChangeNotice!!.priority
                    )
                }
                // if the rule was IGNORE, it was previously scheduled, no need to schedule
                // check if there are more changes
                if (myWaitingChangeNotices.isEmpty()) {
                    // no more pending changes
                    myCurrentChangeNotice = null
                } else {
                    // not empty need to process the next one
                    myCurrentChangeNotice = myWaitingChangeNotices.removeFirst()
                    // if the rule is IGNORE what happens
                }
            }
        }
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
        if (myCapacitySchedule != null) {
            // then change must come from the attached schedule
            require(notice.capacitySchedule == myCapacitySchedule) { "The capacity notice did not come from the attached schedule!" }
        }
        // determine if increase or decrease
        if (capacity == notice.capacity) {
            return
        } else if (notice.capacity > capacity) {
            // increasing the capacity
            capacity = notice.capacity
            // this causes the newly available capacity to be allocated to any waiting requests
            myWaitingQ.processWaitingRequests(numAvailableUnits, notice.priority)
            // resource could have been busy, idle, or inactive when adding the capacity
            // adding capacity cannot result in resource being inactive, must be either busy or idle after this
            //TODO how is the current state determined
        } else {
            // notice.capacity < capacity, need to decrease the capacity
            val decrease = capacity - notice.capacity
            if (numAvailableUnits >= decrease) {
                // there are enough available units to handle the change w/o using busy resources
                capacity = capacity - decrease
                // removed idle units, but some may still be idle
                // may still be busy, idle, or if capacity is zero should be inactive
                //TODO how is the current state determined
            } else {
                // not enough available, this means that at least part of the change will need to wait
                // the timing of when the capacity occurs depends on the capacity change rule
                handleWaitingChange(decrease, notice)
            }
        }
    }

    protected fun handleWaitingChange(amountNeeded: Int, notice: CapacityChangeNotice) {
        // numAvailableUnits < amountNeeded, we cannot reduce the capacity until busy units are released
        if (capacityChangeRule == CapacityChangeRule.IGNORE) {
            handleIgnoreRule(amountNeeded, notice)
        } else if (capacityChangeRule == CapacityChangeRule.WAIT) {
            handleWaitRule(amountNeeded, notice)
        }
    }

    /**
     * Any arriving 
     * If there is already a notice being processed, the arriving notice's end time must be
     * after the completion time of the current notice. An incoming notice cannot supersede
     * a notice that is in process.
     * @param amountNeeded the amount needed to complete the reduction in capacity
     * @param notice the capacity change notice that needs the amount
     */
    protected fun handleIgnoreRule(amountNeeded: Int, notice: CapacityChangeNotice) {
        if (myEndCapacityChangeEvent != null) {
            // a change is scheduled, find end time of newly arriving change notice
            val endTime = time + notice.duration
            check(endTime > myEndCapacityChangeEvent!!.time) { "In coming capacity change, $notice, will be scheduled to complete before a pending change $myCurrentChangeNotice" }
        }
        // ignore takes away all needed, immediately, by decreasing the capacity by the full amount of the change
        //TODO how is the current state determined
        capacity = capacity - amountNeeded
        // schedule the end of the change immediately
        myEndCapacityChangeEvent =
            schedule(this::capacityChangeAction, notice.duration, message = notice, priority = notice.priority)
        // if there are no waiting notices, make this the current one
        if (myWaitingChangeNotices.isEmpty()) {
            myCurrentChangeNotice = notice
        } else {
            // if there are already waiting notices, make this new one wait
            myWaitingChangeNotices.add(notice)
        }
    }

    protected fun handleWaitRule(amountNeeded: Int, notice: CapacityChangeNotice) {
        // must decrease capacity, but all required units are busy
        // must wait for units to be released
        // if there are no waiting notices, make this the current one
        // don't schedule its ending until needed units are released
        if (myWaitingChangeNotices.isEmpty()) {
            myCurrentChangeNotice = notice
        } else {
            // if there are already waiting notices, make this new one wait
            myWaitingChangeNotices.add(notice)
        }
    }

    /** Represents the actions that occur when a capacity change's duration
     * is completed.
     *
     * @param event the ending event
     */
    protected fun capacityChangeAction(event: KSLEvent<CapacityChangeNotice>) {
        val endingChangeNotice = event.message!!
        if (capacityChangeRule == CapacityChangeRule.IGNORE) {
            // if ending notice is same as current notice, we can stop the change associated with the current notice
            // if it was not the current, then the ending change notice was previously completed, nothing to do
            if (myCurrentChangeNotice == endingChangeNotice) {
                myCurrentChangeNotice = null
            }
            if (myWaitingChangeNotices.isNotEmpty()) {
                // note that this notice's end event has already been scheduled,
                //  we begin its official processing when releases occur
                myCurrentChangeNotice = myWaitingChangeNotices.removeFirst()
            }
        } else if (capacityChangeRule == CapacityChangeRule.WAIT) {
            // finished processing the current change notice
            myCurrentChangeNotice = null
            // just completed change in full, check if there is a next one
            if (myWaitingChangeNotices.isNotEmpty()) {
                //  we begin its official processing when releases occur
                myCurrentChangeNotice = myWaitingChangeNotices.removeFirst()
                // it does not schedule its processing until it gets all the needed change
            }
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

        var changeEvent: KSLEvent<CapacityChangeNotice>? = null
        var capacitySchedule: CapacitySchedule? = null
            internal set

        val createTime: Double = time
        var amountNeeded: Int = 0
            internal set(value) {
                require(value >= 0) { "The amount needed must be >= 0" }
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

        override fun capacityChange(item: CapacitySchedule.CapacityItem) {
            println("time = ${item.schedule.time} capacity item ${item.name} started with capacity ${item.capacity}")
            // make the capacity change notice using information from CapacityItem
            val notice = CapacityChangeNotice(item.capacity, item.duration, item.priority)
            notice.capacitySchedule = item.schedule
            // tell resource to handle it
            changeCapacity(notice)
        }
    }
}