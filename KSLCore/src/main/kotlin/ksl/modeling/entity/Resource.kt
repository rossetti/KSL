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
import java.util.*

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
            require(value >= 1) { "The initial capacity of the resource must be >= 1" }
            if (model.isRunning) {
                Model.logger.warn { "Changed the initial capacity of $name during replication ${model.currentReplicationNumber}." }
            }
            field = value
        }

    override var capacity = capacity
        protected set

    private val mySchedules: MutableMap<CapacitySchedule, CapacityChangeListenerIfc> = mutableMapOf()

    override var numTimesSeized: Int = 0
        protected set

    override var numTimesReleased: Int = 0
        protected set

    override val stateStatisticsOption: Boolean = collectStateStatistics

    /** The busy state, keeps track of when all units are busy
     *
     */
    protected val myBusyState: ResourceState = ResourceState("${this.name}_Busy", collectStateStatistics)
    override val busyState: StateAccessorIfc
        get() = myBusyState

    /** The idle state, keeps track of when there are idle units
     * i.e. if any unit is idle then the resource as a whole is
     * considered idle
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
     * are available because the resource is inactive
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
        get() = myNumBusy.value / capacity

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
        return !mySchedules.isEmpty()
    }

    /**
     * Tells the resource to listen and react to changes in the supplied
     * Schedule. Any scheduled items on the schedule will be interpreted as
     * changes to make the resource become inactive.  Note the implications
     * of having more that one schedule in the class documentation.
     *
     * @param schedule the schedule to use, must not be null
     */
    fun useSchedule(schedule: CapacitySchedule) {
        if (isUsingSchedule(schedule)) {
            return
        }
        val scheduleListener = CapacityListener()
        mySchedules.put(schedule, scheduleListener)
        schedule.addCapacityChangeListener(scheduleListener)
    }

    /**
     * @return true if already using the supplied Schedule
     */
    fun isUsingSchedule(schedule: CapacitySchedule): Boolean {
        return mySchedules.containsKey(schedule)
    }

    /**
     * If the resource is using a schedule, the resource stops listening for
     * schedule changes and is no longer using a schedule
     */
    fun stopUsingSchedule(schedule: CapacitySchedule) {
        if (!isUsingSchedule(schedule)) {
            return
        }
        val listenerIfc: CapacityChangeListenerIfc = mySchedules.remove(schedule)!!
        schedule.deleteCapacityChangeListener(listenerIfc)
    }
    inner class CapacityChangeNotice {
        var capacity: Int = 0
        var duration: Double = Double.POSITIVE_INFINITY
        var startTime: Double = 0.0
    }

    inner class CapacityListener : CapacityChangeListenerIfc {
        override fun scheduleStarted(schedule: CapacitySchedule) {
            println("time = ${schedule.time} Schedule Started")
        }

        override fun scheduleEnded(schedule: CapacitySchedule) {
            println("time = ${schedule.time} Schedule Ended")
        }

        override fun scheduleItemStarted(item: CapacitySchedule.CapacityItem) {
            println("time = ${item.schedule.time} scheduled item ${item.name} started with capacity ${item.capacity}")
        }

        override fun scheduleItemEnded(item: CapacitySchedule.CapacityItem) {
            println("time = ${item.schedule.time} scheduled item ${item.name} ended with capacity ${item.capacity}")
        }

    }
}