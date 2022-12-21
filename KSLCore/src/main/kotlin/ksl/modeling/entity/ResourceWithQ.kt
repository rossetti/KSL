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
import ksl.modeling.variable.TWResponse
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

    private var myNoticeCount = 0
    private var myCapacitySchedule: CapacitySchedule? = null

    private var myCapacityChangeListener: CapacityChangeListenerIfc? = null
    private val myWaitingChangeNotices = mutableListOf<CapacityChangeNotice>()
    private var myCurrentChangeNotice: CapacityChangeNotice? = null
        set(value) {
            field = value
            println("$time > set myCurrentChangeNotice to $field")
        }
//    private var myEndCapacityChangeEvent: KSLEvent<CapacityChangeNotice>? = null

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

    //TODO should this be checking state?? should this just be capacity - numBusy?
    override val numAvailableUnits: Int
        get() = if (isInactive || isPendingCapacityChange ) { //TODO isPendingCapacityChange check causes WAIT rule failure
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
        myCurrentChangeNotice = null
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
            // if there is a capacity change in progress its event needs to be cancelled
            // current change set to null and any waiting changes cleared
            // in the case of the IGNORE rule a waiting change will have already scheduled
            // its end of change event.
            if (isPendingCapacityChange){
                myCurrentChangeNotice?.changeEvent?.cancelled = true
                for(notice in myWaitingChangeNotices){
                    notice.changeEvent?.cancelled = true
                }
                myWaitingChangeNotices.clear()
                myCurrentChangeNotice = null
            }
        }
    }

    override fun deallocate(allocation: Allocation) {
        super.deallocate(allocation)
        // deallocation completed need to check for pending capacity change
        if (isPendingCapacityChange) {
            if (capacityChangeRule == CapacityChangeRule.IGNORE) {
                handeIgnoreRuleDeallocation(allocation)
            } else if (capacityChangeRule == CapacityChangeRule.WAIT) {
                handleWaitRuleDeallocation(allocation)
            }
        }
    }

    protected fun handeIgnoreRuleDeallocation(allocation: Allocation) {
        // a capacity change is pending and needs units that were deallocated
        val amountNeeded = myCurrentChangeNotice!!.amountNeeded
        // number busy went down and number available went up by amount released
        val amountReleased = allocation.amountReleased
        val amountToDecrease = minOf(amountReleased, amountNeeded)
        // give the units to the pending change
        myCurrentChangeNotice!!.amountNeeded = myCurrentChangeNotice!!.amountNeeded - amountToDecrease
        ProcessModel.logger.trace { "$time > Resource: $name, provided $amountToDecrease units to notice $myCurrentChangeNotice" }
        if (myCurrentChangeNotice!!.amountNeeded == 0) {
            // the capacity change has been filled
            ProcessModel.logger.trace { "$time > Resource: $name, notice $myCurrentChangeNotice has been completed" }
            // if the rule was IGNORE, it was previously scheduled, no need to schedule
            // check if there are more changes
            if (myWaitingChangeNotices.isEmpty()) {
                // no more pending changes
                myCurrentChangeNotice = null
                ProcessModel.logger.trace { "$time > Resource: $name, no more pending capacity changes" }
            } else {
                // not empty need to process the next one
                myCurrentChangeNotice = myWaitingChangeNotices.removeFirst()
                ProcessModel.logger.trace { "$time > Resource: $name, starting the processing of $myCurrentChangeNotice" }
            }
        }
    }

    protected fun handleWaitRuleDeallocation(allocation: Allocation) {
        // a capacity change is pending and needs units that were deallocated
        // a capacity change is pending and needs units that were deallocated
        val amountNeeded = myCurrentChangeNotice!!.amountNeeded
        // capacity needs to go down by amount needed
        // number busy went down and number available went up by amount released
        val amountReleased = allocation.amountReleased
        val amountToDecrease = minOf(amountReleased, amountNeeded)
        capacity = capacity - amountToDecrease
        //TODO determine current state
        // decrease in capacity can result in resource being inactive but there still could be busy resources
        // cannot be inactive if there are busy units
        // can be inactive if there are no busy units and capacity is now zero
        // can be idle if there are no busy units and capacity > 0
        if ((capacity == 0) && (numBusy == 0) ){
            myState = myInactiveState
        } else if ((numBusy == 0) && (capacity > 0)){
            myState = myIdleState
        }
        ProcessModel.logger.trace { "$time > Resource: $name, decreased capacity by $amountToDecrease" }
        // give the units to the pending change
        myCurrentChangeNotice!!.amountNeeded = myCurrentChangeNotice!!.amountNeeded - amountToDecrease
        ProcessModel.logger.trace { "$time > Resource: $name, provided $amountToDecrease units to notice $myCurrentChangeNotice" }
        // check if pending change has been completely filled
        if (myCurrentChangeNotice!!.amountNeeded == 0) {
            // the capacity change has been filled
            ProcessModel.logger.trace { "$time > Resource: $name, notice $myCurrentChangeNotice has been completed" }
            // it does not schedule its duration until it gets all the needed change
            // schedule the end of its processing
            myCurrentChangeNotice!!.changeEvent = schedule(
                this::capacityChangeAction, myCurrentChangeNotice!!.duration,
                message = myCurrentChangeNotice, priority = myCurrentChangeNotice!!.priority
            )
            ProcessModel.logger.trace { "$time > Resource: $name, scheduled the duration for notice $myCurrentChangeNotice" }
            // check if there are more changes
            if (myWaitingChangeNotices.isEmpty()) {
                // no more pending changes
                myCurrentChangeNotice = null //TODO this will make it null and we will lose the change event
                ProcessModel.logger.trace { "$time > Resource: $name, no more pending capacity changes" }
            } else {
                // not empty need to process the next one
                myCurrentChangeNotice = myWaitingChangeNotices.removeFirst()
                ProcessModel.logger.trace { "$time > Resource: $name, change notice $myCurrentChangeNotice was waiting but is now being processed" }
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

        if (capacityChangeRule == CapacityChangeRule.IGNORE) {
            handleIncomingChangeNoticeIgnoreRule(notice)
        } else if (capacityChangeRule == CapacityChangeRule.WAIT) {
            handleIncomingChangeNoticeWaitRule(notice)
        }

    }

    protected fun handleIncomingChangeNoticeIgnoreRule(notice: CapacityChangeNotice) {
        // determine if increase or decrease
        if (capacity == notice.capacity) {
            return
        } else if (notice.capacity > capacity) {
            ProcessModel.logger.trace { "$time > Resource: $name, change notice $notice is increasing the capacity from $capacity to ${notice.capacity}." }
            // increasing the capacity
            capacity = notice.capacity
            // resource could have been busy, idle, or inactive when adding the capacity
            // adding capacity cannot result in resource being inactive, must be either busy or idle after this
            val available = capacity - numBusy
            //TODO determine current state
            // state can only be busy, idle, or inactive
            // if busy it stays busy, if idle stays idle
            // if inactive it needs to transition to busy or idle
            if (myState == myInactiveState){
                // actually if it was inactive, it should not have any busy units
                // but just in case, check
                myState = if (numBusy > 0){
                    myBusyState
                } else{
                    myIdleState
                }
            }
            ProcessModel.logger.trace { "$time > Resource: $name, state = ${myState.name}, c(t) = $capacity b(t) = $numBusy a(t) = $available q(t) = ${myWaitingQ.numInQ.value}" }
            // this causes the newly available capacity to be allocated to any waiting requests
            // numAvailable could still 0 because a change notice could be pending, use actual available
//            if (isPendingCapacityChange){
//                throw IllegalStateException("something weird: how can a new capacity change come in when there is a pending one?")
//            }
            val n = myWaitingQ.processWaitingRequests(available, notice.priority)
            ProcessModel.logger.trace { "$time > Resource: processed $n waiting requests for new capacity." }
        } else {
            ProcessModel.logger.trace { "$time > Resource: $name, change notice $notice is decreasing the capacity from $capacity to ${notice.capacity}." }
            // notice.capacity < capacity, need to decrease the capacity
            val decrease = capacity - notice.capacity
            if (numAvailableUnits >= decrease) {
                // there are enough available units to handle the change w/o using busy resources
                capacity = capacity - decrease
                // removed idle units, but remaining units are (busy or idle) or all units have been removed
                // may still be busy, idle, or if capacity is zero should be inactive
                //TODO determine current state
                // if it is busy, it stays busy
                // if it is idle, it stays idle
                // if it has no capacity it should be inactive only if there are also no busy units
                // a resource can be busy if it has no capacity. This may occur if units are busy that are needed for the change
                if ((capacity == 0) && (numBusy == 0) ){
                    myState = myInactiveState
                }
                ProcessModel.logger.trace { "$time > Resource: $name, enough units idle to immediately reduce capacity by $decrease." }
            } else {
                // not enough available, this means that at least part of the change will need to wait
                // the timing of when the capacity occurs depends on the capacity change rule
                ProcessModel.logger.trace { "$time > Resource: $name, not enough units idle to reduce capacity by $decrease." }
                notice.amountNeeded = decrease
                handleWaitingChange(notice)
            }
        }
    }
    protected fun handleIncomingChangeNoticeWaitRule(notice: ResourceWithQ.CapacityChangeNotice) {
        //TODO needs a lot of work
        if (capacity == notice.capacity) {
            return
        } else if (notice.capacity > capacity) {
            ProcessModel.logger.trace { "$time > Resource: $name, change notice $notice is increasing the capacity from $capacity to ${notice.capacity}." }
            // increasing the capacity
            capacity = notice.capacity
            // resource could have been busy, idle, or inactive when adding the capacity
            // adding capacity cannot result in resource being inactive, must be either busy or idle after this
            val available = capacity - numBusy
            //TODO determine current state
            // state can only be busy, idle, or inactive
            // if busy it stays busy, if idle stays idle
            // if inactive it needs to transition to busy or idle
            if (myState == myInactiveState){
                // actually if it was inactive, it should not have any busy units
                // but just in case, check
                myState = if (numBusy > 0){
                    myBusyState
                } else{
                    myIdleState
                }
            }
            ProcessModel.logger.trace { "$time > Resource: $name, state = ${myState.name}, c(t) = $capacity b(t) = $numBusy a(t) = $available q(t) = ${myWaitingQ.numInQ.value}" }
            // this causes the newly available capacity to be allocated to any waiting requests
            // numAvailable could still 0 because a change notice could be pending, use actual available
            if (isPendingCapacityChange){
                throw IllegalStateException("something weird: how can a new capacity change come in when there is a pending one?")
            }
            val n = myWaitingQ.processWaitingRequests(available, notice.priority)
            ProcessModel.logger.trace { "$time > Resource: processed $n waiting requests for new capacity." }
        } else {
            ProcessModel.logger.trace { "$time > Resource: $name, change notice $notice is decreasing the capacity from $capacity to ${notice.capacity}." }
            // notice.capacity < capacity, need to decrease the capacity
            val decrease = capacity - notice.capacity
            if (numAvailableUnits >= decrease) {
                // there are enough available units to handle the change w/o using busy resources
                capacity = capacity - decrease
                // removed idle units, but remaining units are (busy or idle) or all units have been removed
                // may still be busy, idle, or if capacity is zero should be inactive
                //TODO determine current state
                // if it is busy, it stays busy
                // if it is idle, it stays idle
                // if it has no capacity it should be inactive only if there are also no busy units
                // a resource can be busy if it has no capacity. This may occur if units are busy that are needed for the change
                if ((capacity == 0) && (numBusy == 0) ){
                    myState = myInactiveState
                }
                ProcessModel.logger.trace { "$time > Resource: $name, enough units idle to immediately reduce capacity by $decrease." }
            } else {
                // not enough available, this means that at least part of the change will need to wait
                // the timing of when the capacity occurs depends on the capacity change rule
                ProcessModel.logger.trace { "$time > Resource: $name, not enough units idle to reduce capacity by $decrease." }
                notice.amountNeeded = decrease
                handleWaitingChange(notice)
            }
        }
    }

    protected fun handleWaitingChange(notice: CapacityChangeNotice) {
        // numAvailableUnits < amountNeeded, we cannot reduce the capacity until busy units are released
        if (capacityChangeRule == CapacityChangeRule.IGNORE) {
            handleIgnoreRule(notice)
        } else if (capacityChangeRule == CapacityChangeRule.WAIT) {
            handleWaitRule(notice)
        }
    }

    /**
     *  If a change is pending, then the arriving notice must wait before making its capacity changes.
     *
     * If there is already a notice being processed, the arriving notice's end time must be
     * after the completion time of the current notice. An incoming notice cannot supersede
     * a notice that is in process.
     * @param notice the capacity change notice that needs the amount
     */
    protected fun handleIgnoreRule(notice: CapacityChangeNotice) {
        // first validate incoming change notice
        if (isPendingCapacityChange) {
            // a change is scheduled, find end time of newly arriving change notice
            val endTime = time + notice.duration
            val pendingChangeEndTime = myCurrentChangeNotice!!.changeEvent!!.time
            require(endTime > pendingChangeEndTime) { "In coming capacity change, $notice, will be scheduled to complete before a pending change $myCurrentChangeNotice" }
        }
        ProcessModel.logger.trace { "$time > Resource: $name, handling IGNORE rule" }
        // always schedule the end of the incoming change immediately
        // capture the time of the change in the event time
        notice.changeEvent =
            schedule(this::capacityChangeAction, notice.duration, message = notice, priority = notice.priority)
        ProcessModel.logger.trace { "$time > Resource: $name, scheduled end of capacity change for ${notice.changeEvent?.time}" }
        if (isPendingCapacityChange) {
            // there is a pending change in progress and a new change is arriving
            // make the incoming change wait
            myWaitingChangeNotices.add(notice)
            ProcessModel.logger.trace { "$time > Resource: $name, a notice is in progress, incoming notice $notice must wait" }
        } else {
            // no pending change
            // make the notice the current notice for processing
            myCurrentChangeNotice = notice
            ProcessModel.logger.trace { "$time > Resource: $name, notice $notice is now being processed" }
            capacity = capacity - notice.amountNeeded
            // ignore takes away all needed, immediately, by decreasing the capacity by the full amount of the change
            // capacity was decreased but change notice still needs those busy units to be released
            //TODO determine current state
            // decrease in capacity can result in resource being inactive but there still could be busy resources
            // cannot be inactive if there are busy units
            // can be inactive if there are no busy units and capacity is now zero
            // can be idle if there are no busy units and capacity > 0
            // if busy it stays busy
            if ((capacity == 0) && (numBusy == 0) ){
                myState = myInactiveState
            } else if ((numBusy == 0) && (capacity > 0)){
                myState = myIdleState
            }
            ProcessModel.logger.trace { "$time > Resource: $name, reduced capacity to $capacity because of notice $notice" }

        }
    }

    protected fun handleWaitRule(notice: CapacityChangeNotice) {
        // must decrease capacity, but all required units are busy
        // must wait for all needed units to be released
        // if there are no waiting notices, make this the current capacity change notice
        // don't schedule its ending until all needed units are released
        if (isPendingCapacityChange) {
            // there is a change in progress, make incoming change wait
            myWaitingChangeNotices.add(notice)
            ProcessModel.logger.trace { "$time > Resource: $name, notice $myWaitingChangeNotices is in progress, incoming notice $notice must wait" }
        } else {
            // there is no pending change, make this incoming change be the one to process
            myCurrentChangeNotice = notice
            ProcessModel.logger.trace { "$time > Resource: $name, notice $notice is now being processed" }
        }
    }

    /** Represents the actions that occur when a capacity change's duration is completed.
     *
     * @param event the ending event
     */
    protected fun capacityChangeAction(event: KSLEvent<CapacityChangeNotice>) {
        val endingChangeNotice = event.message!!
        ProcessModel.logger.trace { "$time > Resource: $name, notice $endingChangeNotice ended its duration" }
        if (capacityChangeRule == CapacityChangeRule.IGNORE) {
            // if ending notice is same as current notice, we can stop the change associated with the current notice
            // if it was not the current, then the ending change notice was previously completed, nothing to do
            if (myCurrentChangeNotice == endingChangeNotice) {
                myCurrentChangeNotice = null
                if (myWaitingChangeNotices.isNotEmpty()) {
                    // note that the waiting notice's end event has already been scheduled,
                    //  we begin its official processing when releases occur
                    myCurrentChangeNotice = myWaitingChangeNotices.removeFirst()
                    ProcessModel.logger.trace { "$time > Resource: $name, notice $myCurrentChangeNotice was waiting but is now being processed" }
                }
            } else {
                // current notice is not the one that ended.  that means that the ending notice
                // already finished and when it finished the current notice was set
                // the current notice is set to finish some time in the future.
            }
        } else if (capacityChangeRule == CapacityChangeRule.WAIT) {
            // finished processing the current change notice
            myCurrentChangeNotice = null
            // just completed change in full, check if there is a next one
            if (myWaitingChangeNotices.isNotEmpty()) {
                //  we begin its official processing when releases occur
                myCurrentChangeNotice = myWaitingChangeNotices.removeFirst()
                // it does not schedule its processing until it gets all the needed change
                ProcessModel.logger.trace { "$time > Resource: $name, notice $myCurrentChangeNotice was waiting but is now being processed" }
            }
        }
    }

    override fun toString(): String {
        return super.toString() + " q(t) = ${myWaitingQ.numInQ.value}"
    }

    inner class CapacityChangeNotice(
        val capacity: Int = 0,
        val duration: Double = Double.POSITIVE_INFINITY,
        val priority: Int = KSLEvent.DEFAULT_PRIORITY
    ) {
        init {
            require(capacity >= 0) { "The capacity cannot be negative" }
            require(duration > 0.0) { "The duration must be > 0.0" }
        }

        val id = ++myNoticeCount

        var changeEvent: KSLEvent<CapacityChangeNotice>? = null
            internal set

        var capacitySchedule: CapacitySchedule? = null
            internal set

        val createTime: Double = time

        var amountNeeded: Int = 0
            internal set(value) {
                require(value >= 0) { "The amount needed must be >= 0" }
                field = value
            }

        override fun toString(): String {
            return "CapacityChangeNotice(createTime=$createTime, capacity=$capacity, duration=$duration, amount needed = $amountNeeded priority=$priority)"
        }
    }

    inner class CapacityChangeListener : CapacityChangeListenerIfc {
        override fun scheduleStarted(schedule: CapacitySchedule) {
            ProcessModel.logger.trace { "$time > Resource: $name schedule ${schedule.name} started." }
            val n = schedule.model.currentReplicationNumber
            println("Replication: $n")
            println("time = ${schedule.time} Schedule Started")
            // nothing to do when the schedule starts
        }

        override fun scheduleEnded(schedule: CapacitySchedule) {
            ProcessModel.logger.trace { "$time > Resource: $name schedule ${schedule.name} ended." }
            println("time = ${schedule.time} Schedule Ended")
            // nothing to do when the schedule ends
        }

        override fun capacityChange(item: CapacitySchedule.CapacityItem) {
            ProcessModel.logger.trace { "$time > Resource: $name, capacity item ${item.name} started with capacity ${item.capacity} for duration ${item.duration}." }
            println("time = ${item.schedule.time} capacity item ${item.name} started with capacity ${item.capacity} for duration ${item.duration}")
            // make the capacity change notice using information from CapacityItem
            val notice = CapacityChangeNotice(item.capacity, item.duration, item.priority)
            notice.capacitySchedule = item.schedule
            // tell resource to handle it
            changeCapacity(notice)
        }
    }
}