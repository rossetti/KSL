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

    protected var myNoticeCount = 0
    protected var myCapacitySchedule: CapacitySchedule? = null

    protected var myCapacityChangeListener: CapacityChangeListenerIfc? = null
    protected val myWaitingChangeNotices = mutableListOf<CapacityChangeNotice>()
    protected var myCurrentChangeNotice: CapacityChangeNotice? = null

    /**
     * The default rule is IGNORE. This can be changed via the useSchedule() function
     * or when there is no schedule being used. The rule cannot be changed when there
     * are pending capacity changes.
     */
    var capacityChangeRule: CapacityChangeRule = CapacityChangeRule.IGNORE
        set(value) {
            //check(model.isNotRunning) { "$time > Tried to change the capacity change rule of $name during replication ${model.currentReplicationNumber}." }
            require(!isUsingSchedule()) { "Cannot change the rule because the resource is already using a capacity change schedule." }
            require(!isPendingCapacityChange) { "Cannot change the rule when there are pending capacity changes." }
            field = value
        }

    /**
     * Indicates whether capacity changes are pending. The resource cannot
     * allocate units when capacity changes are pending because released
     * busy units will be used to fill the capacity change.
     */
    val isPendingCapacityChange
        get() = myCurrentChangeNotice != null

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
     * Schedule.  The model cannot be running when changing the schedule.
     *
     * @param schedule the schedule to use
     * @param changeRule the rule to follow. By default, it is CapacityChangeRule.IGNORE.
     */
    override fun useSchedule(schedule: CapacitySchedule, changeRule: CapacityChangeRule) {
        check(model.isNotRunning) { "$time > Tried to change the schedule of $name during replication ${model.currentReplicationNumber}." }
        stateReportingOption = true
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
     * @return true if already using a schedule
     */
    fun isUsingSchedule(): Boolean {
        return myCapacitySchedule != null
    }

    /**
     * If the resource is using a schedule, the resource stops listening for
     * capacity changes and is no longer using a schedule. The current capacity
     * will be used for the remainder of the replication.
     */
    fun stopUsingSchedule() {
        if (myCapacitySchedule != null) {
            stateReportingOption = false
            myCapacitySchedule!!.deleteCapacityChangeListener(myCapacityChangeListener!!)
            myCapacityChangeListener = null
            myCapacitySchedule = null
            // if there is a capacity change in progress its event needs to be cancelled
            // current change set to null and any waiting changes cleared
            // in the case of the IGNORE rule a waiting change will have already scheduled
            // its end of change event.
            if (isPendingCapacityChange) {
                myCurrentChangeNotice?.changeEvent?.cancel = true
                for (notice in myWaitingChangeNotices) {
                    notice.changeEvent?.cancel = true
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
                // for WAIT rule handle releases only until full change gets allocated and scheduled
                if (myCurrentChangeNotice!!.changeEvent == null) {
                    // once it is not null, the full change has been completed and is being processed
                    handleWaitRuleDeallocation(allocation)
                }
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
                // no need to schedule because already scheduled upon arrival
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
            ProcessModel.logger.trace { "$time > Resource: $name, notice's original start time = ${myCurrentChangeNotice?.createTime}, new end time ${myCurrentChangeNotice!!.changeEvent!!.time}" }
        }
    }

    /**
     *  It is an error to try to change the capacity directly via this method if the
     *  resource is using a capacity change schedule.
     *
     *  The changes are handled based on the specified capacity change rule for the resource.
     *
     *  Handles the start of a change in capacity. If the capacity is increased over its current
     *  value and there are no pending changes, then the capacity is immediately increased and requests that are waiting
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
            ProcessModel.logger.trace { "$time > Resource: $name, handling IGNORE rule" }
            handleIncomingChangeNoticeIgnoreRule(notice)
        } else if (capacityChangeRule == CapacityChangeRule.WAIT) {
            ProcessModel.logger.trace { "$time > Resource: $name, handling WAIT rule" }
            handleIncomingChangeNoticeWaitRule(notice)
        }
    }

    protected fun handleIncomingChangeNoticeIgnoreRule(notice: CapacityChangeNotice) {
        if (isPendingCapacityChange) {
            // capacity change is pending for the IGNORE rule
            if (notice.capacity >= capacity) {
                // notice the above >= with = meaning keep the current capacity
                // positive change with a negative change pending, cancel the pending negative change
                myCurrentChangeNotice?.changeEvent?.cancel = true
                // make there be no pending change after the positive change
                myCurrentChangeNotice = null
                // assume that the positive change cancels all waiting negative changes
                myWaitingChangeNotices.clear()
                // process the positive change now since there is no pending change anymore
                if (notice.capacity > capacity) {
                    processPositiveCapacityChange(notice)
                }
            } else if (notice.capacity < capacity) {
                // negative change with change pending
                // a change is scheduled, find end time of newly arriving negative change notice
                val endTime = time + notice.duration
                val pendingChangeEndTime = myCurrentChangeNotice!!.changeEvent!!.time
                // do not permit an incoming negative change to "interrupt" an in-progress negative change, after is okay
                require(endTime > pendingChangeEndTime) { "In coming negative capacity change, $notice, will be scheduled to complete before a pending change $myCurrentChangeNotice" }
                // always schedule the end of the incoming change immediately
                // capture the time of the change in the event time
                notice.changeEvent =
                    schedule(this::capacityChangeAction, notice.duration, message = notice, priority = notice.priority)
                ProcessModel.logger.trace { "$time > Resource: $name, scheduled end of capacity change for ${notice.changeEvent?.time}" }
                // there is a pending change in progress and a new change is arriving
                // make the incoming change wait
                myWaitingChangeNotices.add(notice)
                ProcessModel.logger.trace { "$time > Resource: $name, a notice is in progress, incoming notice $notice must wait" }
            }
        } else {
            // no capacity change is pending for the IGNORE rule
            if (notice.capacity > capacity) {
                // positive change with no change pending
                processPositiveCapacityChange(notice)
            } else if (notice.capacity < capacity) {
                // negative change with no change pending
                negativeChangeNoPendingChangeIgnoreRule(notice)
            }
            // if equal there is no change to process
        }
    }

    protected fun processPositiveCapacityChange(notice: CapacityChangeNotice) {
        ProcessModel.logger.trace { "$time > Resource: $name, change notice $notice is increasing the capacity from $capacity to ${notice.capacity}." }
        // increasing the capacity immediately
        capacity = notice.capacity
        // resource could have been busy, idle, or inactive when adding the capacity
        // adding capacity cannot result in resource being inactive, must be either busy or idle after this
        val available = capacity - numBusy
        ProcessModel.logger.trace { "$time > Resource: $this" }
        // this causes the newly available capacity to be allocated to any waiting requests
        // this resumes their processes at the current simulated time
        //TODO this requires there to be only one waiting queue to notify
        // this causes limitations because the queue of waiting requests
        // must be known to the resource. Currently, a Resource does not know the queue
        // until allocations are made and at this point allocations are not involved since the entity is waiting in
        // some queue. Once the entity receives units from the resource, we get an allocation.
        // Here, we only know about the requests because ResourceWithQ forces there to be a singular request queue.
        // If there was no access to a request queue, then the waiting requests could not be informed about the new capacity.
        // The main issue is that in general, different seize() calls can use different request queues for the
        // same Resource. Thus, an instance of Resource cannot know what request queues it may be involved with because
        // currently this is not tracked for the resource. This would involve capturing the queue information everytime
        // a seize() call occurs for a resource.  If there are more than one request queues involved with the resource,
        // then what assumptions need to be made to the ordering of their notification of the new capacity must be made?
        // The allocation records the queue that the entity waited in
        // so that release can affect waiting entities for the released allocation
        val n = myWaitingQ.processWaitingRequests(available, notice.priority)
        ProcessModel.logger.trace { "$time > Resource: $name will allocate $n units from the positive capacity change causing $available available units." }
    }

    protected fun notifyWaitingRequestsOfCapacityIncrease(available: Int, priority: Int) {
        require(available >= 0) { "Resource: resource ($name), The amount available was less than 0 for notifications" }
        if (available == 0) {
            logger.trace { "$time > Resource: processed 0 waiting requests for the positive capacity change." }
            return
        }
        // myQueueSet holds the queues that have requests for this resource
        if (myQueueSet.isEmpty()) {
            // no queues are currently associated with this resource, no reason to notify
            logger.trace { "$time > Resource: processed 0 waiting requests for the positive capacity change." }
            return
        }
        if (myQueueSet.size == 1) {
            // there is only one queue, no reason to decide, just notify it
            val queue = myQueueSet.first()
            val n = queue.processWaitingRequests(available, priority)
            logger.trace { "$time > Resource: $name will allocate $n units from the positive capacity change causing $available available units." }
            return
        }
        // there is more than 1 queue to notify, in what order should the notifications occur
        // two logical orderings: 1) the order in which they were added (reflects when request occurred)
        // 2) in descending order of the number of requests for the resource in the queues
        val itr = requestQNotificationRule.ruleIterator(myQueueSet)
        var amountAvailable = available
        while (itr.hasNext()) {
            val queue = itr.next()
            // need to ensure that notifications stop if all available will be allocated
            val n = queue.processWaitingRequests(amountAvailable, priority)
            logger.trace { "$time > Resource: $name will allocate $n units from the positive capacity change causing $available available units." }
            amountAvailable = amountAvailable - n
            // there is no point in notifying after the resource has no units available
            if (amountAvailable == 0) {
                break
            }
        }
    }

    protected fun negativeChangeNoPendingChangeIgnoreRule(notice: CapacityChangeNotice) {
        ProcessModel.logger.trace { "$time > Resource: $name, change notice $notice is decreasing the capacity from $capacity to ${notice.capacity}." }
        // notice.capacity < capacity, need to decrease the capacity
        val decrease = capacity - notice.capacity
        if (numAvailableUnits >= decrease) {
            // there are enough available units to handle the change w/o using busy resources
            capacity = capacity - decrease
            ProcessModel.logger.trace { "$time > Resource: $name, enough units idle to immediately reduce capacity by $decrease." }
        } else {
            // not enough available, ignore rule causes entire change at the current time
            // some of the change will need to be supplied when the busy resources are released
            ProcessModel.logger.trace { "$time > Resource: $name, not enough units idle to reduce capacity by $decrease." }
            notice.amountNeeded = decrease
            // always schedule the end of the incoming change immediately
            // capture the time of the change in the event time
            notice.changeEvent =
                schedule(this::capacityChangeAction, notice.duration, message = notice, priority = notice.priority)
            ProcessModel.logger.trace { "$time > Resource: $name, scheduled end of capacity change for ${notice.changeEvent?.time}" }
            // make the notice the current notice for processing
            myCurrentChangeNotice = notice
            ProcessModel.logger.trace { "$time > Resource: $name, notice $notice is now being processed" }
            capacity = capacity - notice.amountNeeded
            // ignore takes away all needed, immediately, by decreasing the capacity by the full amount of the change
            // capacity was decreased but change notice still needs those busy units to be released
            ProcessModel.logger.trace { "$time > Resource: $name, reduced capacity to $capacity because of notice $notice" }
        }
    }

    protected fun handleIncomingChangeNoticeWaitRule(notice: ResourceWithQ.CapacityChangeNotice) {
        if (isPendingCapacityChange) {
            // there is a change in progress, make incoming change wait
            // a positive change or a negative change must wait for current change to complete
            myWaitingChangeNotices.add(notice)
            ProcessModel.logger.trace { "$time > Resource: $name, notice $myCurrentChangeNotice is in progress." }
            ProcessModel.logger.trace { "$time > Resource: $name, incoming notice $notice must wait." }
        } else {
            // no capacity change is pending for the WAIT rule
            if (notice.capacity > capacity) {
                // positive change with no change pending
                processPositiveCapacityChange(notice)
            } else if (notice.capacity < capacity) {
                // negative change with no change pending
                negativeChangeNoPendingChangeWaitRule(notice)
            }
        }
    }

    private fun negativeChangeNoPendingChangeWaitRule(notice: CapacityChangeNotice) {
        ProcessModel.logger.trace { "$time > Resource: $name, change notice $notice is decreasing the capacity from $capacity to ${notice.capacity}." }
        // notice.capacity < capacity, need to decrease the capacity
        val decrease = capacity - notice.capacity
        if (numAvailableUnits >= decrease) {
            // there are enough available units to handle the change w/o using busy resources
            capacity = capacity - decrease
            // removed idle units, but remaining units are (busy or idle) or all units have been removed
            // may still be busy, idle, or if capacity is zero should be inactive
            // all units needed were allocated, no resulting pending notice
            // change stays until next change arrives
            ProcessModel.logger.trace { "$time > Resource: $name, enough units idle to immediately reduce capacity by $decrease." }
        } else {
            // not enough available, this means that at least part of the change will need to wait
            // must decrease capacity, but all required units are busy
            // must wait for all needed units to be released
            // don't schedule its ending until all needed units are released
            ProcessModel.logger.trace { "$time > Resource: $name, not enough units idle to reduce capacity by $decrease." }
            notice.amountNeeded = decrease
            // there is no current pending change, make this incoming change be the one to process
            // it does not schedule its duration until it gets all the needed change
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
                //  the change could be for positive or negative capacity
                myCurrentChangeNotice = myWaitingChangeNotices.removeFirst()
                ProcessModel.logger.trace { "$time > Resource: $name, notice $myCurrentChangeNotice was waiting but is now being processed" }
                if (myCurrentChangeNotice!!.capacity > capacity) {
                    // positive change after completing previous change
                    processPositiveCapacityChange(myCurrentChangeNotice!!)
                    // schedule the end of the capacity change, has full duration
                    myCurrentChangeNotice!!.changeEvent =
                        schedule(
                            this::capacityChangeAction,
                            myCurrentChangeNotice!!.duration,
                            message = myCurrentChangeNotice!!,
                            priority = myCurrentChangeNotice!!.priority
                        )
                    ProcessModel.logger.trace { "$time > Resource: $name, scheduled end of capacity change for ${myCurrentChangeNotice!!.changeEvent?.time}" }
                } else if (myCurrentChangeNotice!!.capacity < capacity) {
                    // negative change after completing previous change
                    negativeChangeAfterPendingChangeWaitRule(myCurrentChangeNotice!!)
                }
            }
        }
    }

    protected fun negativeChangeAfterPendingChangeWaitRule(notice: CapacityChangeNotice) {
        ProcessModel.logger.trace { "$time > Resource: $name, change notice $notice is decreasing the capacity from $capacity to ${notice.capacity}." }
        // notice.capacity < capacity, need to decrease the capacity
        val decrease = capacity - notice.capacity
        if (numAvailableUnits >= decrease) {
            // there are enough available units to handle the change w/o using busy resources
            capacity = capacity - decrease
            // removed idle units, but remaining units are (busy or idle) or all units have been removed
            // may still be busy, idle, or if capacity is zero should be inactive
            // all units needed were taken by the process change
            ProcessModel.logger.trace { "$time > Resource: $name, enough units idle to immediately reduce capacity by $decrease." }
            // schedule the end of the change, wait rule has full change for duration
            notice.changeEvent =
                schedule(this::capacityChangeAction, notice.duration, message = notice, priority = notice.priority)
            ProcessModel.logger.trace { "$time > Resource: $name, scheduled end of capacity change for ${notice.changeEvent?.time}" }
        } else {
            // not enough available, this means that at least part of the change will need to be in process
            // must decrease capacity, but required units are busy, must wait for needed units to be released
            // don't schedule its duration until all needed units are released
            ProcessModel.logger.trace { "$time > Resource: $name, not enough units idle to reduce capacity by $decrease." }
            notice.amountNeeded = decrease
            ProcessModel.logger.trace { "$time > Resource: $name, notice $notice is now being processed when releases occur." }
        }
    }

    override fun resourceBecameInactive() {
        super.resourceBecameInactive()
        for (request in myWaitingQ) {
            request.entity.resourceBecameInactiveWhileWaitingInQueueWithSeizeRequestInternal(myWaitingQ, this, request)
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
            // nothing to do when the schedule starts
        }

        override fun scheduleEnded(schedule: CapacitySchedule) {
            ProcessModel.logger.trace { "$time > Resource: $name schedule ${schedule.name} ended." }
            // nothing to do when the schedule ends
        }

        override fun capacityChange(item: CapacitySchedule.CapacityItem) {
            ProcessModel.logger.trace { "$time > Resource: $name, capacity item ${item.name} started with capacity ${item.capacity} for duration ${item.duration}." }
            // make the capacity change notice using information from CapacityItem
            val notice = CapacityChangeNotice(item.capacity, item.duration, item.priority)
            notice.capacitySchedule = item.schedule
            // tell resource to handle it
            changeCapacity(notice)
        }
    }
}