/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
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

import ksl.modeling.variable.DefaultReportingOptionIfc
import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.statistic.State
import ksl.utilities.statistic.StateAccessorIfc
import kotlin.math.abs

interface ResourceCIfc : DefaultReportingOptionIfc {

    var initialCapacity: Int
    val capacity: Int
    val stateStatisticsOption: Boolean
    val busyState: StateAccessorIfc
    val idleState: StateAccessorIfc
    val failedState: StateAccessorIfc
    val inactiveState: StateAccessorIfc
    val state: StateAccessorIfc
    val previousState: StateAccessorIfc

    /** Checks if the resource is idle, has no units allocated
     */
    val isIdle: Boolean

    /** Checks to see if the resource is busy, has some units allocated
     */
    val isBusy: Boolean

    /** Checks if the resource is failed
     */
    val isFailed: Boolean

    /** Checks to see if the resource is inactive
     */
    val isInactive: Boolean
    val numBusyUnits: TWResponseCIfc
    val util: TWResponseCIfc
    val numAvailableUnits: Int
    val hasAvailableUnits: Boolean
    val hasBusyUnits: Boolean
    val fractionBusy: Double
    val numBusy: Int
    val numTimesSeized: Int
    val numTimesReleased: Int
}

/**
 * An allocation listener is notified whenever the resource is allocated and when the resource
 * is deallocated. This allows general actions to occur when the resource's state changes
 * at these instances in time.
 */
interface AllocationListenerIfc {

    /**
     * @param allocation the allocation that was allocated
     */
    fun allocate(allocation: Allocation)

    /**
     * @param allocation the allocation that was deallocated
     */
    fun deallocate(allocation: Allocation)
}

interface ResourceFailureActionsIfc {

    /**
     * @param allocation the allocation that was affected by the failure
     */
    fun beginFailure(allocation: Allocation)

    /**
     * @param allocation the allocation that was affected by the failure
     */
    fun endFailure(allocation: Allocation)
}

/**
 *  A Resource represents a number of common units that can be allocated to entities.  A resource
 *  has an initial capacity.  The capacity can be changed during a replication; however, the capacity of
 *  every replication starts at the same initial capacity.
 *
 *  A resource is busy if at least 1 unit has been allocated. A resource becomes busy when it has allocations. If
 *  a seize-request occurs and the resource has capacity to fill it completely, then the request is allocated
 *  the requested amount.  If insufficient capacity is available at the time of the request, then the request
 *  waits until the requested units can be allocated.
 *
 *  A resource is considered inactive if all of its units of capacity are inactive. That is, a resource is
 *  inactive if its capacity is zero.  A resource that is inactive can be seized.  If a request for units occurs
 *  when the resource is inactive, the request waits (as usual) until it can be fulfilled.
 *
 *  A resource is idle if it is not failed, and it has capacity but no units have been allocated. If b(t) is the number
 *  of units allocated at time t, and c(t) is the current capacity of the resource, then the number of available units,
 *  a(t), is defined as a(t) = c(t) - b(t).  If the resource is failed, then a(t) = 0.  Thus, a resource is idle if
 *  a(t) = c(t).  Since a resource is busy if b(t) > 0, busy and idle are complements of each other. Provided a
 *  resource is not failed and c(t) > 0 (not inactive), then the resource is either busy b(t) > 0 or idle b(t) = 0.
 *  If a(t) > 0, then the resource has units that it can allocate.
 *
 *
 */
open class Resource(
    parent: ModelElement,
    name: String? = null,
    capacity: Int = 1,
    collectStateStatistics: Boolean = false
) : ModelElement(parent, name), ResourceCIfc {

    init {
        require(capacity >= 1) { "The initial capacity of the resource must be >= 1" }
    }

    override var defaultReportingOption: Boolean = true
        set(value) {
            myNumBusy.defaultReportingOption = value
            myUtil.defaultReportingOption = value
            field = value
        }

    /** A resource can be allocated to 0 or more entities.
     *  An entity that is using a resource can have more than 1 allocation of the resource.
     *  The key to this map represents the entities that are using the resource.
     *  The element of this map represents the list of allocations allocated to the entity.
     */
    protected val entityAllocations: MutableMap<ProcessModel.Entity, MutableList<Allocation>> = mutableMapOf()

    protected val allocationListeners: MutableList<AllocationListenerIfc> = mutableListOf()

    /**
     * holds entity requests that are waiting for the resource in an internal list
     * with no statistics. This list allows waiting requests to be processed because of
     * capacity changes and failures
     */
    protected val waitingRequests: MutableList<ProcessModel.Entity.Request> = mutableListOf()

    fun addAllocationListener(listener: AllocationListenerIfc) {
        allocationListeners.add(listener)
    }

    fun removeAllocationListener(listener: AllocationListenerIfc) {
        allocationListeners.remove(listener)
    }

    protected fun allocationNotification(allocation: Allocation) {
        for (listener in allocationListeners) {
            listener.allocate(allocation)
        }
    }

    protected fun deallocationNotification(allocation: Allocation) {
        for (listener in allocationListeners) {
            listener.deallocate(allocation)
        }
    }

    override var initialCapacity = capacity
        set(value) {
            require(value >= 0) { "The initial capacity of the resource must be >= 0" }
            if (model.isRunning) {
                Model.logger.warn { "Changed the initial capacity of $name during replication ${model.currentReplicationNumber}." }
            }
            field = value
        }

    override var capacity = capacity
        protected set(value) {
            require(value >= 0) { "The capacity must be >= 0" }
            field = value
            if (field == 0) {
                myState = myInactiveState
            }
        }

    private val mySchedules: MutableMap<CapacitySchedule, CapacityChangeListenerIfc> = mutableMapOf()

    override var numTimesSeized: Int = 0
        protected set

    override var numTimesReleased: Int = 0
        protected set

    override val stateStatisticsOption: Boolean = collectStateStatistics

    /** The busy state, keeps track of when the resource has an allocation
     *  A resource is busy if any of its units are allocated.
     *
     */
    protected val myBusyState: ResourceState = ResourceState("${this.name}_Busy", collectStateStatistics)
    override val busyState: StateAccessorIfc
        get() = myBusyState

    /** The idle state keeps track of when no units have been allocated
     */
    protected val myIdleState: ResourceState = ResourceState("${this.name}Idle", collectStateStatistics)
    override val idleState: StateAccessorIfc
        get() = myIdleState

    /** The failed state, keeps track of when no units
     * are available because the resource is failed
     *
     */
    protected val myFailedState: ResourceState = FailedState("${this.name}_Failed", collectStateStatistics)
    override val failedState: StateAccessorIfc
        get() = myFailedState

    /** The inactive state, keeps track of when no units
     * are available because the resource's capacity is zero.
     */
    protected val myInactiveState: ResourceState = ResourceState("${this.name}_Inactive", collectStateStatistics)
    override val inactiveState: StateAccessorIfc
        get() = myInactiveState

    protected var myState: ResourceState = myIdleState
        set(nextState) {
            field.exit(time)  // exit the current state
            myPreviousState = field // remember what the current state was
            field = nextState // transition to next state
            field.enter(time) // enter the current state
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
    override val isFailed: Boolean
        get() = myState === myFailedState

    /** Checks to see if the resource is inactive
     */
    override val isInactive: Boolean
        get() = myState === myInactiveState

    protected val myNumBusy = TWResponse(this, "${this.name}:BusyUnits")
    override val numBusyUnits: TWResponseCIfc
        get() = myNumBusy

    protected val myUtil = TWResponse(this, "${this.name}:Util")
    override val util: TWResponseCIfc
        get() = myUtil
    override val numBusy: Int
        get() = myNumBusy.value.toInt()

    override val numAvailableUnits: Int
        get() = if (isFailed || isInactive) {//isBusy || isFailed || isInactive
            0
        } else {
            capacity - numBusy
        }

    override val hasAvailableUnits: Boolean
        get() = numAvailableUnits > 0

    override val hasBusyUnits: Boolean
        get() = myNumBusy.value > 0.0

    override val fractionBusy: Double
        get() {
            return if (capacity == 0) {
                0.0
            } else {
                myNumBusy.value / capacity
            }
        }

    override fun toString(): String {
        return "$name: state = $myState capacity = $capacity numBusy = $numBusy numAvailable = $numAvailableUnits"
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
        for ((entity, aList) in entityAllocations) {
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

    override fun beforeReplication() {
        super.beforeReplication()
        initializeStates()
    }

    private fun initializeStates() {
        //clears the accumulators but keeps the current state thinking that is entered
        myIdleState.initialize(isIdle)
        myBusyState.initialize(isBusy)
        myFailedState.initialize(isFailed)
        myInactiveState.initialize(isInactive)
        myState = myInactiveState // tell it to be in the inactive state (assign prev, exit, assign, enter)
    }

    override fun initialize() {
        super.initialize()
        waitingRequests.clear()
        entityAllocations.clear()
        capacity = initialCapacity
        // note that initialize() causes state to not be entered, and clears it accumulators
//        myIdleState.initialize()
//        myBusyState.initialize()
//        myFailedState.initialize()
//        myInactiveState.initialize()
        myState = myIdleState // will cause myPreviousState to be set to current value of myState
//        myState.enter(time) // besides setting it, we must enter it
//        myPreviousState = myInactiveState // make sure that it starts as if it was inactive to idle
        numTimesSeized = 0
        numTimesReleased = 0
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
        require(amountNeeded >= 1) { "The amount to allocate must be >= 1" }
        check(numAvailableUnits >= amountNeeded) { "The amount requested, $amountNeeded must be <= the number of units available, $numAvailableUnits" }
        val allocation = Allocation(entity, this, amountNeeded, queue, allocationName)
        if (!entityAllocations.contains(entity)) {
            entityAllocations[entity] = mutableListOf()
        }
        entityAllocations[entity]?.add(allocation)
        myNumBusy.increment(amountNeeded.toDouble())
        numTimesSeized++
        myUtil.value = fractionBusy
        // resource becomes busy (or stays busy), because an allocation occurred
//        myState.exit(time)
        myState = myBusyState
//        myState.enter(time)
        numTimesSeized++
        //need to put this allocation in Entity also
        entity.allocate(allocation)
        allocationNotification(allocation)
        return allocation
    }

    /** Causes the resource to deallocate the amount associated with the allocation
     *
     * @param allocation the allocation to be deallocated
     */
    fun deallocate(allocation: Allocation) {
        require(allocation.amount >= 1) { "The allocation does not have any amount to deallocate" }
        require(allocation.resource === this) { "The allocations was not on this resource." }
        require(entityAllocations.contains(allocation.entity)) { "The entity associated with the allocation is not using this resource." }
        val b: Boolean = entityAllocations[allocation.entity]!!.contains(allocation)
        require(b) { "The supplied allocation is not currently allocated for this resource." }
        //need to remove from allocations for the specific entity
        entityAllocations[allocation.entity]!!.remove(allocation)
        if (entityAllocations[allocation.entity]!!.isEmpty()) {
            // no more allocations for this entity, remove it from the map also
            entityAllocations.remove(allocation.entity)
        }
        // give back to the resource
        myNumBusy.decrement(allocation.amount.toDouble())
        numTimesReleased++
        myUtil.value = fractionBusy
        if (myNumBusy.value == 0.0) {
//            myState.exit(time)
            myState = myIdleState
//            myState.enter(time)
        }
        // need to also deallocate from the entity
        allocation.entity.deallocate(allocation)
        // deallocate the allocation, so it can't be used again
        allocation.amount = 0
        allocation.timeDeallocated = time
        deallocationNotification(allocation)
    }

    protected open fun resourceEnteredFailure() {
        val list = allocations()
        for (allocation in list) {
            allocation.failureActions.beginFailure(allocation)
        }
    }

    protected open fun resourcedExitedFailure() {
        val list = allocations()
        for (allocation in list) {
            allocation.failureActions.endFailure(allocation)
        }
    }

    protected open inner class ResourceState(aName: String, stateStatistics: Boolean = false) :
        State(name = aName, useStatistic = stateStatistics) {
        //TODO need to have states: idle, busy, inactive?
    }

    protected inner class FailedState(aName: String, stateStatistics: Boolean = false) :
        ResourceState(aName, stateStatistics) {
        override fun onEnter() {
            resourceEnteredFailure()
        }

        override fun onExit() {
            resourcedExitedFailure()
        }
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
            if (waitingRequests.isNotEmpty()) {
                // there are requests waiting for this resource, and the resource now has more capacity
                for (request in waitingRequests) {
                    // find the request that is next in the queue
                    if (request.isQueued) {
                        if (request == request.queue!!.peekNext()) {
                            // if there is now enough capacity, tell the entity to resume
                            if (numAvailableUnits >= request.amountRequested) {
                                request.entity.resumeProcess()
                                break
                            }
                        }
                    }
                }
            }
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

    internal fun addRequest(request: ProcessModel.Entity.Request) {
        waitingRequests.add(request)
    }

    internal fun removeRequest(request: ProcessModel.Entity.Request) {
        waitingRequests.remove(request)
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