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
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.statistic.State
import ksl.utilities.statistic.StateAccessorIfc

interface ResourceCIfc : DefaultReportingOptionIfc {

    /**
     * The initial capacity of the resource at the start of the replication. The initial
     * capacity must be greater than 0.
     */
    var initialCapacity: Int

    /**
     *  The current capacity of the resource. In general, it can be 0 or greater
     */
    val capacity: Int

    /**
     *  Indicates if detailed statistics on time in states are collected
     */
    val stateStatisticsOption: Boolean

    /**
     *  Access to the busy state. Busy means at least 1 unit of the resource is allocated.
     */
    val busyState: StateAccessorIfc

    /**
     *  Access to the idle state. Idle means that no units of the resource are allocated.
     */
    val idleState: StateAccessorIfc
//    val failedState: StateAccessorIfc

    /**
     * Access to the inactive state. Inactive means that the capacity of the resource is 0
     */
    val inactiveState: StateAccessorIfc

    /**
     *  The current state of the resource.
     */
    val state: StateAccessorIfc

    /**
     *  The last (previous) state before the current state.
     */
    val previousState: StateAccessorIfc

    /** Checks if the resource is idle, has no units allocated
     */
    val isIdle: Boolean

    /** Checks to see if the resource is busy, has some units allocated
     */
    val isBusy: Boolean

    /** Checks if the resource is failed
     */
//    val isFailed: Boolean

    /** Checks to see if the resource is inactive
     */
    val isInactive: Boolean

    /**
     * Statistical response representing the number of busy units of the resource.
     */
    val numBusyUnits: TWResponseCIfc

    /**
     * Statistical response representing the utilization of the resource.
     */
    val util: TWResponseCIfc

    /**
     *  If c(t) is the current capacity and b(t) is the current number busy,
     *  then a(t) = c(t) - b(t) is the current number of available units.
     *  Under some capacity change situations, a(t) may be negative.
     */
    val numAvailableUnits: Int

    /**
     *  If a(t) is greater than zero
     */
    val hasAvailableUnits: Boolean

    /**
     *  If b(t) is greater than zero
     */
    val hasBusyUnits: Boolean

    /** The fraction of the current capacity that is currently busy.
     *
     *  if b(t) = 0, then 0,
     *  if b(t) greater than or equal to c(t), then 1.0
     *  else b(t)/c(t)
     */
    val fractionBusy: Double

    /**
     *  The number of busy units at any time t, b(t)
     */
    val numBusy: Int

    /**
     *  The number of times that the resource has been seized (allocated)
     */
    val numTimesSeized: Int

    /**
     *  The number of times that the resource has been released (deallocated)
     */
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
 *  either busy b(t) > 0 or idle b(t) = 0.  If a(t) > 0, then the resource has units that it can be allocated.
 *
 *  Subclasses of Resource implement additional state behavior.
 *
 *  @param parent the parent holding this resource
 *  @param name the name of the resource
 *  @param capacity the initial capacity of the resource.  Cannot be changed during a replication. The default capacity is 1.
 *  @param collectStateStatistics indicates if detailed statistics are automatically collected on time spent in resource
 *  states. The default is false.  Utilization and busy statistics are always collected unless specifically turned off
 *  via TWResponseCIfc references.
 */
open class Resource(
    parent: ModelElement,
    name: String? = null,
    capacity: Int = 1,
    collectStateStatistics: Boolean = false
) : ModelElement(parent, name), ResourceCIfc {

    private var myInactiveProp: Response? = null
    private var myIdleProp: Response? = null

    init {
        require(capacity >= 1) { "The initial capacity of the resource must be >= 1" }
        if (collectStateStatistics) {
            myInactiveProp = Response(this, name = "${this.name}:PTimeIdle")
            myIdleProp = Response(this, name = "${this.name}:PTimeInactive")
        }
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
            //TODO do something about state??
            if ((capacity == 0) && (numBusy == 0)) {
                myState = myInactiveState
            }
        }

    protected val myNumBusy = TWResponse(this, "${this.name}:BusyUnits")
    override val numBusyUnits: TWResponseCIfc
        get() = myNumBusy

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
            } else if (newValue < previousValue) {
                // decreasing the number busy
                val decrease = previousValue - newValue
                myNumBusy.decrement(decrease.toDouble())
                numTimesReleased++
            }
            myUtil.value = fractionBusy
            //TODO do something about state??
        }

    override val numAvailableUnits: Int
        get() = capacity - numBusy

    override val hasAvailableUnits: Boolean
        get() = numAvailableUnits > 0

    override val hasBusyUnits: Boolean
        get() = numBusy > 0.0

    override val fractionBusy: Double
        get() {
            return if (numBusy == 0) {
                0.0
            } else if (numBusy >= capacity) {
                1.0
            } else {
                numBusy / capacity.toDouble()
            }
        }

    protected val myUtil = TWResponse(this, "${this.name}:Util")
    override val util: TWResponseCIfc
        get() = myUtil

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
    protected val myIdleState: ResourceState = ResourceState("${this.name}Idle", collectStateStatistics)
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
    protected val myInactiveState: ResourceState = ResourceState("${this.name}_Inactive", collectStateStatistics)
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
            ProcessModel.logger.trace { "$time > Resource: $name exited state ${field.name}" }
            myPreviousState = field // remember what the current state was
            field = nextState // transition to next state
            field.enter(time) // enter the current state
            ProcessModel.logger.trace { "$time > Resource: $name entered state ${field.name}" }
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
        val t = totalStateTime
        println("totalStateTime = $t")
        if (t > 0.0) {
            println("busy totalTimeInState = ${busyState.totalTimeInState}")
            println("idle totalTimeInState = ${idleState.totalTimeInState}")
            println("inactive totalTimeInState = ${inactiveState.totalTimeInState}")
            myIdleProp?.value = idleState.totalTimeInState / t
            myInactiveProp?.value = inactiveState.totalTimeInState / t
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
        //TODO checking state is an issue
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
        require(canAllocate(amountNeeded)) { "The amount requested, $amountNeeded cannot currently be allocated" }
        val allocation = Allocation(entity, this, amountNeeded, queue, allocationName)
        if (!entityAllocations.contains(entity)) {
            entityAllocations[entity] = mutableListOf()
        }
        entityAllocations[entity]?.add(allocation)
        numBusy = numBusy + amountNeeded
//        myNumBusy.increment(amountNeeded.toDouble())
//        numTimesSeized++
//        myUtil.value = fractionBusy
        // resource becomes busy (or stays busy), because an allocation occurred
//        myState = myBusyState
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
        numBusy = numBusy - allocation.amount
//        myNumBusy.decrement(allocation.amount.toDouble())
//        numTimesReleased++
//        myUtil.value = fractionBusy
//        if (myNumBusy.value == 0.0) {
//            myState = myIdleState
//        }
        // need to also deallocate from the entity
        allocation.entity.deallocate(allocation)
        // deallocate the allocation, so it can't be used again
        allocation.deallocate()
        deallocationNotification(allocation)
    }

//    protected open fun resourceEnteredFailure() {
//        val list = allocations()
//        for (allocation in list) {
//            allocation.failureActions.beginFailure(allocation)
//        }
//    }
//
//    protected open fun resourcedExitedFailure() {
//        val list = allocations()
//        for (allocation in list) {
//            allocation.failureActions.endFailure(allocation)
//        }
//    }

    protected open inner class ResourceState(aName: String, stateStatistics: Boolean = false) :
        State(name = aName, useStatistic = stateStatistics) {
        //TODO need to have states: idle, busy, inactive?
    }

//    protected inner class FailedState(aName: String, stateStatistics: Boolean = false) :
//        ResourceState(aName, stateStatistics) {
//        override fun onEnter() {
//            resourceEnteredFailure()
//        }
//
//        override fun onExit() {
//            resourcedExitedFailure()
//        }
//    }
}