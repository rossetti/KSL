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

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.modeling.variable.*
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.statistic.State
import ksl.utilities.statistic.StateAccessorIfc

/**
 *  A Resource represents a number of common units that can be allocated to entities.  A resource
 *  has an initial capacity that cannot be changed during a replication. This base resource class
 *  can only be busy or idle.
 *
 *  A resource is busy if at least 1 unit has been allocated. A resource becomes busy when it has allocations. If
 *  a seize-request occurs and the resource has capacity to fill it completely, then the request is allocated
 *  the requested amount.  If insufficient capacity is available at the time of the request, then the request
 *  waits until the requested units can be allocated.
 *
 *  If b(t) is the number of units allocated at time t, and c(t) is the current capacity of the resource, then the number of available units,
 *  a(t), is defined as a(t) = c(t) - b(t).  Thus, a resource is idle if
 *  a(t) = c(t).  Since a resource is busy if b(t) > 0, busy and idle are complements of each other. A resource is
 *  either busy b(t) > 0 or idle b(t) = 0.  If a(t) > 0, then the resource has units that can be allocated.
 *
 *  The utilization of a resource is defined as the ratio of the average number of busy units to the average
 *  number of active units.  Units are active if they are part of the current capacity of the resource.
 *
 *  Subclasses of Resource implement additional state behavior.
 *
 *  @param parent the parent holding this resource
 *  @param name the name of the resource
 *  @param capacity the initial capacity of the resource.  Cannot be changed during a replication. The default capacity is 1.
 */
open class Resource(
    parent: ModelElement,
    name: String? = null,
    capacity: Int = 1
) : ProcessModel(parent, name), ResourceCIfc {

    init {
        require(capacity >= 1) { "The initial capacity of the resource must be >= 1" }
    }

    /**
     *  Tracks which queues have requests targeting the resource
     */
    protected val myCapacityChangeQSet = mutableSetOf<RequestQ>()

    var requestQNotificationRule: RequestQueueNotificationRuleIfc = DefaultRequestQueueNotificationRule

    /**
     *  The pools that currently contain the resource
     */
    internal val myResourcePools = mutableSetOf<ResourcePool>()

    override var defaultReportingOption: Boolean = true
        set(value) {
            myNumBusy.defaultReportingOption = value
            stateReportingOption = value
            field = value
        }

    override var selectionCriteria: Double = 0.0

    /** A resource can be allocated to 0 or more entities.
     *  An entity that is using a resource can have more than 1 allocation of the resource.
     *  The key to this map represents the entities that are using the resource.
     *  The element of this map represents the list of allocations allocated to the entity.
     */
    protected val entityAllocations: MutableMap<ProcessModel.Entity, MutableList<Allocation>> = mutableMapOf()

    protected val allocationListeners: MutableList<AllocationListenerIfc> = mutableListOf()

    /**
     *  Resources can be associated with a capacity change schedule. If a capacity change occurs,
     *  especially a decrease that makes the resource inactive or an increase that allows the resource
     *  to be available occurs, then queues that hold requests for the resource may want to be notified
     *  of the capacity change.
     *
     *  In the case of a capacity increase, the additional units will be automatically allocated
     *  to waiting requests when the increase occurs if and only if the queue holding the requests
     *  is associated with the resource.  The order of queue notification is governed by setting
     *  of the proper (requestNotificationRule).
     *
     *  In the case of a capacity decrease that makes the resource inactive, the entities
     *  associated with the requests held in the associated queues are notified so that they
     *  may take action because the resource is inactive.
     *
     *  This function associates the request queue with the resource so
     *  that the notification will occur.  For the case of resources that have queue because
     *  of defined structure (ResourceWithQ, MovableResourceWithQ, ResourcePoolWithQ, MovableResourcePoolWithQ),
     *  the registration of the queue is automatic when the resource is constructed or when added to the pool.
     *  If you use a general, not attached queue to hold requests (via the seize() function) then, you should
     *  consider using this function to register the queue. A queue may be registered with more than one
     *  resource and a resource may have many queues registered.
     */
    fun registerCapacityChangeQueue(queue: RequestQ) {
        myCapacityChangeQSet.add(queue)
    }

    /**
     *  This function permits a queue to be unregistered from a resource. If the queue was not registered,
     *  then nothing occurs.
     */
    fun unregisterCapacityChangeQueue(queue: RequestQ) {
        myCapacityChangeQSet.remove(queue)
    }

    /**
     * Add an allocation listener.  Allocation listeners are notified when (after)
     * units are allocated to an entity and after units are deallocated.
     */
    fun addAllocationListener(listener: AllocationListenerIfc) {
        allocationListeners.add(listener)
    }

    /**
     * Removes the listener
     */
    fun removeAllocationListener(listener: AllocationListenerIfc) {
        allocationListeners.remove(listener)
    }

    /**
     * Notifies any attached listeners when units are allocated.
     */
    protected fun allocationNotification(allocation: Allocation) {
        for (listener in allocationListeners) {
            listener.allocate(allocation)
        }
    }

    /**
     * Notifies any attached listeners when units are deallocated.
     */
    protected fun deallocationNotification(allocation: Allocation) {
        for (listener in allocationListeners) {
            listener.deallocate(allocation)
        }
    }

    protected val myInactiveProp: Response = Response(this, name = "${this.name}:PTimeInactive")
    val proportionOfTimeInactive: ResponseCIfc
        get() = myInactiveProp
    protected val myIdleProp: Response = Response(this, name = "${this.name}:PTimeIdle")
    val proportionOfTimeIdle: ResponseCIfc
        get() = myIdleProp
    protected val myBusyProp: Response = Response(this, name = "${this.name}:PTimeBusy")
    val proportionOfTimeBusy: ResponseCIfc
        get() = myBusyProp

    final override var stateReportingOption: Boolean = false
        set(value) {
            myCapacity.defaultReportingOption = value
            myInactiveProp.defaultReportingOption = value
            myIdleProp.defaultReportingOption = value
            myBusyProp.defaultReportingOption = value
            field = value
        }

    @set:KSLControl(
        controlType = ControlType.INTEGER,
        lowerBound = 0.0
    )
    override var initialCapacity = capacity
        set(value) {
            require(value >= 0) { "The initial capacity of the resource must be >= 0" }
            if (model.isRunning) {
                Model.logger.warn { "Changed the initial capacity of $name during replication ${model.currentReplicationNumber}." }
            }
            field = value
        }

    protected val instantUtilTW: TWResponse = TWResponse(this, "${this.name}:InstantaneousUtil")
    override val timeAvgInstantaneousUtil: TWResponseCIfc
        get() = instantUtilTW

    override val instantaneousUtil: Double
        get() {
            return if (numBusy == 0) {
                0.0
            } else if (numBusy >= capacity) {
                1.0
            } else {
                numBusy.toDouble() / capacity.toDouble()
            }
        }

    override var capacity = capacity
        protected set(value) {
            require(value >= 0) { "The capacity must be >= 0" }
            field = value
            // handle the state change
            if ((numBusy == 0) && (field == 0)) {
                myState = myInactiveState
            } else if ((numBusy == 0) && (field > 0)) {
                myState = myIdleState
            } else if ((numBusy > 0) && (field >= 0)) {
                myState = myBusyState
            }
            myCapacity.value = field.toDouble()
            instantUtilTW.value = instantaneousUtil
        }

    protected val myCapacity =
        TWResponse(this, name = "${this.name}:NumActiveUnits", initialValue = capacity.toDouble())

    val numActiveUnits: TWResponseCIfc
        get() = myCapacity

    init {
        stateReportingOption = false
    }

    protected val myNumBusy = TWResponse(this, "${this.name}:NumBusyUnits")
    override val numBusyUnits: TWResponseCIfc
        get() = myNumBusy

    protected val myFractionBusy: Response = Response(this, name = "${this.name}:ScheduledUtil")
    override val scheduledUtil: ResponseCIfc
        get() = myFractionBusy

    protected val mySeizeCounter = Counter(this, name = "${this.name}:SeizeCount")
    override val seizeCounter: CounterCIfc
        get() = mySeizeCounter

    override var numBusy: Int = 0
        protected set(newValue) {
            require(newValue >= 0) { "The number busy must be >= 0" }
            val previousValue = field
            field = newValue
            if (newValue > previousValue) {
                // increasing the number busy
                val increase = newValue - previousValue
                myNumBusy.increment(increase.toDouble())
                numTimesSeized++
                mySeizeCounter.increment()
            } else if (newValue < previousValue) {
                // decreasing the number busy
                val decrease = previousValue - newValue
                myNumBusy.decrement(decrease.toDouble())
                numTimesReleased++
            }
            // handle the state change
            if ((field == 0) && (capacity == 0)) {
                myState = myInactiveState
            } else if ((field == 0) && (capacity > 0)) {
                myState = myIdleState
            } else if ((field > 0) && (capacity >= 0)) {
                myState = myBusyState
            }
            instantUtilTW.value = instantaneousUtil
        }

    override val numAvailableUnits: Int
        get() = capacity - numBusy

    override val hasAvailableUnits: Boolean
        get() = numAvailableUnits > 0

    override val hasBusyUnits: Boolean
        get() = numBusy > 0.0

    override var numTimesSeized: Int = 0
        protected set

    override var numTimesReleased: Int = 0
        protected set

    /** The busy state, keeps track of when the resource has an allocation
     *  A resource is busy if any of its units are allocated.
     *
     */
    protected val myBusyState: ResourceState = ResourceState("${this.name}_Busy")
    override val busyState: StateAccessorIfc
        get() {
            if (isBusy) {
                myState.exit(time)
                myState.enter(time)
            }
            return myBusyState
        }

    /**
     * Time when state time accumulation started. May be greater than 0 because of warmup.
     */
    var startStateTime: Double = 0.0
        protected set

    /**
     *  Total time that the resource has been idle, busy, or inactive
     */
    val totalStateTime: Double
        get() = time - startStateTime

    /** The idle state keeps track of when no units have been allocated
     */
    protected val myIdleState: ResourceState = ResourceState("${this.name}_Idle")
    override val idleState: StateAccessorIfc
        get() {
            if (isIdle) {
                myState.exit(time)
                myState.enter(time)
            }
            return myIdleState
        }

    /** The failed state, keeps track of when no units
     * are available because the resource is failed
     *
     */
//    protected val myFailedState: ResourceState = FailedState("${this.name}_Failed", collectStateStatistics)
//    override val failedState: StateAccessorIfc
//        get() = myFailedState

    /** The inactive state, keeps track of when no units
     * are available because the resource's capacity is zero.
     */
    protected val myInactiveState: ResourceState = InactiveState("${this.name}_Inactive")
    override val inactiveState: StateAccessorIfc
        get() {
            if (isInactive) {
                myState.exit(time)
                myState.enter(time)
            }
            return myInactiveState
        }

    protected var myState: ResourceState = myIdleState
        set(nextState) {
            field.exit(time)  // exit the current state
            myPreviousState = field // remember what the current state was
            field = nextState // transition to next state
            field.enter(time) // enter the current state
            logger.trace { "r = ${model.currentReplicationNumber} : $time > Resource: $name : exited state ${myPreviousState.name} : entered state ${field.name}: c(t) = $capacity b(t) = $numBusy a(t) = $numAvailableUnits" }
        }

    override val state: StateAccessorIfc
        get() = myState

    protected var myPreviousState: ResourceState = myInactiveState
    override val previousState: StateAccessorIfc
        get() = myPreviousState

    /** Checks if the resource is idle, has no units allocated
     */
    override val isIdle: Boolean
        get() = myState === myIdleState

    /** Checks to see if the resource is busy, has some units allocated
     */
    override val isBusy: Boolean
        get() = myState === myBusyState

    /** Checks if the resource is failed
     */
//    override val isFailed: Boolean
//        get() = myState === myFailedState

    /** Checks to see if the resource is inactive
     */
    override val isInactive: Boolean
        get() = myState === myInactiveState

    override fun toString(): String {
        return "$name: state = ${myState.name} c(t) = $capacity b(t) = $numBusy a(t) = $numAvailableUnits"
    }

    /**
     *  Checks if the entity is using (has allocated units) of the resource.
     * @param entity the entity that might be using the resource
     */
    fun isUsing(entity: ProcessModel.Entity): Boolean {
        return entityAllocations.contains(entity)
    }

    /**
     *  Computes the number of different allocations of the resource held by the entity.
     *  Recall that allocations can be for different amounts.
     *
     * @param entity the entity that might be using the resource
     * @return the count of the number of distinct allocations
     */
    fun numberOfAllocations(entity: ProcessModel.Entity): Int {
        return if (!isUsing(entity)) {
            0
        } else {
            entityAllocations[entity]!!.size
        }
    }

    /**
     * @param entity the entity that might be using the resource
     * @return the list of allocations associated with the entity's use of the resource,
     * which may be empty if the entity is not using the resource
     */
    fun allocations(entity: ProcessModel.Entity): List<Allocation> {
        if (!isUsing(entity)) {
            return emptyList()
        }
        return entityAllocations[entity]!!.toList()
    }

    /**
     * @return a list of all the allocations of the resource to any entity
     */
    fun allocations(): List<Allocation> {
        val list = mutableListOf<Allocation>()
        for ((_, aList) in entityAllocations) {
            list.addAll(aList)
        }
        return list
    }

    /**
     * Computes the total number of units of the specified resource that are allocated
     * to the entity.
     * @param entity the entity that might be using the resource
     * @return the total amount requested over all the distinct allocations
     */
    fun totalAmountAllocated(entity: ProcessModel.Entity): Int {
        return if (!isUsing(entity)) {
            0
        } else {
            var sum = 0
            for (allocation in entityAllocations[entity]!!) {
                sum = sum + allocation.amount
            }
            sum
        }
    }

    override fun warmUp() {
        super.warmUp()
        startStateTime = time
        myState.exit(time)
        myBusyState.resetStateCollection()
        myInactiveState.resetStateCollection()
        myIdleState.resetStateCollection()
        myState.enter(time)
    }

    override fun replicationEnded() {
        super.replicationEnded()
        val avgNR = myNumBusy.withinReplicationStatistic.weightedAverage
        val avgMR = numActiveUnits.withinReplicationStatistic.weightedAverage
        if (avgMR > 0.0) {
            myFractionBusy.value = avgNR / avgMR
        }
        if (totalStateTime > 0.0) {
            myIdleProp.value = idleState.totalTimeInState / totalStateTime
            myInactiveProp.value = inactiveState.totalTimeInState / totalStateTime
            myBusyProp.value = busyState.totalTimeInState / totalStateTime
        }
    }

    override fun initialize() {
        super.initialize()
        entityAllocations.clear()
        startStateTime = 0.0
        numTimesSeized = 0
        numTimesReleased = 0
        // note that initialize() causes state to not be entered, and clears it accumulators
        myIdleState.initialize()
        myBusyState.initialize()
        myInactiveState.initialize()
        // myState is one of the states (idle, busy, inactive)
        // but now the state thinks we are not in it, make it think we are in it
        myState.enter(time)
        // now exit it for the inactive state
        myState = myInactiveState // will cause previous state to be some arbitrary state from end of last replication
        // make previous state inactive and current state idle, for start of the replication
        myState = myIdleState
        capacity = initialCapacity
    }

    /**
     * A resource may fill a request in many ways.  This function indicates if the
     * request for the amount needed can be allocated immediately, without any wait based on the current state
     * of the resource. Since the underlying state of the resource may be more complex than
     * indicated by the state exposed in the API, it is important to use this method
     * to determine if the requested amount can be allocated.
     *
     * @param amountNeeded the amount needed from the resource
     * @return true means that the amount needed can be allocated at the current time
     */
    fun canAllocate(amountNeeded: Int = 1): Boolean {
        require(amountNeeded >= 1) { "The amount to allocate must be >= 1" }
        // checking state is an issue
        if (isInactive) {
            return false
        }
        return amountNeeded <= numAvailableUnits
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
     * @return an allocation representing that the units have been allocated to the entity. The reference
     * to this allocation is necessary in order to deallocate the allocated units.
     */
    fun allocate(
        entity: ProcessModel.Entity,
        amountNeeded: Int = 1,
        queue: RequestQ,
        allocationName: String? = null
    ): Allocation {
        require(canAllocate(amountNeeded)) { "$this The amount requested, $amountNeeded cannot currently be allocated" }
        val allocation = Allocation(entity, this, amountNeeded, queue, allocationName)
        if (!entityAllocations.contains(entity)) {
            entityAllocations[entity] = mutableListOf()
        }
        entityAllocations[entity]?.add(allocation)
        numBusy = numBusy + amountNeeded
        //need to put this allocation in Entity also
        entity.allocate(allocation)
        allocationNotification(allocation)
        return allocation
    }

    /** Causes the resource to deallocate the amount associated with the allocation
     *
     * @param allocation the allocation to be deallocated
     */
    open fun deallocate(allocation: Allocation) {
        require(allocation.amount >= 1) { "The allocation does not have any amount to deallocate" }
        require(allocation.myResource === this) { "The allocations was not on this resource." }
        require(entityAllocations.contains(allocation.myEntity)) { "The entity associated with the allocation is not using this resource." }
        val b: Boolean = entityAllocations[allocation.myEntity]!!.contains(allocation)
        require(b) { "The supplied allocation is not currently allocated for this resource." }
        //need to remove from allocations for the specific entity
        entityAllocations[allocation.myEntity]!!.remove(allocation)
        if (entityAllocations[allocation.myEntity]!!.isEmpty()) {
            // no more allocations for this entity, remove it from the map also
            entityAllocations.remove(allocation.myEntity)
        }
        // give back to the resource
        numBusy = numBusy - allocation.amount
        // need to also deallocate from the entity
        allocation.myEntity.deallocate(allocation)
        // deallocate the allocation, so it can't be used again
        allocation.deallocate()
        deallocationNotification(allocation)
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

    protected open inner class ResourceState(aName: String, stateStatistics: Boolean = false) :
        State(name = aName, useStatistic = stateStatistics) {
        // need to have state implementations for: failed?
    }

    protected inner class InactiveState(aName: String, stateStatistics: Boolean = false) :
        ResourceState(aName, stateStatistics) {
        override fun onEnter() {
            resourceBecameInactive()
        }

        override fun onExit() {
            resourceBecameActive()
        }
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
    fun useSchedule(schedule: CapacitySchedule, changeRule: CapacityChangeRule) {
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

    protected fun handeIgnoreRuleDeallocation(allocation: Allocation) {
        // a capacity change is pending and needs units that were deallocated
        val amountNeeded = myCurrentChangeNotice!!.amountNeeded
        // number busy went down and number available went up by amount released
        val amountReleased = allocation.amountReleased
        val amountToDecrease = minOf(amountReleased, amountNeeded)
        // give the units to the pending change
        myCurrentChangeNotice!!.amountNeeded = myCurrentChangeNotice!!.amountNeeded - amountToDecrease
        logger.trace { "$time > Resource: $name, provided $amountToDecrease units to notice $myCurrentChangeNotice" }
        if (myCurrentChangeNotice!!.amountNeeded == 0) {
            // the capacity change has been filled
            logger.trace { "$time > Resource: $name, notice $myCurrentChangeNotice has been completed" }
            // if the rule was IGNORE, it was previously scheduled, no need to schedule
            // check if there are more changes
            if (myWaitingChangeNotices.isEmpty()) {
                // no more pending changes
                myCurrentChangeNotice = null
                logger.trace { "$time > Resource: $name, no more pending capacity changes" }
            } else {
                // not empty need to process the next one
                myCurrentChangeNotice = myWaitingChangeNotices.removeFirst()
                // no need to schedule because already scheduled upon arrival
                logger.trace { "$time > Resource: $name, starting the processing of $myCurrentChangeNotice" }
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
        logger.trace { "$time > Resource: $name, decreased capacity by $amountToDecrease" }
        // give the units to the pending change
        myCurrentChangeNotice!!.amountNeeded = myCurrentChangeNotice!!.amountNeeded - amountToDecrease
        logger.trace { "$time > Resource: $name, provided $amountToDecrease units to notice $myCurrentChangeNotice" }
        // check if pending change has been completely filled
        if (myCurrentChangeNotice!!.amountNeeded == 0) {
            // the capacity change has been filled
            logger.trace { "$time > Resource: $name, notice $myCurrentChangeNotice has been completed" }
            // it does not schedule its duration until it gets all the needed change
            // schedule the end of its processing
            myCurrentChangeNotice!!.changeEvent = schedule(
                this::capacityChangeAction, myCurrentChangeNotice!!.duration,
                message = myCurrentChangeNotice, priority = myCurrentChangeNotice!!.priority
            )
            logger.trace { "$time > Resource: $name, scheduled the duration for notice $myCurrentChangeNotice" }
            logger.trace { "$time > Resource: $name, notice's original start time = ${myCurrentChangeNotice?.createTime}, new end time ${myCurrentChangeNotice!!.changeEvent!!.time}" }
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
     *  capacity is decreased from its current value, then the amount of the decrease will be
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
            logger.trace { "$time > Resource: $name, handling IGNORE rule" }
            handleIncomingChangeNoticeIgnoreRule(notice)
        } else if (capacityChangeRule == CapacityChangeRule.WAIT) {
            logger.trace { "$time > Resource: $name, handling WAIT rule" }
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
                logger.trace { "$time > Resource: $name, scheduled end of capacity change for ${notice.changeEvent?.time}" }
                // there is a pending change in progress and a new change is arriving
                // make the incoming change wait
                myWaitingChangeNotices.add(notice)
                logger.trace { "$time > Resource: $name, a notice is in progress, incoming notice $notice must wait" }
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
        require(notice.capacity > capacity) { "The capacity after a capacity increase must be > than the current capacity " }
        logger.trace { "$time > Resource: $name, change notice $notice is increasing the capacity from $capacity to ${notice.capacity}." }
        // increasing the capacity immediately
        capacity = notice.capacity
        // resource could have been busy, idle, or inactive when adding the capacity
        // adding capacity cannot result in resource being inactive, must be either busy or idle after this
        // the current capacity is now larger than it was. This causes some units to become available.
        val available = capacity - numBusy
        logger.trace { "$time > Resource: $this" }
        logger.trace { "$time > Resource: $name, now has $available units." }
        if (available == 0) {
            logger.trace { "$time > Resource: $name, had 0 units available after the positive capacity change. No reason to notify entities." }
            return
        }
        if (myCapacityChangeQSet.isEmpty()) {
            // no queues are currently associated with this resource, no reason to notify
            logger.trace { "$time > Resource: $name, The resource is not associated with any capacity change queues. No queues were notified of the positive capacity change." }
            return
        }
        // this causes the newly available capacity to be allocated to any waiting requests
        // which may resume the entity processes at the current simulated time
        notifyWaitingRequestsOfCapacityIncrease(available, notice.priority)
    }

    protected fun notifyWaitingRequestsOfCapacityIncrease(available: Int, priority: Int) {
        // myCapacityChangeQSet holds the queues that may have requests for this resource
        if (myCapacityChangeQSet.size == 1) {
            // there is only one queue, no reason to decide, just notify it
            val queue = myCapacityChangeQSet.first()
            val n = queue.processWaitingRequests(available, priority)
            logger.trace { "$time > Resource: $name will allocate $n units from the positive capacity change having $available available units." }
            return
        }
        // there is more than 1 queue to notify, in what order should the notifications occur
        // two logical orderings: 1) the order in which they were added (reflects when request occurred)
        // 2) in descending order of the number of requests for the resource in the queues
        val itr = requestQNotificationRule.ruleIterator(myCapacityChangeQSet)
        var amountAvailable = available
        while (itr.hasNext()) {
            val queue = itr.next()
            // need to ensure that notifications stop if all available will be allocated
            val n = queue.processWaitingRequests(amountAvailable, priority)
            logger.trace { "$time > Resource: $name will allocate $n units from the positive capacity change having $amountAvailable available units." }
            amountAvailable = amountAvailable - n
            // there is no point in notifying after the resource has no units available
            if (amountAvailable == 0) {
                break
            }
        }
    }

    protected fun negativeChangeNoPendingChangeIgnoreRule(notice: CapacityChangeNotice) {
        logger.trace { "$time > Resource: $name, change notice $notice is decreasing the capacity from $capacity to ${notice.capacity}." }
        // notice.capacity < capacity, need to decrease the capacity
        val decrease = capacity - notice.capacity
        if (numAvailableUnits >= decrease) {
            // there are enough available units to handle the change w/o using busy resources
            capacity = capacity - decrease
            logger.trace { "$time > Resource: $name, enough units idle to immediately reduce capacity by $decrease." }
        } else {
            // not enough available, ignore rule causes entire change at the current time
            // some of the change will need to be supplied when the busy resources are released
            logger.trace { "$time > Resource: $name, not enough units idle to reduce capacity by $decrease." }
            notice.amountNeeded = decrease
            // always schedule the end of the incoming change immediately
            // capture the time of the change in the event time
            notice.changeEvent =
                schedule(this::capacityChangeAction, notice.duration, message = notice, priority = notice.priority)
            logger.trace { "$time > Resource: $name, scheduled end of capacity change for ${notice.changeEvent?.time}" }
            // make the notice the current notice for processing
            myCurrentChangeNotice = notice
            logger.trace { "$time > Resource: $name, notice $notice is now being processed" }
            capacity = capacity - notice.amountNeeded
            // ignore takes away all needed, immediately, by decreasing the capacity by the full amount of the change
            // capacity was decreased but change notice still needs those busy units to be released
            logger.trace { "$time > Resource: $name, reduced capacity to $capacity because of notice $notice" }
        }
    }

    protected fun handleIncomingChangeNoticeWaitRule(notice: CapacityChangeNotice) {
        if (isPendingCapacityChange) {
            // there is a change in progress, make incoming change wait
            // a positive change or a negative change must wait for current change to complete
            myWaitingChangeNotices.add(notice)
            logger.trace { "$time > Resource: $name, notice $myCurrentChangeNotice is in progress." }
            logger.trace { "$time > Resource: $name, incoming notice $notice must wait." }
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
        logger.trace { "$time > Resource: $name, change notice $notice is decreasing the capacity from $capacity to ${notice.capacity}." }
        // notice.capacity < capacity, need to decrease the capacity
        val decrease = capacity - notice.capacity
        if (numAvailableUnits >= decrease) {
            // there are enough available units to handle the change w/o using busy resources
            capacity = capacity - decrease
            // removed idle units, but remaining units are (busy or idle) or all units have been removed
            // may still be busy, idle, or if capacity is zero should be inactive
            // all units needed were allocated, no resulting pending notice
            // change stays until next change arrives
            logger.trace { "$time > Resource: $name, enough units idle to immediately reduce capacity by $decrease." }
        } else {
            // not enough available, this means that at least part of the change will need to wait
            // must decrease capacity, but all required units are busy
            // must wait for all needed units to be released
            // don't schedule its ending until all needed units are released
            logger.trace { "$time > Resource: $name, not enough units idle to reduce capacity by $decrease." }
            notice.amountNeeded = decrease
            // there is no current pending change, make this incoming change be the one to process
            // it does not schedule its duration until it gets all the needed change
            myCurrentChangeNotice = notice
            logger.trace { "$time > Resource: $name, notice $notice is now being processed" }
        }
    }

    /** Represents the actions that occur when a capacity change's duration is completed.
     *
     * @param event the ending event
     */
    protected fun capacityChangeAction(event: KSLEvent<CapacityChangeNotice>) {
        val endingChangeNotice = event.message!!
        logger.trace { "$time > Resource: $name, notice $endingChangeNotice ended its duration" }
        if (capacityChangeRule == CapacityChangeRule.IGNORE) {
            // if ending notice is same as current notice, we can stop the change associated with the current notice
            // if it was not the current, then the ending change notice was previously completed, nothing to do
            if (myCurrentChangeNotice == endingChangeNotice) {
                myCurrentChangeNotice = null
                if (myWaitingChangeNotices.isNotEmpty()) {
                    // note that the waiting notice's end event has already been scheduled,
                    //  we begin its official processing when releases occur
                    myCurrentChangeNotice = myWaitingChangeNotices.removeFirst()
                    logger.trace { "$time > Resource: $name, notice $myCurrentChangeNotice was waiting but is now being processed" }
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
                logger.trace { "$time > Resource: $name, notice $myCurrentChangeNotice was waiting but is now being processed" }
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
                    logger.trace { "$time > Resource: $name, scheduled end of capacity change for ${myCurrentChangeNotice!!.changeEvent?.time}" }
                } else if (myCurrentChangeNotice!!.capacity < capacity) {
                    // negative change after completing previous change
                    negativeChangeAfterPendingChangeWaitRule(myCurrentChangeNotice!!)
                }
            }
        }
    }

    protected fun negativeChangeAfterPendingChangeWaitRule(notice: CapacityChangeNotice) {
        logger.trace { "$time > Resource: $name, change notice $notice is decreasing the capacity from $capacity to ${notice.capacity}." }
        // notice.capacity < capacity, need to decrease the capacity
        val decrease = capacity - notice.capacity
        if (numAvailableUnits >= decrease) {
            // there are enough available units to handle the change w/o using busy resources
            capacity = capacity - decrease
            // removed idle units, but remaining units are (busy or idle) or all units have been removed
            // may still be busy, idle, or if capacity is zero should be inactive
            // all units needed were taken by the process change
            logger.trace { "$time > Resource: $name, enough units idle to immediately reduce capacity by $decrease." }
            // schedule the end of the change, wait rule has full change for duration
            notice.changeEvent =
                schedule(this::capacityChangeAction, notice.duration, message = notice, priority = notice.priority)
            logger.trace { "$time > Resource: $name, scheduled end of capacity change for ${notice.changeEvent?.time}" }
        } else {
            // not enough available, this means that at least part of the change will need to be in process
            // must decrease capacity, but required units are busy, must wait for needed units to be released
            // don't schedule its duration until all needed units are released
            logger.trace { "$time > Resource: $name, not enough units idle to reduce capacity by $decrease." }
            notice.amountNeeded = decrease
            logger.trace { "$time > Resource: $name, notice $notice is now being processed when releases occur." }
        }
    }

    /**
     *  This function is called from the InactiveState when the state is entered.
     *  The purpose of this function is to permit the notification of entities that may have
     *  requests waiting in a queue that is supported by the resource that became inactive. There might not be
     *  requests in the queue by the entity when the resource becomes inactive; however,
     *  if there are, then the entity may want to not have the request wait during the inactive period.
     *  This allows the entity to react to this situation.
     */
    protected fun resourceBecameInactive() {
        if (myCapacityChangeQSet.size == 1) {
            // there is only one queue, no reason to decide, just notify it
            val queue = myCapacityChangeQSet.first()
            for (request in queue) {
                request.entity.resourceBecameInactiveWhileWaitingInQueueWithSeizeRequestInternal(queue, this, request)
            }
            return
        }
        val itr = requestQNotificationRule.ruleIterator(myCapacityChangeQSet)
        while (itr.hasNext()) {
            val queue = itr.next()
            for (request in queue) {
                request.entity.resourceBecameInactiveWhileWaitingInQueueWithSeizeRequestInternal(queue, this, request)
            }
        }
    }

    /**
     *  This function is called from the InactiveState when the state is exited.
     *  The current behavior is to do nothing. That is, the normal operation of the resource occurs.
     *  This function can be used by subclasses to inject logic into this event.
     */
    protected open fun resourceBecameActive() {

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
            logger.trace { "$time > Resource: $name schedule ${schedule.name} started." }
            // nothing to do when the schedule starts
        }

        override fun scheduleEnded(schedule: CapacitySchedule) {
            logger.trace { "$time > Resource: $name schedule ${schedule.name} ended." }
            // nothing to do when the schedule ends
        }

        override fun capacityChange(item: CapacitySchedule.CapacityItem) {
            logger.trace { "$time > Resource: $name, capacity item ${item.name} started with capacity ${item.capacity} for duration ${item.duration}." }
            // make the capacity change notice using information from CapacityItem
            val notice = CapacityChangeNotice(item.capacity, item.duration, item.priority)
            notice.capacitySchedule = item.schedule
            // tell resource to handle it
            changeCapacity(notice)
        }
    }


    companion object {

        /**
         *  Creates the required number of resources that have no queue, each with the specified capacity.
         * @param parent the containing model element
         * @param numToCreate the number of resources to create, must be 1 or more
         * @param capacity the capacity for the resource at the beginning of each replication, must be at least 1
         */
        fun createResources(parent: ModelElement, numToCreate: Int, capacity: Int = 1): List<Resource> {
            require(capacity >= 1) { "The initial capacity of the resource must be >= 1" }
            require(numToCreate >= 1) { "The initial numToCreate must be >= 1" }
            val list = mutableListOf<Resource>()
            for (i in 1..numToCreate) {
                list.add(Resource(parent, capacity = capacity, name = "${parent.name}:R${i}"))
            }
            return list
        }
    }
}