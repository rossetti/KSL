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
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.statistic.State
import ksl.utilities.statistic.StateAccessorIfc
import kotlin.math.roundToInt

/**
 *  A Resource represents a number of common units that can be allocated to entities.  A resource
 *  has an initial capacity that can be changed during a replication.
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
open class Resource @JvmOverloads constructor(
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
    protected val myCapacityChangeQSet: MutableSet<RequestQ> = mutableSetOf<RequestQ>()

    final override var initialRequestQueueNotificationRule: RequestQueueNotificationRuleIfc = DefaultRequestQueueNotificationRule
        set(value) {
            require(model.isNotRunning) { "The model must not be running when changing the initial request queue notification rule." }
            field = value
        }

    /**
     *  Changing this during a replication will only affect the current replication.
     *  The rule will be reset to the initial setting at the beginning of each replication.
     */
    var requestQNotificationRule: RequestQueueNotificationRuleIfc = initialRequestQueueNotificationRule

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
    protected val entityAllocations: MutableMap<Entity, MutableList<Allocation>> = mutableMapOf()

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
     *  A general queue that a `seize()` waits in is registered by the seize, on the resource or on every member
     *  of the pool, so that freed units reach the requests waiting in it however the units are freed. Calling
     *  this function yourself is needed only to have a queue notified before any seize has used it. A queue
     *  may be registered with more than one resource and a resource may have many queues registered.
     */
    fun registerCapacityChangeQueue(queue: RequestQ) {
        myCapacityChangeQSet.add(queue)
    }

    /**
     *  A trigger for units of this resource, for use in a wait for the first of several things
     *  (`waitForFirst`). The trigger fires when the request it places in [queue] is served while the
     *  wait is open, or at once when the units can be allocated and no waiting request would be
     *  chosen first, as `seize` takes them. Its value is the allocation, made after the wait ends, as
     *  `seize` makes it; release it as any other. While the wait is open the request waits in [queue]
     *  as a `seize` request does. If the wait ends otherwise, the request leaves the queue.
     *
     *  A trigger holds no state of any wait, so it can be kept and used in any number of waits. For a
     *  movable resource, which is seized one unit at a time, [amountNeeded] must be 1.
     *
     *  @param amountNeeded the number of units wanted; at least 1
     *  @param seizePriority the priority of the yield a wait with this trigger makes before it starts,
     *  as `seize` yields
     *  @param queue the queue the request waits in
     *  @param recordRenegedWait whether a request that leaves without service (the wait timed out, or
     *  another trigger fired first) records its wait in the queue's time in queue. With false, the
     *  queue's number in queue has counted the request while its time in queue leaves it out, so the
     *  two no longer agree as Little's law requires.
     */
    fun seizeTrigger(
        amountNeeded: Int = 1,
        seizePriority: Int = ProcessModel.SEIZE_PRIORITY,
        queue: RequestQ,
        recordRenegedWait: Boolean = true
    ): WaitTrigger<Allocation> = ResourceSeizeTrigger(this, amountNeeded, seizePriority, queue, recordRenegedWait)

    /**
     *  Unregisters a queue from this resource, so that units freed on it are no longer offered to the
     *  requests in [queue]. If the queue was not registered, nothing occurs.
     *
     *  Use it only for a queue you have finished with. A seize that waits in [queue] registers it again, and
     *  a request left waiting in an unregistered queue is reached only by releases made through that queue,
     *  so it could wait beside idle units. For that reason this function throws while a request in [queue]
     *  is waiting for this resource; check first with [RequestQ.hasRequestsWaitingFor].
     *
     *  @throws IllegalStateException if a request in [queue] is waiting for this resource, directly or
     *  through a pool it belongs to
     */
    fun unregisterCapacityChangeQueue(queue: RequestQ) {
        check(!queue.hasRequestsWaitingFor(this)) {
            "Queue (${queue.name}) cannot be unregistered from resource ($name): it holds requests waiting " +
                    "for it, which would then not be offered units freed on it. Check with hasRequestsWaitingFor() first."
        }
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

    /** How many changes to this resource's units are under way; nested calls count once each. */
    private var myChangeDepth = 0

    /** The listener notices of the change under way, delivered in order once it is complete. */
    private val myDeferredNotices = ArrayDeque<() -> Unit>()

    /**
     *  Runs [block] as one change to this resource's units: the allocation listeners are told of it once it is
     *  complete, in the order of its steps. A listener that changes the resource makes a change of its own, whose
     *  notices are delivered when it completes, before the listener returns; the notices of the first change still
     *  to be delivered then read the resource as the listener's change left it. A change that throws tells nothing.
     */
    private inline fun <T> change(block: () -> T): T {
        myChangeDepth++
        val result = try {
            block()
        } catch (e: Throwable) {
            myChangeDepth--
            if (myChangeDepth == 0) myDeferredNotices.clear()
            throw e
        }
        myChangeDepth--
        if (myChangeDepth == 0 && myDeferredNotices.isNotEmpty()) deliverNotices()
        return result
    }

    /** Delivers the notices of the change just completed; a listener's own change delivers its own. */
    private fun deliverNotices() {
        val notices = myDeferredNotices.toList()
        myDeferredNotices.clear()
        for (notice in notices) notice()
    }

    /** Tells the listeners [notice] once the change under way is complete; at once when none is. */
    private fun tellListeners(notice: () -> Unit) {
        if (myChangeDepth > 0) myDeferredNotices.addLast(notice) else notice()
    }

    /**
     * Notifies any attached listeners when units are allocated, once the change is complete.
     */
    protected fun allocationNotification(allocation: Allocation) {
        tellListeners { for (listener in allocationListeners.toList()) listener.allocate(allocation) }
    }

    /** Notifies the attached listeners that [amount] units of [lender] were lent to [borrower]. */
    private fun loanNotification(lender: Allocation, borrower: Allocation, amount: Int) {
        tellListeners { for (listener in allocationListeners.toList()) listener.unitsLent(lender, borrower, amount) }
    }

    /** Notifies the attached listeners that [amount] lent units came back to [lender]. */
    private fun returnNotification(lender: Allocation, amount: Int) {
        tellListeners { for (listener in allocationListeners.toList()) listener.unitsReturned(lender, amount) }
    }

    /**
     * Notifies any attached listeners when units are deallocated, once the change is complete.
     */
    protected fun deallocationNotification(allocation: Allocation) {
        tellListeners { for (listener in allocationListeners.toList()) listener.deallocate(allocation) }
    }

    protected val myCapacity: TWResponse =
        TWResponse(this, name = "${this.name}:NumActiveUnits", initialValue = capacity.toDouble())

    val numActiveUnits: TWResponseCIfc
        get() = myCapacity

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

    init {
        stateReportingOption = false
        myCapacity.defaultReportingOption = false
        myInactiveProp.defaultReportingOption = false
        myIdleProp.defaultReportingOption = false
        myBusyProp.defaultReportingOption = false
    }

    @set:KSLControl(
        controlType = ControlType.INTEGER,
        lowerBound = 0.0
    )
    override var initialCapacity : Int = capacity
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

    override var capacity : Int = capacity
        protected set(value) {
            require(value >= 0) { "The capacity must be >= 0" }
            field = value
            if (value > highestCapacity) highestCapacity = value
            updateState()
            myCapacity.value = field.toDouble()
            instantUtilTW.value = instantaneousUtil
        }

    /**
     *  The highest capacity this resource has had in the current replication, counting every change, even one
     *  undone within the same event. Work allocated at a peak may stay busy after the capacity falls under
     *  `IGNORE`, so this, not the capacity now, bounds the busy units.
     */
    internal var highestCapacity: Int = capacity
        private set

    protected val myNumBusy: TWResponse = TWResponse(this, "${this.name}:NumBusyUnits")
    override val numBusyUnits: TWResponseCIfc
        get() = myNumBusy

    protected val myFractionBusy: Response = Response(this, name = "${this.name}:ScheduledUtil")
    override val scheduledUtil: ResponseCIfc
        get() = myFractionBusy

    protected val mySeizeCounter: Counter = Counter(this, name = "${this.name}:SeizeCount")
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
            } else if (newValue < previousValue) {
                // decreasing the number busy
                val decrease = previousValue - newValue
                myNumBusy.decrement(decrease.toDouble())
            }
            updateState()
            instantUtilTW.value = instantaneousUtil
        }

    /** The state the capacity and the busy units give: inactive, idle or busy. */
    private fun stateFromUnits(): ResourceState =
        if (numBusy == 0 && capacity == 0) myInactiveState
        else if (numBusy == 0) myIdleState
        else myBusyState

    /**
     *  Assigns the state the capacity and the busy units give, even when it is unchanged, as each change of
     *  either always has. While failed it does nothing: the failed state takes precedence until repair.
     */
    protected fun updateState() {
        if (!isFailed) myState = stateFromUnits()
        myFailureClock?.update()
    }

    /**
     * Units promised to entities that have been woken to take them and have not yet resumed. A waiting
     * entity is not given its units when it is woken; it takes them when it resumes, at the same instant.
     * In between, the units are promised to it, so that nothing else, another queue, another pool or a
     * new arrival, can take them first.
     */
    internal var myPromisedUnits: Int = 0

    /**
     * The units that can be allocated now: the capacity less the busy units and less any units promised
     * to a waiting entity that has been woken to take them. None while the resource is failed.
     */
    override val numAvailableUnits: Int
         get() = if (isFailed) 0 else maxOf(0, capacity - numBusy - myPromisedUnits)

    /**
     *  The units not busy and not promised: those a capacity decrease can remove at once. Unlike
     *  [numAvailableUnits] it is not zeroed by a failure, since capacity changes apply underneath a failure.
     *
     *  Two readings of the units must not be confused. [numAvailableUnits] is what can be *allocated* now,
     *  none while failed; every allocation and offer reads it. [numIdleUnits] is what is *idle* now, failed
     *  or not; the capacity decreases read it.
     */
    internal val numIdleUnits: Int
        get() = maxOf(0, capacity - numBusy - myPromisedUnits)

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
                refreshCurrentState()
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
                refreshCurrentState()
            }
            return myIdleState
        }

    /** The failed state, keeps track of when no units are available because the resource is failed.
     *  It takes precedence over the other three until the resource is repaired.
     */
    protected val myFailedState: ResourceState = ResourceState("${this.name}_Failed")
    override val failedState: StateAccessorIfc
        get() {
            if (isFailed) {
                refreshCurrentState()
            }
            return myFailedState
        }

    /** The inactive state, keeps track of when no units
     * are available because the resource's capacity is zero.
     */
    protected val myInactiveState: ResourceState = InactiveState("${this.name}_Inactive")
    override val inactiveState: StateAccessorIfc
        get() {
            if (isInactive) {
                refreshCurrentState()
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

    /** True when no units are allocated and the capacity is greater than zero. A resource with zero
     * capacity and nothing allocated is inactive, not idle (see isInactive).
     */
    override val isIdle: Boolean
        get() = myState === myIdleState

    /** True when some units are allocated, whatever the capacity. A resource whose capacity has
     * just gone to zero stays busy until its allocated units are released.
     */
    override val isBusy: Boolean
        get() = myState === myBusyState

    /** True from a [fail] until the [repair] that follows it. Final: the resource's availability depends on it. */
    final override val isFailed: Boolean
        get() = myState === myFailedState

    /** True when no units are allocated and the capacity is zero, as when a resource is off shift
     * and its last unit has been released.
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
    fun isUsing(entity: Entity): Boolean {
        return entityAllocations.contains(entity)
    }

    /**
     *  Computes the number of different allocations of the resource held by the entity.
     *  Recall that allocations can be for different amounts.
     *
     * @param entity the entity that might be using the resource
     * @return the count of the number of distinct allocations
     */
    fun numberOfAllocations(entity: Entity): Int {
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
    fun allocations(entity: Entity): List<Allocation> {
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
    fun totalAmountAllocated(entity: Entity): Int {
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
        myQuietAvailabilityHooks = true
        myState.exit(time)
        myBusyState.resetStateCollection()
        myInactiveState.resetStateCollection()
        myIdleState.resetStateCollection()
        myFailedState.resetStateCollection()
        myState.enter(time)
        myQuietAvailabilityHooks = false
    }

    /**
     *  Brings the current state's time up to date by leaving it and entering it again at this instant. The
     *  state does not change, so the availability hooks keep quiet: reading a state's statistics, or
     *  resetting them at the warm-up, tells no one that the resource became inactive or active.
     */
    private fun refreshCurrentState() {
        myQuietAvailabilityHooks = true
        myState.exit(time)
        myState.enter(time)
        myQuietAvailabilityHooks = false
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
            myFailedProp?.value = failedState.totalTimeInState / totalStateTime
        }
    }

    override fun initialize() {
        super.initialize()
        // every replication starts repaired, with its failures, if declared, starting afresh
        resetFailures()
        entityAllocations.clear()
        myLoans.clear()
        myReservations.clear()
        myWaitingPreemptors.clear()
        numTimesPreempted = 0
        myPromisedUnits = 0
        startStateTime = 0.0
        numTimesSeized = 0
        numTimesReleased = 0
        // note that initialize() causes state to not be entered, and clears it accumulators
        myIdleState.initialize()
        myBusyState.initialize()
        myInactiveState.initialize()
        myFailedState.initialize()
        // myState is one of the states (idle, busy, inactive, failed)
        // but now the state thinks we are not in it, make it think we are in it
        myState.enter(time)
        // now exit it for the inactive state
        myState = myInactiveState // will cause previous state to be some arbitrary state from end of last replication
        // make previous state inactive and current state idle, for start of the replication
        myState = myIdleState
        highestCapacity = 0
        capacity = initialCapacity
        requestQNotificationRule = initialRequestQueueNotificationRule
        // every replication starts on shift; the next goOffShift makes a control for this replication
        myShift = null
        // every replication starts its behavior afresh, if it has one and is to start with it
        clearBehaviorState()
        if (myBehaviorBlock != null && behaviorActiveAtStart) {
            launchBehavior()
        }
    }

    /**
     *  Takes this resource off shift and back on. Made by the first [goOffShift] of a replication, so
     *  that it restores the initial capacity of that replication, and dropped in [initialize].
     */
    private var myShift: ShiftControl? = null

    /** True while this resource has been taken off shift with [goOffShift]. */
    val isOffShift: Boolean
        get() = myShift?.isOffShift ?: false

    /**
     *  Takes this resource off shift until [goOnShift]: its capacity goes to zero, so new seize
     *  requests wait in their queues. Work already allocated finishes under the resource's
     *  [capacityChangeRule], `IGNORE` by default: a unit that is busy finishes its current allocation,
     *  and when it is released it goes to the off-shift change, not to the next waiting request. A
     *  second call while off shift does nothing.
     *
     *  A resource whose [initialCapacity] is 0 has nothing to take off shift, and this throws; check
     *  that the initial capacity is greater than 0 first. A resource that uses a capacity schedule takes
     *  its capacity from the schedule alone, and this throws too; [hasSchedule] is the check.
     */
    fun goOffShift(): Unit = change {
        val shift = myShift ?: ShiftControl(this).also { myShift = it }
        shift.goOffShift()
        myFailureClock?.update()
    }

    /**
     *  Brings this resource back on shift, restoring the [initialCapacity] it had when this
     *  replication's first [goOffShift] was made. The requests waiting for it are offered the units as
     *  for any capacity increase. Nothing happens when the resource is on shift.
     */
    fun goOnShift(): Unit = change {
        val shift = myShift ?: return@change
        if (!shift.isOffShift) return@change
        val capacityBefore = capacity
        shift.goOnShift()
        myFailureClock?.update()
        // The capacity increase back to the on-shift capacity runs afterUnitsRestored itself. When the capacity
        // was not raised, because it had been put back directly while off shift, no increase ran: the return to
        // shift is still the moment the units can be used again, so the hook is called here.
        if (capacity <= capacityBefore && !isRunEnded) afterUnitsRestored()
    }

    /**
     *  Called when this resource's units come back by other than a release: at the end of a capacity
     *  increase, including the one a [goOnShift] makes, and of a [repair], and at a [goOnShift] that raises
     *  no capacity because the capacity was put back directly while off shift. It is called once for each,
     *  except under `CapacityChangeRule.WAIT` when the return to shift waits behind a decrease still in
     *  progress: then once at the return and once when the increase is made. It is not called once the run
     *  has ended. A subclass whose waiting requests are not reached by the offer to
     *  the resource's own queues offers its units here.
     */
    protected open fun afterUnitsRestored() {}

    /**
     *  What happens to work in service when this resource's units are taken away, by a failure or by a
     *  more urgent request: `FINISH` (the default) lets it complete, `RESUME` holds the holder's delay and
     *  goes on with the time left, `RESTART` holds it and starts it again in full. A failure or a preemption
     *  can override it for itself. Set while the model is not running.
     */
    var interruptRule: InterruptRule = InterruptRule.FINISH
        set(value) {
            require(model.isNotRunning) { "The interrupt rule of $name cannot be changed while the model is running." }
            field = value
        }

    // ---- preemption ------------------------------------------------------------------------------

    /**
     *  Units of [lender] in use by [borrower], taken by a preemption. The lender's allocation keeps its
     *  amount, with the units counted as missing for it. [lender] is null when the lender's allocation has
     *  ended: the units are then the borrower's until it releases them. When a borrower that lent the units
     *  on ends, its borrowers' loans are recorded again against the lenders it borrowed from.
     */
    internal class Loan(val lender: Allocation?, val borrower: Allocation, val amount: Int)

    /**
     *  A preemption under `FINISH` waiting for the units its victims still use: [request] takes the units of
     *  any release, capacity increase or repair of this resource ahead of the queue until [stillNeeded] is 0,
     *  after any reservation of a more urgent entity.
     */
    internal class Reservation(val request: ProcessModel.Entity.Request, var stillNeeded: Int) {
        /**
         *  True once every unit has been promised and the request has been woken to take them. The
         *  reservation stays until the request takes its units and leaves its queue, so a promise broken
         *  in between does not cost it its place: it collects again.
         */
        var isComplete: Boolean = false

        /** Collects again, from nothing, after the promise of a completed reservation broke. */
        fun reopen() {
            isComplete = false
            stillNeeded = request.amountRequested
        }
    }

    /** What a preemption would take: the free units, and the units of each victim. */
    internal class PreemptionPlan(val free: Int, val takes: List<Pair<Allocation, Int>>)

    private val myLoans: MutableList<Loan> = mutableListOf()

    private val myReservations: MutableList<Reservation> = mutableListOf()

    /** The loans now recorded on this resource, for checks. */
    internal val loans: List<Loan>
        get() = myLoans

    /** The reservations now waiting, for checks. */
    internal val reservations: List<Reservation>
        get() = myReservations

    /** The queues registered on this resource, for checks. */
    internal val registeredQueues: Set<RequestQ>
        get() = myCapacityChangeQSet

    /**
     *  The number of preemptions of this resource in the replication: each `preempt` that took units from
     *  at least one holder, or reserved them from holders that finish their work.
     */
    var numTimesPreempted: Int = 0
        protected set

    /**
     *  What a preemption by [entity] for [amountNeeded] units would take: the free units, then units of the
     *  holders whose entity's priority is lower (a larger number) than [entity]'s, lowest priority first and,
     *  among equals, the most recently allocated first, only as many as are still needed. Units [returning]
     *  to a holder from a loan that is ending count as that holder's. Null when the free units are already
     *  enough, when the free and lendable units together are too few, when more is needed than the capacity the
     *  resource is heading to (its current change, and any change asked for and waiting, but not a capacity
     *  schedule's item waiting its turn), or, for a preemption that takes units at once ([takesAtOnce], under
     *  `RESUME` or `RESTART`), while a `FINISH` reservation of another entity, as urgent or more, is still collecting
     *  its units.
     */
    internal fun planPreemption(
        entity: ProcessModel.Entity,
        amountNeeded: Int,
        returning: Map<Allocation, Int> = emptyMap(),
        takesAtOnce: Boolean = true
    ): PreemptionPlan? {
        // off shift, or with the capacity cut below what is needed, nothing is taken: units above the capacity
        // are only finishing the work they were allocated for. Under the WAIT rule the capacity is cut only as busy
        // units are released, so a pending decrease counts at the capacity it is heading to, and off shift at none.
        if (amountNeeded > reachableCapacity) return null
        // a FINISH preemption still collecting its units ranks its claim: units are not taken at once, under RESUME
        // or RESTART, from holders ahead of one that is as urgent or more. Another FINISH preemption only reserves,
        // and the reservations are served in order of priority.
        if (takesAtOnce && myReservations.any { isCollecting(it) && it.request.entity !== entity && it.request.entity.priority <= entity.priority }) {
            return null
        }
        val free = numAvailableUnits
        var short = amountNeeded - free
        if (short <= 0) return null
        fun lendable(a: Allocation) = a.amount - a.missingByLoan + (returning[a] ?: 0)
        val candidates = allocations()
            .filter { it.myEntity.priority > entity.priority && lendable(it) > 0 }
            .sortedWith(
                compareByDescending<Allocation> { it.myEntity.priority }.thenByDescending { it.timeAllocated }
                    // allocated at the same instant: the later allocation, with the larger id, is the more recent
                    .thenByDescending { it.id }
            )
        val takes = mutableListOf<Pair<Allocation, Int>>()
        for (victim in candidates) {
            if (short == 0) break
            val k = minOf(short, lendable(victim))
            takes.add(victim to k)
            short -= k
        }
        return if (short > 0) null else PreemptionPlan(free, takes)
    }

    /**
     *  Allocates [amountNeeded] units to [entity] under [plan]: the free units, and the victims' units as
     *  loans, which go missing for them under [rule]. The units are counted busy once, for the borrower.
     */
    internal fun allocateWithLoans(
        entity: ProcessModel.Entity,
        amountNeeded: Int,
        plan: PreemptionPlan,
        queue: RequestQ,
        rule: InterruptRule,
        allocationName: String?
    ): Allocation = change {
        require(rule != InterruptRule.FINISH) { "Under FINISH nothing is taken from a holder of $name in service." }
        val allocation = Allocation(entity, this, amountNeeded, queue, allocationName)
        entityAllocations.getOrPut(entity) { mutableListOf() }.add(allocation)
        numBusy = numBusy + plan.free
        numTimesSeized++
        mySeizeCounter.increment()
        entity.allocate(allocation)
        for ((victim, k) in plan.takes) {
            myLoans.add(Loan(victim, allocation, k))
            victim.unitsGoMissing(byFailure = 0, byLoan = k, rule = rule)
            loanNotification(victim, allocation, k)
        }
        numTimesPreempted++
        allocationNotification(allocation)
        allocation
    }

    /**
     *  Reserves for [request], under `FINISH`, the units its victims still use: it is promised the [free]
     *  units now and takes the rest from the next releases, ahead of the queue.
     */
    internal fun reserve(request: ProcessModel.Entity.Request, free: Int) {
        if (free > 0) request.promise(this, free)
        val reservation = Reservation(request, request.amountRequested - free)
        request.reservation = reservation
        myReservations.add(reservation)
        // only a FINISH preempt reserves; its rule is recorded so that a move of the request finds it, as it
        // finds the rule of a preempt that waits
        request.waitingPreemptRule = InterruptRule.FINISH
        numTimesPreempted++
    }

    /** Drops the reservation of a request leaving its queue; its promises are withdrawn with the request's. */
    internal fun dropReservation(request: ProcessModel.Entity.Request) {
        myReservations.removeAll { it.request === request }
        request.reservation = null
    }

    /**
     *  Gives the available units to the waiting reservations, the most urgent first and the oldest first
     *  among equals, and resumes each one that has all it needs.
     */
    private fun serveReservations(priority: Int, source: ResumeSource, detail: String?) {
        var withdrawn = 0
        // a stable sort: the list is in the order the reservations were made
        for (reservation in myReservations.sortedBy { it.request.entity.priority }) {
            if (numAvailableUnits <= 0) break
            // one already woken to take its units keeps its place until it takes them
            if (reservation.isComplete) continue
            if (!isCollecting(reservation)) {
                // more than the capacity heading to: it holds nothing, and collects when the capacity returns
                val held = reservation.request.promises[this] ?: 0
                if (held > 0) {
                    reservation.request.withdrawPromise(this, held)
                    reservation.stillNeeded += held
                    withdrawn += held
                }
                continue
            }
            val k = minOf(reservation.stillNeeded, numAvailableUnits)
            reservation.request.promise(this, k)
            reservation.stillNeeded -= k
            if (reservation.stillNeeded > 0) break
            reservation.isComplete = true
            val request = reservation.request
            val queue = request.queue as RequestQ
            queue.resumeWoken(request, priority, source, "reservation_completed_resource=$name, request_id=${request.id}, $detail")
        }
        // a pending change counted the promised units it needs
        if (withdrawn > 0) unitsWithdrawnFromPromise(withdrawn)
    }

    /**
     *  The capacity the resource is heading to: the current capacity, cut to that of its current change and of any
     *  change asked for and waiting behind it, as an off-shift or a cut by hand can. A capacity schedule's item
     *  whose turn has not come does not count: under WAIT the schedule runs late, and its next item can wait while
     *  the current one still runs.
     */
    private val reachableCapacity: Int
        get() {
            var reachable = myCurrentChangeNotice?.let { minOf(capacity, it.capacity) } ?: capacity
            for (waiting in myWaitingChangeNotices) {
                if (!waiting.isScheduleItem) reachable = minOf(reachable, waiting.capacity)
            }
            return reachable
        }

    /**
     *  True for a reservation still collecting its units that the capacity the resource is heading to could fill.
     *  One for more units than that holds nothing and blocks no one; it collects when the capacity returns.
     */
    private fun isCollecting(reservation: Reservation): Boolean =
        !reservation.isComplete && reservation.request.amountRequested <= reachableCapacity

    /**
     *  A seize has just taken units of this resource, out of [servedFrom], as a woken or reserved request taking
     *  what it was promised does. Promised units cannot be lent, so a waiting preemptor that outranks the request
     *  could not plan against them; now they are an allocation it may take from, and it plans again.
     */
    internal fun afterSeizeTook(servedFrom: RequestQ): Unit = change {
        if (isRunEnded) return@change
        // A request selection rule sees woken requests, which still wait in the queue with their units promised;
        // one serving strictly from the head chooses the woken head again and serves no one behind it. Now the head
        // has left, the units still free are offered again, so the request it chooses next is served at once.
        if (servedFrom.requestSelectionRule != null && numAvailableUnits > 0) {
            offerFreedUnits(ProcessModel.RESUME_PRIORITY, ResumeSource.UNKNOWN_INTERNAL, "woken_request_left_resource=$name")
            return@change
        }
        if (myWaitingPreemptors.isEmpty()) return@change
        replanWaitingPreemptors(ProcessModel.RESUME_PRIORITY, ResumeSource.UNKNOWN_INTERNAL, "promised_units_taken_resource=$name")
    }

    /** A waiting preemptor was moved here: the waiting preemptors plan, as on an arrival. */
    internal fun preemptorMovedIn(): Unit = change {
        if (isRunEnded) return@change
        replanWaitingPreemptors(ProcessModel.RESUME_PRIORITY, ResumeSource.REQUEST_MOVED_TO_RESOURCE, "preemptor_moved_in_resource=$name")
    }

    /** Set when a reservation was given up by a decrease, so its units are offered once the change is applied. */
    private var myReservationReturned: Boolean = false

    /** Offers the units of reservations a decrease gave up, once the change has been applied. */
    private fun offerUnitsOfReturnedReservations(priority: Int) {
        // a change asked for and now waiting can lower the capacity heading to below what a reservation needs:
        // it stops collecting, and gives back what it holds at once rather than at the next offer
        var withdrawn = 0
        for (reservation in myReservations) {
            if (reservation.isComplete || isCollecting(reservation)) continue
            val held = reservation.request.promises[this] ?: 0
            if (held <= 0) continue
            reservation.request.withdrawPromise(this, held)
            reservation.stillNeeded += held
            withdrawn += held
            myReservationReturned = true
        }
        // a pending change counted the promised units it needs; once the loop is over, as handing them on can
        // start the next change
        if (withdrawn > 0) unitsWithdrawnFromPromise(withdrawn)
        if (!myReservationReturned) return
        myReservationReturned = false
        if (!isRunEnded) offerFreedUnits(priority, ResumeSource.REQUEST_Q_CAPACITY_INCREASE, "reservation_given_up_resource=$name")
    }

    /**
     *  For a capacity decrease short of idle units: takes back up to [need] units promised to reservations still
     *  collecting theirs, the least urgent first and the latest made first among equals, and returns how many it
     *  took. Each such reservation needs them again, and collects them as units come free. A complete reservation,
     *  whose request has been woken to take its units, keeps them, as a woken request does. Without this a WAIT
     *  decrease and a part-filled reservation could each wait for the units the other holds.
     */
    private fun reclaimFromReservationsCollecting(need: Int): Int {
        var taken = 0
        val collecting = myReservations.filter { !it.isComplete }
            .withIndex().sortedWith(compareBy<IndexedValue<Reservation>>({ -it.value.request.entity.priority }, { -it.index }))
            .map { it.value }
        for (reservation in collecting) {
            if (taken >= need) break
            val request = reservation.request
            val held = request.promises[this] ?: 0
            if (held <= 0) continue
            val k = minOf(held, need - taken)
            request.withdrawPromise(this, k)
            reservation.stillNeeded += k
            taken += k
        }
        return taken
    }

    /**
     *  As a capacity decrease to [limit] is applied: a reservation still collecting, for more units than [limit],
     *  could never complete, and would hold its promised units, and so the resource, for good. It is given up: its
     *  units are no longer promised, and its request waits again as a `FINISH` preemptor, planning again when
     *  units come back, as one that found too few on arrival does. It took nothing, so it no longer counts as a
     *  preemption.
     */
    private fun returnReservationsBeyond(limit: Int) {
        for (reservation in myReservations.filter { !it.isComplete && it.request.amountRequested > limit }) {
            val request = reservation.request
            val held = request.promises[this] ?: 0
            dropReservation(request)
            if (held > 0) request.withdrawPromise(this, held)
            numTimesPreempted--
            waitAsPreemptor(request as ProcessModel.Entity.ResourceRequest, InterruptRule.FINISH)
            myReservationReturned = true
        }
    }

    // ---- failures --------------------------------------------------------------------------------

    /**
     *  Fails this resource now: no unit can be allocated until [repair], and the requests waiting for it
     *  go on waiting. Its holders meet [rule]: under `FINISH` they complete their work, and the units they
     *  release are kept from the waiting requests until the repair; under `RESUME` and `RESTART` all their
     *  units go missing, so a holder's delay that depends on them is held, and a delay it starts while
     *  failed begins held. The capacity is unchanged, and capacity changes and shifts still apply
     *  underneath. A resource that is already failed throws; [isFailed] is the check.
     *
     *  @param rule what happens to work in service; by default the resource's [interruptRule]
     */
    fun fail(rule: InterruptRule = interruptRule) {
        check(!isFailed) { "$name is already failed; check isFailed before calling fail()." }
        failWith(rule, declared = false)
    }

    /**
     *  Fails the resource. The failure is marked as a declared one, or not, before its listeners are told, so a
     *  listener that repairs it and fails the resource again by hand makes a failure of the model's own, which the
     *  declared failures leave alone.
     */
    private fun failWith(rule: InterruptRule, declared: Boolean): Unit = change {
        // nothing was available while inactive and nothing is while failed: no change to tell of
        val inactive = isInactive
        myQuietAvailabilityHooks = true
        myState = myFailedState
        myQuietAvailabilityHooks = false
        myFailure = FailureInProgress(rule, declared, inactive)
        if (rule != InterruptRule.FINISH) {
            // units already missing (lent to a preemptor) go missing through the borrower's allocation
            for (a in allocations()) {
                val units = a.amount - a.missingUnits
                if (units > 0) a.unitsGoMissing(byFailure = units, byLoan = 0, rule = rule)
            }
        }
        myNumFailures?.increment()
        failureNotification()
        myFailureClock?.update()
    }

    /**
     *  Repairs this resource: the units its holders lost come back (a held delay goes on, or starts again
     *  under `RESTART`), the resource takes the state its capacity and busy units give, and its available
     *  units are offered to the waiting requests as for a capacity increase. A resource that is not failed
     *  throws; [isFailed] is the check.
     */
    fun repair(): Unit = change {
        check(isFailed) { "$name is not failed; check isFailed before calling repair()." }
        val failure = checkNotNull(myFailure) { "$name is failed with no failure in progress." }
        // a waiting preemptor can take back units in the offer below: a restored trigger fires only for the
        // allocations whose units are still back once the offer is made
        settlingRestores {
            for (a in allocations()) {
                if (a.missingByFailure > 0) a.unitsComeBack(byFailure = a.missingByFailure, byLoan = 0)
            }
            myFailure = null
            // tell of a change of availability only against the state before the failure: inactive then and now
            // is no change, and inactive then but not now is the resource becoming active
            val next = stateFromUnits()
            myQuietAvailabilityHooks = failure.inactiveAtStart
            myState = next
            myQuietAvailabilityHooks = false
            if (failure.inactiveAtStart && next !== myInactiveState) resourceBecameActive()
            offerFreedUnits(ProcessModel.RELEASE_PRIORITY, ResumeSource.REQUEST_Q_CAPACITY_INCREASE, "repaired_resource=$name")
        }
        failureNotification()
        myFailureClock?.update()
        val failures = myFailuresEntity
        if (failures != null && failures.repaired.isSuspended && !isRunEnded) failures.repaired.resume()
        if (!isRunEnded) afterUnitsRestored()
    }

    /**
     *  The failure in progress: its rule, whether the declared failures started it (rather than a call of
     *  `fail()`), and whether the resource was inactive when it began. Made before any listener is told of the
     *  failure, and taken at the start of the repair, so every part of a failure is set and cleared at once.
     */
    private class FailureInProgress(val rule: InterruptRule, val declared: Boolean, val inactiveAtStart: Boolean)

    /** The failure in progress; null while the resource is not failed. */
    private var myFailure: FailureInProgress? = null

    /** True while a change into or out of the failed state is being made, when the availability hooks keep quiet. */
    private var myQuietAvailabilityHooks: Boolean = false

    private fun failureNotification() {
        tellListeners { for (listener in allocationListeners.toList()) listener.failureStateChanged(this) }
    }

    /** The failures declared by [failures]; null when none are. */
    private var myFailureSpec: FailureSpec? = null

    private class FailureSpec(
        val timeBetween: RandomVariable,
        val repairTime: RandomVariable,
        val basis: ResourceFailureBasis,
        val rule: InterruptRule
    )

    /** True if failures have been declared with [failures]. */
    val hasFailures: Boolean
        get() = myFailureSpec != null

    private var myFailedProp: Response? = null
    private var myNumFailures: Counter? = null

    /**
     *  Declares that this resource fails and is repaired on its own, in every replication: it waits out
     *  [timeBetween] on [basis], fails under [rule], stays failed for [repairTime], and is repaired, over
     *  and over. A failure that comes due while the resource is already failed by a call of [fail] waits for
     *  that repair, and its time between failures starts again from there. A call of [repair] during the
     *  repair time ends that failure early, and the time to the next failure counts from that repair. The
     *  declared failures repair only a failure they started: if the model fails the resource again during
     *  what was the repair time, that failure lasts until the model repairs it. The waiting is done by an entity
     *  of the resource's own, separate from a behavior it may have.
     *
     *  On `BUSY_TIME`, the time counts while at least one unit is working, whatever the shift or a failure made
     *  by [fail]: a unit finishing its work off shift, under a capacity schedule's zero-capacity item, or
     *  through a `FINISH` failure is working; a unit a `RESUME` or `RESTART` failure takes from its holder is not. The time between failures starts at each repair, so a declared
     *  failure's own repair time is not counted. A failure comes due at the exact instant that much busy time
     *  has passed. On
     *  `USES`, [timeBetween] is rounded to a whole number of releases (at least 1), and the resource fails at
     *  the release that completes it, before the released units are offered to anyone waiting. On every
     *  basis the resource fails at the instant the failure comes due, so nothing is allocated in between.
     *
     *  The two times become random variables of this resource, named `<name>:TimeBetweenFailuresRV` and
     *  `<name>:RepairTimeRV`, so their streams are reset and advanced with the model's other random
     *  variables, and an experiment can change their parameters by those names.
     *
     *  It also collects the failure statistics, as [collectFailureStatistics] does. Declared once, while
     *  the model is not running.
     *
     *  @param timeBetween the time, or the number of uses, between failures
     *  @param repairTime the time each failure lasts
     *  @param basis what [timeBetween] counts
     *  @param rule what happens to work in service at each failure; by default the resource's [interruptRule]
     */
    fun failures(
        timeBetween: RVariableIfc,
        repairTime: RVariableIfc,
        basis: ResourceFailureBasis = ResourceFailureBasis.CALENDAR_TIME,
        rule: InterruptRule = interruptRule
    ) {
        check(model.isNotRunning) { "The failures of $name must be declared before the model runs." }
        check(myFailureSpec == null) { "$name already has its failures declared; they are declared once." }
        // the model's own random variables, so that its streams are reset, advanced and controlled as all others are
        myFailureSpec = FailureSpec(
            RandomVariable(this, timeBetween, "${this.name}:TimeBetweenFailuresRV"),
            RandomVariable(this, repairTime, "${this.name}:RepairTimeRV"),
            basis, rule
        )
        if (basis == ResourceFailureBasis.BUSY_TIME) myFailureClock = BusyClock()
        collectFailureStatistics()
    }

    /**
     *  Adds two statistics for this resource's failures, whether they come from [failures] or from calls
     *  of [fail]: the proportion of time it is failed (`PTimeFailed`) and the number of failures in a
     *  replication (`NumFailures`). Without them the failed state still keeps its own times, and no element
     *  is added to the model. Called while the model is not running; a second call does nothing.
     */
    fun collectFailureStatistics() {
        check(model.isNotRunning) { "The failure statistics of $name must be asked for before the model runs." }
        if (myFailedProp != null) return
        myFailedProp = Response(this, name = "${this.name}:PTimeFailed")
        myNumFailures = Counter(this, name = "${this.name}:NumFailures")
    }

    /** The entity waiting out this replication's failures; null when none are declared. */
    private var myFailuresEntity: ResourceFailures? = null

    /** Counts busy time toward the next failure on `BUSY_TIME`; null on the other bases. */
    private var myFailureClock: BusyClock? = null

    /** The releases left before the next failure on `USES`; 0 when not counting. */
    private var myUsesLeft: Int = 0

    private fun resetFailures() {
        myFailure = null
        myFailureClock?.disarm()
        myUsesLeft = 0
        myFailuresEntity = null
        if (myFailureSpec != null) {
            val entity = ResourceFailures()
            myFailuresEntity = entity
            activate(entity.run)
        }
    }

    private fun countUse() {
        // a release made while the replication's entities are terminated completes no failure count
        if (myUsesLeft <= 0 || isRunEnded) return
        myUsesLeft--
        if (myUsesLeft == 0) failureCameDue()
    }

    /** True while [deallocate] runs: a failure coming due then is applied at the end of the release. */
    private var myReleaseInProgress = false

    /** A declared failure came due during the release in progress. */
    private var myFailureDueAtRelease = false

    /**
     *  A declared failure is due: its basis has counted out. It is applied now, so that nothing can be allocated
     *  between its coming due and the resource failing; during a release, at the end of the release, before
     *  the freed units are offered. The failure entity is then woken only to time the repair.
     */
    private fun failureCameDue() {
        if (myReleaseInProgress) myFailureDueAtRelease = true else applyDueFailure()
    }

    private fun applyDueFailure() {
        val entity = myFailuresEntity ?: return
        if (!entity.due.isSuspended || isRunEnded) return
        // a resource failed by hand keeps that failure; the entity waits for its repair, as before
        if (!isFailed) {
            failWith(myFailureSpec!!.rule, declared = true)
            entity.failedWhenDue = true
        }
        entity.due.resume()
    }

    private fun failureClockFired(event: KSLEvent<Nothing>) {
        myFailureClock?.fired()
    }

    /**
     *  The busy time left before the next failure on `BUSY_TIME`. It runs while at least one unit is working
     *  (busy, and not taken from its holder by a failure), whatever the shift or a failure made by hand: when it starts, it schedules the failure for the time
     *  left; when it stops, it banks what is left and cancels the event. It is armed only while the next
     *  declared failure is awaited, so a declared failure's repair time is never counted.
     */
    private inner class BusyClock {
        private var armed = false
        private var running = false
        private var left = 0.0
        private var since = 0.0
        private var event: KSLEvent<Nothing>? = null

        fun arm(busyTime: Double) {
            require(busyTime >= 0.0 && busyTime.isFinite()) { "The busy time between failures of $name must be finite and >= 0; it was $busyTime." }
            armed = true
            running = false
            left = busyTime
            update()
        }

        fun disarm() {
            cancelEvent()
            event = null
            armed = false
            running = false
        }

        fun update() {
            // once the run has ended, a shift's end or a repair in a finally block starts no clock
            if (!armed || isRunEnded) return
            // a unit is working while it is busy and not taken by a failure: a FINISH failure leaves the job
            // running, while under RESUME or RESTART its units go missing and its work is held
            val shouldRun = numBusy - allocations().sumOf { it.missingByFailure } > 0
            if (shouldRun && !running) {
                running = true
                since = time
                event = schedule(::failureClockFired, left)
            } else if (!shouldRun && running) {
                running = false
                // Whether the busy time has run out is read from the clock's own event: when it is due now,
                // nothing is left. Subtracting the times can leave a small residue above zero, which would
                // keep the failure for the next busy period.
                val due = event?.let { it.time <= time } ?: false
                left = if (due) 0.0 else maxOf(0.0, left - (time - since))
                cancelEvent()
                event = null
                // the busy time ran out as the clock stopped, ahead of its own event at this instant: the
                // failure is due now, not at the next busy period
                if (left <= 0.0) fired()
            }
        }

        /** A failure due after the run's end was never placed on the calendar, so there is nothing to cancel. */
        private fun cancelEvent() {
            val e = event ?: return
            if (e.isScheduled) e.cancel = true
        }

        fun fired() {
            event = null
            armed = false
            running = false
            failureCameDue()
        }
    }

    /** The entity that waits out a resource's declared failures; a new one for every replication. */
    // The entity that waits out failures is the resource's own plumbing, not an entity of the model: the animation
    // inventory lists it as internal, as it does a movable resource's driver, and its events are captured.
    @ksl.animation.KSLInternalEntity
    private inner class ResourceFailures : Entity() {
        val due: Suspension = Suspension("${this@Resource.name}:failure due")
        val repaired: Suspension = Suspension("${this@Resource.name}:repaired")

        /** The failure was applied when it came due, before this entity was woken. */
        var failedWhenDue: Boolean = false

        @ksl.animation.KSLAnimatedProcess(name = "failures")
        val run: KSLProcess = process("failures", isDefaultProcess = true) {
            val spec = myFailureSpec!!
            while (true) {
                when (spec.basis) {
                    ResourceFailureBasis.CALENDAR_TIME -> delay(spec.timeBetween.value)
                    ResourceFailureBasis.BUSY_TIME -> {
                        myFailureClock!!.arm(spec.timeBetween.value)
                        suspendFor(due)
                    }
                    ResourceFailureBasis.USES -> {
                        myUsesLeft = maxOf(1, spec.timeBetween.value.roundToInt())
                        suspendFor(due)
                    }
                }
                if (failedWhenDue) {
                    failedWhenDue = false
                } else if (!isFailed) {
                    failWith(spec.rule, declared = true)
                }
                // repaired by hand at the instant it failed, by a failure listener: the time to the next failure
                // counts from then
                if (!isFailed) continue
                if (myFailure?.declared != true) {
                    // failed by a call of fail(), before this failure came due or in place of it at the instant it
                    // began: this failure waits for that repair and its clock starts again
                    suspendFor(repaired)
                    continue
                }
                // the repair time, or a repair by hand before it ends, whichever comes first; a hand repair
                // ends this failure early, and the time to the next one counts from it
                suspendForOrTimeout(repaired, spec.repairTime.value)
                // repair only the failure this entity started, not one the model made after a hand repair
                if (isFailed && myFailure?.declared == true) repair()
            }
        }
    }

    // ---- behavior --------------------------------------------------------------------------------

    private var myBehaviorBlock: (suspend KSLProcessBuilder.() -> Unit)? = null
    private var myBehaviorIsCyclic: Boolean = false
    private var myBehaviorStoppedAction: (() -> Unit)? = null

    /** The entity of the running behavior; null when none is running. */
    private var myBehaviorEntity: ResourceBehavior? = null

    /** The behavior's activation, while it has not yet started. */
    private var myBehaviorStart: KSLEvent<KSLProcess>? = null

    /** An `AFTER_CYCLE` stop is pending. */
    private var myStopRequested: Boolean = false

    /** True if a behavior, of either form, has been declared for this resource. */
    val hasBehavior: Boolean
        get() = myBehaviorBlock != null

    /**
     *  True while the behavior is running in this replication, from its activation until it ends: by returning
     *  from its block, by a stop, by the model terminating its process (as clearing the queue it waits in does),
     *  or at the end of the replication.
     */
    val isBehaviorActive: Boolean
        get() = myBehaviorEntity != null

    /** True when a stop after the current cycle has been asked for and the cycle has not yet finished. */
    val isBehaviorStopRequested: Boolean
        get() = myStopRequested

    /**
     *  The process of the running behavior, so that another process can interrupt its delays (for
     *  example with `interruptDelay`); null when no behavior is running.
     */
    var behaviorProcess: KSLProcess? = null
        private set

    /**
     *  Whether each replication starts the behavior. With false, a replication starts without it and
     *  [startBehavior] starts it when the model wants it, so an experiment can compare runs with and
     *  without the behavior. Set while the model is not running.
     *
     *  It is one of the model's controls only for a resource with a behavior, declared before the
     *  model's controls are first read; a resource without one does not list it.
     */
    @set:KSLControl(controlType = ControlType.BOOLEAN)
    var behaviorActiveAtStart: Boolean = true
        set(value) {
            require(model.isNotRunning) { "The behavior of $name cannot be switched on or off at start while the model is running." }
            field = value
        }

    /** A resource without a behavior has nothing for [behaviorActiveAtStart] to switch, so it is not a control. */
    override fun defaultExcludedControlNames(): Set<String> =
        if (hasBehavior) super.defaultExcludedControlNames()
        else super.defaultExcludedControlNames() + ::behaviorActiveAtStart.name

    /**
     *  The number of consecutive cycles of a cyclic behavior that may end at the simulated time they
     *  began; one more is taken to be looping without waiting, and throws. Large enough for a
     *  legitimate burst, such as a cycle that takes each of many messages already waiting. Set while
     *  the model is not running; at least 1.
     */
    var maxCyclesPerInstant: Int = 10_000
        set(value) {
            require(value > 0) { "The number of cycles per instant of $name must be > 0; it was $value." }
            require(model.isNotRunning) { "The number of cycles per instant of $name cannot be changed while the model is running." }
            field = value
        }

    /**
     *  Called by [cyclicBehavior] and [behavior] before a behavior is stored. A subclass that cannot
     *  have a behavior in some state (an agent resource with a statechart) throws here.
     */
    protected open fun checkBehaviorAllowed() {}

    /**
     *  Declares a behavior that the library repeats: [block] is one cycle, run again as soon as it
     *  ends, from time 0 of every replication. This is the usual form of a resource's own behavior,
     *  such as a break taken every so often:
     *  <pre>
     *  doctor.cyclicBehavior {
     *      delay(120.0)
     *      doctor.goOffShift()
     *      delay(15.0)
     *      doctor.goOnShift()
     *  }
     *  </pre>
     *  Anything a process can do is allowed in the block, including seizing this resource, which then
     *  waits in its queue like any customer. The behavior belongs to the resource as a whole, however
     *  large its capacity: one process, not one per unit.
     *
     *  The block is one lambda run by a new entity in each replication, so its local variables start
     *  fresh every time while anything it captures from outside carries over between replications;
     *  state that must reset belongs inside the block.
     *
     *  More than [maxCyclesPerInstant] cycles in a row that end at the time they began throw, naming
     *  this resource: such a cycle never waits. Use [stopBehavior] and [startBehavior] to switch the
     *  behavior off and on during a replication, [onBehaviorStopped] to put back what a stopped
     *  behavior changed, and [behaviorActiveAtStart] to decide whether each replication starts it.
     *  A resource has at most one behavior, of either form, declared while the model is not running.
     *
     *  @param block one cycle of the behavior
     */
    fun cyclicBehavior(block: suspend KSLProcessBuilder.() -> Unit) {
        declareBehavior(block, cyclic = true)
    }

    /**
     *  Declares a behavior whose [block] runs once from time 0 of every replication, for a set-up that
     *  is not a cycle, such as a delayed start. Otherwise as [cyclicBehavior], which is the usual form;
     *  a block that loops by itself cannot be stopped after its current cycle or guarded against a
     *  cycle that never waits.
     *
     *  @param block the behavior
     */
    fun behavior(block: suspend KSLProcessBuilder.() -> Unit) {
        declareBehavior(block, cyclic = false)
    }

    private fun declareBehavior(block: suspend KSLProcessBuilder.() -> Unit, cyclic: Boolean) {
        check(model.isNotRunning) { "The behavior of $name must be declared before the model runs." }
        check(myBehaviorBlock == null) { "$name already has a behavior; a resource has at most one, of either form." }
        checkBehaviorAllowed()
        myBehaviorBlock = block
        myBehaviorIsCyclic = cyclic
    }

    /**
     *  Declares what to do after the behavior is stopped by [stopBehavior], to put back what it changed,
     *  for example bringing the resource back on shift when a break was cut short. It does not suspend.
     *  It does not run when the behavior ends on its own or when a replication ends. A second call
     *  replaces the first. Declared while the model is not running.
     *
     *  @param action what to do after a stop
     */
    fun onBehaviorStopped(action: () -> Unit) {
        check(model.isNotRunning) { "The stop action of $name must be declared before the model runs." }
        myBehaviorStoppedAction = action
    }

    /**
     *  Starts the behavior again at the current time, after the calling event, as a fresh process with
     *  fresh local state. It must have been declared, the model must be running, and it must not be
     *  running already; [isBehaviorActive] is the check.
     */
    fun startBehavior() {
        check(hasBehavior) { "$name has no behavior to start." }
        check(model.isRunning) { "The behavior of $name can be started only while the model runs." }
        check(!isBehaviorActive) { "The behavior of $name is already running; check isBehaviorActive first." }
        launchBehavior()
    }

    /**
     *  Stops the running behavior. With [BehaviorStop.AFTER_CYCLE] (the default) a cyclic behavior
     *  finishes its current cycle and starts no other; a second such call before the cycle ends
     *  throws, and [isBehaviorStopRequested] is the check. With [BehaviorStop.NOW], and for a behavior
     *  declared with [behavior], it ends at once: its process is terminated, releasing anything it
     *  holds and running its `finally` blocks, and a stop not yet begun cancels its start. A stop
     *  `NOW` after a pending `AFTER_CYCLE` is allowed. Then the action given to [onBehaviorStopped]
     *  runs.
     *
     *  A behavior cannot stop itself `NOW`, since it is the process running: it returns from its block,
     *  or asks to stop after the current cycle.
     *
     *  @param at how the stop takes effect
     */
    fun stopBehavior(at: BehaviorStop = BehaviorStop.AFTER_CYCLE) {
        check(isBehaviorActive) { "The behavior of $name is not running; check isBehaviorActive first." }
        if (at == BehaviorStop.AFTER_CYCLE && myBehaviorIsCyclic) {
            check(!myStopRequested) { "A stop of the behavior of $name after its current cycle is already pending; check isBehaviorStopRequested first." }
            myStopRequested = true
            return
        }
        val entity = myBehaviorEntity!!
        val start = myBehaviorStart
        if (start != null && start.isScheduled) {
            // not yet begun: nothing of it runs
            start.cancel = true
            endBehavior(stopped = true)
            return
        }
        check(!entity.run.isRunning) {
            "The behavior of $name cannot stop itself at once; it returns from its block, or stops after its current cycle."
        }
        // ended first, so the terminated process's own hook finds nothing more to end; the stop action runs once
        // its process has been terminated
        clearBehaviorState()
        entity.terminateProcess()
        myBehaviorStoppedAction?.invoke()
    }

    private fun launchBehavior() {
        val entity = ResourceBehavior()
        myBehaviorEntity = entity
        myStopRequested = false
        behaviorProcess = entity.run
        myBehaviorStart = activate(entity.run)
    }

    private fun endBehavior(stopped: Boolean) {
        myBehaviorEntity = null
        behaviorProcess = null
        myBehaviorStart = null
        myStopRequested = false
        if (stopped) myBehaviorStoppedAction?.invoke()
    }

    private fun clearBehaviorState() {
        myBehaviorEntity = null
        behaviorProcess = null
        myBehaviorStart = null
        myStopRequested = false
    }

    /** The entity that runs a resource's behavior; a new one for every start. */
    // The entity that runs the behavior is the resource's own, as the failures' entity is. What it does, seizing
    // this resource during a break for instance, is part of what a replay shows, so its events are captured.
    @ksl.animation.KSLInternalEntity
    private inner class ResourceBehavior : Entity() {
        @ksl.animation.KSLAnimatedProcess(name = "behavior")
        val run: KSLProcess = process("behavior", isDefaultProcess = true) {
            myBehaviorStart = null
            val block = myBehaviorBlock!!
            if (!myBehaviorIsCyclic) {
                block()
            } else {
                var sameInstant = 0
                while (!myStopRequested) {
                    val cycleStart = time
                    block()
                    if (time == cycleStart) {
                        sameInstant++
                        check(sameInstant <= maxCyclesPerInstant) {
                            "The cyclic behavior of ${this@Resource.name} ended $sameInstant cycles in a row at time $cycleStart without " +
                                    "the time advancing: its cycle never waits. Make each cycle wait (a delay, a seize, a " +
                                    "wait), or raise maxCyclesPerInstant if such a burst is intended."
                        }
                    } else {
                        sameInstant = 0
                    }
                }
            }
            endBehavior(stopped = myStopRequested)
        }

        /**
         *  The behavior's process was terminated by the model, not by `stopBehavior`: the behavior has ended, and
         *  it may be started again. Its stop action runs only if a stop after the cycle had been asked for. At the
         *  end of a replication nothing runs; the state is cleared.
         */
        override fun handleTerminatedProcess(terminatedProcess: KSLProcess) {
            if (myBehaviorEntity !== this) return
            if (isReplicationEnding) clearBehaviorState() else endBehavior(stopped = myStopRequested)
        }
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
        entity: Entity,
        amountNeeded: Int = 1,
        queue: RequestQ,
        allocationName: String? = null
    ): Allocation = change {
        require(canAllocate(amountNeeded)) { "$this The amount requested, $amountNeeded cannot currently be allocated" }
        val allocation = Allocation(entity, this, amountNeeded, queue, allocationName)
        if (!entityAllocations.contains(entity)) {
            entityAllocations[entity] = mutableListOf()
        }
        entityAllocations[entity]?.add(allocation)
        numBusy = numBusy + amountNeeded
        numTimesSeized++
        mySeizeCounter.increment()
        //need to put this allocation in Entity also
        entity.allocate(allocation)
        allocationNotification(allocation)
        allocation
    }

    /** Causes the resource to deallocate the amount associated with the allocation
     *  The primary use of this function is to support the release() function
     *  of the process view.
     *
     * @param allocation the allocation to be deallocated
     */
    open fun deallocate(allocation: Allocation): Unit = change {
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
        // give back to the resource; only the units freed reach the queue or a pending capacity decrease, not
        // those that stay with a borrower or go back to a lender. Most resources have no loans to settle.
        myReleaseInProgress = true
        val settlement = if (myLoans.isEmpty()) null else settleLoans(allocation)
        val freed = allocation.amount - (settlement?.notFreed ?: 0)
        numBusy = numBusy - freed
        numTimesReleased++
        // need to also deallocate from the entity
        allocation.myEntity.deallocate(allocation)
        // deallocate the allocation, so it can't be used again
        allocation.deallocate()
        deallocationNotification(allocation)
        // A use failure due at this release is known before the release completes a capacity change, whose
        // increase would otherwise offer the freed units first: while it is due, nothing is offered, and it is
        // applied at the end of the release.
        countUse()
        // deallocation completed need to check for pending capacity change; not once the run has ended, when
        // the releases come from terminating the replication's entities and nothing more can be scheduled.
        // The capacity and its changes start afresh at the next replication.
        if (isPendingCapacityChange && !isRunEnded) {
            if (freed > 0 && capacityChangeRule == CapacityChangeRule.IGNORE) {
                handleIgnoreRuleRelease(freed)
            } else if (freed > 0 && capacityChangeRule == CapacityChangeRule.WAIT) {
                // for WAIT rule handle releases only until full change gets allocated and scheduled
                if (myCurrentChangeNotice!!.changeEvent == null) {
                    // once it is not null, the full change has been completed and is being processed
                    handleWaitRuleRelease(freed)
                }
            }
        }
        myReleaseInProgress = false
        val failing = myFailureDueAtRelease
        myFailureDueAtRelease = false
        // the units of ending loans pass on, counting the units this release freed, unless the resource fails
        // at this release; then they go back to their lenders, and the failure takes them with the rest in the
        // same step, so a restored trigger fires only for units still back once the failure is applied
        if (failing) {
            settlingRestores {
                if (settlement != null) returnLoans(settlement, passOn = false)
                applyDueFailure()
            }
        } else if (settlement != null) {
            returnLoans(settlement, passOn = true)
        }
    }

    /** The allocations whose units came back while a repair or a failing release settles; null when none is settling. */
    private var myRestoredWhileSettling: MutableList<Allocation>? = null

    /** True when the restored claim of [allocation] is held back until this resource has settled. */
    internal fun holdRestoredClaim(allocation: Allocation): Boolean {
        val held = myRestoredWhileSettling ?: return false
        held.add(allocation)
        return true
    }

    /**
     *  Runs the [steps] of a repair or a failing release, in which units come back and may be taken again, and
     *  then claims the restored trigger of each allocation whose units are still back. Nested, the outer call
     *  makes the claims.
     */
    private inline fun settlingRestores(steps: () -> Unit) {
        if (myRestoredWhileSettling != null) {
            steps()
            return
        }
        val held = mutableListOf<Allocation>()
        myRestoredWhileSettling = held
        try {
            steps()
        } finally {
            myRestoredWhileSettling = null
        }
        for (allocation in held.distinct()) allocation.claimRestoredIfWhole()
    }

    /** What settling a release's loans leaves to do: the units going back, by lender, and those of them down. */
    private class Settlement(val notFreed: Int, val returning: MutableMap<Allocation, Int>, val down: Map<Allocation, Int>)

    /**
     *  Passes the units of ending loans on to the waiting preemptors that outrank their lenders, when [passOn],
     *  and gives the rest back to the lenders. A failed or inactive resource lends nothing, as at a preemption's
     *  arrival, and once the run has ended nothing is resumed.
     */
    private fun returnLoans(settlement: Settlement, passOn: Boolean) {
        val returning = settlement.returning
        if (passOn && returning.isNotEmpty() && !isFailed && !isInactive && !isRunEnded) passLoansOn(returning)
        // a resource failed now, under RESUME or RESTART, has all its units down, however the failure began: one
        // whatever the settlement recorded of the releasing allocation's own units, those coming back are down too
        val failureRule = myFailure?.rule
        val allDown = isFailed && failureRule != null && failureRule != InterruptRule.FINISH
        for ((lender, back) in returning) {
            if (back > 0) {
                val down = if (allDown) back else minOf(back, settlement.down[lender] ?: 0)
                lender.loanComesBack(back, down, failureRule)
                if (!lender.isDeallocated) returnNotification(lender, back)
            }
        }
    }

    /**
     *  Settles the loans of [allocation] as it is released, and returns the units that are not freed: those
     *  it lent out, which stay with their borrowers, and those it borrowed that go back to their lenders. The
     *  units going back are passed on or returned by [returnLoans] once the release has freed its other units.
     */
    private fun settleLoans(allocation: Allocation): Settlement {
        // Units this allocation lent out stay with their borrowers. Of the units it
        // still holds, those it borrowed go back to their lenders first, newest loan first, and stay busy for
        // them. Borrowed units it lent on are not here to give back: its borrowers now owe them to the
        // original lenders, and the rest of what it lent goes to the queue at the borrowers' release.
        val lentOutLoans = myLoans.filter { it.lender === allocation }
        val lentOut = lentOutLoans.sumOf { it.amount }
        var held = allocation.amount - lentOut
        var returned = 0
        // units of this allocation down in a failure stay down when they go back to a lender
        var downLeft = allocation.missingByFailure
        val stillOwed = ArrayDeque<Pair<Allocation, Int>>()
        // the units going back, by lender, and how many of them are down in a failure
        val returning = LinkedHashMap<Allocation, Int>()
        val down = HashMap<Allocation, Int>()
        for (loan in myLoans.filter { it.borrower === allocation }.asReversed()) {
            myLoans.remove(loan)
            val lender = loan.lender ?: continue
            val back = minOf(loan.amount, held)
            if (back > 0) {
                val d = minOf(back, downLeft)
                downLeft -= d
                returning[lender] = (returning[lender] ?: 0) + back
                down[lender] = (down[lender] ?: 0) + d
                returned += back
                held -= back
            }
            if (loan.amount > back) stillOwed.addLast(lender to loan.amount - back)
        }
        for (loan in lentOutLoans) {
            myLoans.remove(loan)
            var left = loan.amount
            while (left > 0 && stillOwed.isNotEmpty()) {
                val (lender, owed) = stillOwed.removeFirst()
                val piece = minOf(owed, left)
                myLoans.add(Loan(lender, loan.borrower, piece))
                left -= piece
                if (owed > piece) stillOwed.addFirst(lender to owed - piece)
            }
            if (left > 0) myLoans.add(Loan(null, loan.borrower, left))
        }
        return Settlement(returned + lentOut, returning, down)
    }

    /**
     *  Gives units [returning] to their lenders from loans that are ending to the waiting preemptors that
     *  outrank those lenders, most urgent first and, among equals, the one waiting longest, when it can then take
     *  all it needs. A lender is held already, so a holder never resumes ahead of a waiting preemptor that outranks
     *  it and can take what it needs, whatever that preemptor's rule. A preemptor under `RESUME` or `RESTART` plans its preemption as it would on arrival,
     *  counting the returning units as their lenders': the returning units it takes pass to it as loans from the
     *  same lenders, who stay held and are not told; any other units it takes are taken from their holders as at
     *  any preemption. A `FINISH` preemptor's reservation still collecting takes returning units of lenders it
     *  outranks when they complete it, with the free units; it takes nothing in service. Each is
     *  resumed with its allocation made. The units not taken are left in [returning], to go back.
     */
    private fun passLoansOn(returning: MutableMap<Allocation, Int>) {
        while (returning.values.any { it > 0 }) {
            val outranksALender = { req: ProcessModel.Entity.Request ->
                returning.any { (lender, k) -> k > 0 && lender.myEntity.priority > req.entity.priority }
            }
            val waiting = myCapacityChangeQSet.asSequence()
                .flatMap { q -> q.asSequence() }
                .filterIsInstance<ProcessModel.Entity.ResourceRequest>()
                .filter { it.myResource === this && it.passedAllocation == null }
                .filter { it.waitingPreemptRule.let { rule -> rule != null && rule != InterruptRule.FINISH } }
                .filter { !it.isPassedOver && it.promises.isEmpty() }
                .filter(outranksALender)
            val reserving = myReservations.asSequence()
                .filter { isCollecting(it) && it.request.resource === this && outranksALender(it.request) }
                .map { it.request }
                .filterIsInstance<ProcessModel.Entity.ResourceRequest>()
                .filter { it.passedAllocation == null }
            val candidates = (waiting + reserving)
                .sortedWith(compareBy<ProcessModel.Entity.ResourceRequest> { it.entity.priority }.thenBy { it.timeEnteredQueue })
            var chosen: ProcessModel.Entity.ResourceRequest? = null
            var plan: PreemptionPlan? = null
            for (request in candidates) {
                plan = if (request.reservation != null) {
                    reservationPlan(request, returning)
                } else {
                    planPreemption(request.entity, request.amountRequested, returning)
                        // units this release freed go to what is as urgent as it first, as at any re-planning,
                        // including a more urgent waiting FINISH preemptor, which is not a candidate here
                        ?.takeIf { it.free == 0 || (mayTakeFreeUnits(request) && isFirstClaimantOfFreeUnits(request, returning)) }
                }
                if (plan != null) {
                    chosen = request
                    break
                }
            }
            val request = chosen ?: return
            if (request.reservation != null) {
                // its promised free units become part of the allocation; the reservation is done
                val promised = request.promises[this] ?: 0
                if (promised > 0) request.withdrawPromise(this, promised)
                dropReservation(request)
            }
            giveToWaitingPreemptor(request, plan!!, returning, ProcessModel.RELEASE_PRIORITY,
                ResumeSource.REQUEST_Q_RESOURCE_RELEASE, "loan_passed_on_resource=$name, request_id=${request.id}")
        }
    }

    /**
     *  True when nothing ahead of [request] claims the free units at a loan's end: no reservation still collecting,
     *  and no waiting preemptor of any rule that could plan now, counting the [returning] units as their lenders',
     *  is more urgent, or as urgent and waiting longer. A waiting preemptor that cannot plan holds no one back, as
     *  when the waiting preemptors plan at a release.
     */
    private fun isFirstClaimantOfFreeUnits(
        request: ProcessModel.Entity.ResourceRequest,
        returning: Map<Allocation, Int>
    ): Boolean {
        val priority = request.entity.priority
        fun ahead(other: ProcessModel.Entity.Request): Boolean =
            other !== request && (other.entity.priority < priority ||
                    (other.entity.priority == priority && other.timeEnteredQueue < request.timeEnteredQueue))
        if (myReservations.any { isCollecting(it) && ahead(it.request) }) return false
        return myWaitingPreemptors.none { w ->
            w.passedAllocation == null && w.resource === this && (w.queue as? RequestQ)?.contains(w) == true &&
                    !w.isPassedOver && w.promises.isEmpty() && ahead(w) &&
                    planPreemption(w.entity, w.amountRequested, returning,
                        takesAtOnce = w.waitingPreemptRule != InterruptRule.FINISH) != null
        }
    }

    /**
     *  For a `FINISH` preemptor's reservation still collecting: the plan that completes it from the free units
     *  promised to it, the units free now when it is first in line for them (a release that ends a loan may free
     *  others too), and the [returning]
     *  units of lenders its entity outranks, taken from the least urgent lender first and, among equals, the most
     *  recently allocated; null when those are too few, and then the returning units go back. Nothing in service
     *  is taken.
     */
    private fun reservationPlan(
        request: ProcessModel.Entity.ResourceRequest,
        returning: Map<Allocation, Int>
    ): PreemptionPlan? {
        val reservation = request.reservation ?: return null
        val promised = request.promises[this] ?: 0
        // free units go to the most urgent claimant first, as when units are released: a reservation counts them
        // only when no more urgent reservation, and no more urgent waiting preemptor of any rule, waits for them
        val freeNow = if (isFirstClaimantOfFreeUnits(request, returning)) minOf(numAvailableUnits, reservation.stillNeeded) else 0
        val free = promised + freeNow
        var short = reservation.stillNeeded - freeNow
        val lenders = returning.filter { (lender, k) -> k > 0 && lender.myEntity.priority > request.entity.priority }
            .keys.sortedWith(
                compareByDescending<Allocation> { it.myEntity.priority }.thenByDescending { it.timeAllocated }.thenByDescending { it.id }
            )
        val takes = mutableListOf<Pair<Allocation, Int>>()
        for (lender in lenders) {
            if (short == 0) break
            val k = minOf(short, returning.getValue(lender))
            takes.add(lender to k)
            short -= k
        }
        return if (short > 0) null else PreemptionPlan(free, takes)
    }

    /**
     *  Allocates to the waiting preemptor [request] under [plan], and resumes it with the allocation made. Of
     *  each victim's units, those in [returning] pass to it from an ending loan, and the rest are taken as at
     *  any preemption.
     */
    private fun giveToWaitingPreemptor(
        request: ProcessModel.Entity.ResourceRequest,
        plan: PreemptionPlan,
        returning: MutableMap<Allocation, Int>,
        priority: Int,
        source: ResumeSource,
        detail: String
    ): Unit = change {
        val rule = request.waitingPreemptRule!!
        val queue = request.queue as RequestQ
        val allocation = Allocation(request.entity, this, request.amountRequested, queue, null)
        entityAllocations.getOrPut(request.entity) { mutableListOf() }.add(allocation)
        numBusy = numBusy + plan.free
        numTimesSeized++
        mySeizeCounter.increment()
        request.entity.allocate(allocation)
        for ((victim, k) in plan.takes) {
            myLoans.add(Loan(victim, allocation, k))
            val passed = minOf(k, returning[victim] ?: 0)
            if (passed > 0) {
                returning[victim] = returning.getValue(victim) - passed
                // a FINISH preemptor takes over a lender already held: its hold goes on as it was
                if (rule != InterruptRule.FINISH) victim.loanPassesOn(rule)
            }
            if (k > passed) {
                check(rule != InterruptRule.FINISH) { "Under FINISH nothing is taken from a holder of $name in service." }
                victim.unitsGoMissing(byFailure = 0, byLoan = k - passed, rule = rule)
                // units passed on from an ending loan were lent already, and are still not the victim's
                loanNotification(victim, allocation, k - passed)
            }
        }
        // a FINISH preemption was counted when it reserved
        if (plan.takes.isNotEmpty() && rule != InterruptRule.FINISH) numTimesPreempted++
        allocationNotification(allocation)
        request.passedAllocation = allocation
        queue.resumeWoken(request, priority, source, detail)
    }

    /** The preemptors waiting for this resource, in the order they began to wait; some may have left since. */
    private val myWaitingPreemptors: MutableSet<ProcessModel.Entity.ResourceRequest> = LinkedHashSet()

    /** Records that [request], a `preempt` under [rule] that found nothing to take, waits for this resource. */
    internal fun waitAsPreemptor(request: ProcessModel.Entity.ResourceRequest, rule: InterruptRule) {
        request.waitingPreemptRule = rule
        myWaitingPreemptors.add(request)
    }

    /**
     *  Units have come free or come back: the waiting preemptors plan again, most urgent first and, among
     *  equals, the one waiting longest, each as it would on arrival. One that can now take enough is given
     *  its units, or, under `FINISH`, reserves them; then the next plans. So a holder never resumes ahead
     *  of a waiting preemptor that outranks it, and free units go to a waiting preemptor ahead of the
     *  requests it outranks. It takes no free unit ahead of a request or a reservation as urgent as it is
     *  (see [mayTakeFreeUnits]): it stays waiting, and the units go to the reservations and the queue. The
     *  offer of freed units calls this first, before each request it serves, and once more when it has served
     *  any reservation or request.
     */
    private fun replanWaitingPreemptors(priority: Int, source: ResumeSource, detail: String?) {
        if (myWaitingPreemptors.isEmpty() || isFailed || isInactive || isRunEnded) return
        // one that has its allocation, has left its queue, or has moved its request to another resource
        myWaitingPreemptors.removeAll { r ->
            r.passedAllocation != null || r.resource !== this || (r.queue as? RequestQ)?.contains(r) != true
        }
        val candidates = myWaitingPreemptors
            .filter { !it.isPassedOver && it.promises.isEmpty() }
            .sortedWith(compareBy<ProcessModel.Entity.ResourceRequest> { it.entity.priority }.thenBy { it.timeEnteredQueue })
        for (request in candidates) {
            val amount = request.amountRequested
            val plan = if (amount <= numAvailableUnits) PreemptionPlan(amount, emptyList())
                else planPreemption(request.entity, amount, takesAtOnce = request.waitingPreemptRule != InterruptRule.FINISH) ?: continue
            if (plan.free > 0 && !mayTakeFreeUnits(request)) continue
            // taking only free units, it is served as a seize is, so its queue's selection rule must choose it, as
            // it must on arrival
            if (plan.takes.isEmpty() && !chosenByItsRule(request)) continue
            myWaitingPreemptors.remove(request)
            val why = "preemptor_replanned_resource=$name, request_id=${request.id}, ${detail ?: ""}"
            if (request.waitingPreemptRule == InterruptRule.FINISH && plan.takes.isNotEmpty()) {
                reserve(request, plan.free)
            } else {
                giveToWaitingPreemptor(request, plan, mutableMapOf(), priority, source, why)
            }
        }
    }

    /**
     *  Whether the waiting preemptor [request] may take free units now. It may not while something as urgent as it
     *  is comes before it: a reservation still collecting units whose entity is equally or more urgent, or the
     *  request the free units would go to next, when that request is more urgent, or is equally urgent and ahead of
     *  it (in its own queue by the queue's discipline, in another queue by the time it began to wait). That request
     *  is the one the offer would serve next: under the default notification rule, the one that entered its queue
     *  first; under a rule of the model's own, the next of the first queue, in the rule's order, that has one the
     *  units can serve. A request of lower priority does not hold it back. The offer asks again before each request
     *  it serves, so the preemptor is compared with each in turn.
     */
    private fun mayTakeFreeUnits(request: ProcessModel.Entity.ResourceRequest): Boolean {
        val priority = request.entity.priority
        if (myReservations.any { isCollecting(it) && it.request.entity.priority <= priority }) return false
        if (requestQNotificationRule === DefaultRequestQueueNotificationRule) {
            val (nextQueue, next) = nextByDefaultRule(myCapacityChangeQSet) ?: return true
            return next === request || !comesBefore(next, nextQueue, request)
        }
        // a rule of the model's own offers queue by queue, in its order: the next request is that of the first queue
        // with one the units can serve, as the offer finds it
        val itr = requestQNotificationRule.ruleIterator(myCapacityChangeQSet)
        while (itr.hasNext()) {
            val queue = itr.next()
            val next = queue.nextRequestServableBy(this) ?: continue
            return next === request || !comesBefore(next, queue, request)
        }
        return true
    }

    /**
     *  For the invariant checks: a waiting preemptor passed over by a woken request it should have gone ahead of,
     *  or null. A request is passed over when another request of its queue, no more urgent and behind it by the
     *  queue's discipline, holds a promise of units that, with the units of the holders the preemptor outranks,
     *  would have served it. Only the plain case is judged: the resource working and on shift, the preemptor not
     *  under FINISH and with nothing promised, no reservation as urgent as it still collecting units, and the other
     *  request not served through a reservation.
     */
    internal fun waitingPreemptorPassedOver(): String? {
        if (isFailed || isInactive || isOffShift || isRunEnded) return null
        for (preemptor in myWaitingPreemptors) {
            if (preemptor.waitingPreemptRule == InterruptRule.FINISH || preemptor.resumePending || preemptor.isReserved ||
                preemptor.promises.isNotEmpty() || preemptor.passedAllocation != null || preemptor.resource !== this) continue
            val queue = preemptor.queue as? RequestQ ?: continue
            if (!queue.contains(preemptor)) continue
            val priority = preemptor.entity.priority
            val needed = preemptor.amountRequested
            if (needed > reachableCapacity) continue
            if (myReservations.any { isCollecting(it) && it.request.entity.priority <= priority }) continue
            val lendable = allocations().filter { it.myEntity.priority > priority }.sumOf { it.amount - it.missingByLoan }
            for (other in queue) {
                if (other === preemptor) continue
                val promised = other.promises[this] ?: 0
                // a reservation's units go to it by the FINISH preemptors' own rule, ahead of a waiting preemptor
                if (promised == 0 || other.reservation != null || other.entity.priority < priority ||
                    !queue.precedes(preemptor, other)) continue
                val free = numAvailableUnits + promised
                // taking only free units it is served as a seize is, and a selection rule may choose the other
                if (free >= needed && queue.requestSelectionRule != null) continue
                if (free + lendable >= needed) {
                    return "the waiting preemptor request ${preemptor.id} of entity ${preemptor.entity.id} (priority $priority, " +
                            "$needed units) was passed over by request ${other.id} of entity ${other.entity.id} (priority " +
                            "${other.entity.priority}), behind it in ${queue.name}, which holds $promised promised units"
                }
            }
        }
        return null
    }

    /** True when the request's queue has no request selection rule, or its rule chooses the request for this resource. */
    private fun chosenByItsRule(request: ProcessModel.Entity.ResourceRequest): Boolean {
        val queue = request.queue as? RequestQ ?: return true
        val rule = queue.requestSelectionRule ?: return true
        return rule.selectRequests(this, queue).any { it === request }
    }

    /**
     *  Under the default notification rule, the request this resource would serve next from [queues]: of the
     *  next request of each queue, the one that entered its queue first, with the queue it is in. Null when no
     *  queue has a request the resource can serve. The offer of freed units and a waiting preemptor's check
     *  both use it, so the two agree.
     */
    private fun nextByDefaultRule(queues: Set<RequestQ>): Pair<RequestQ, ProcessModel.Entity.Request>? {
        var bestQueue: RequestQ? = null
        var best: ProcessModel.Entity.Request? = null
        for (queue in queues) {
            val candidate = queue.nextRequestServableBy(this) ?: continue
            if (best == null || candidate.timeEnteredQueue < best.timeEnteredQueue) {
                bestQueue = queue
                best = candidate
            }
        }
        return if (best == null) null else Pair(bestQueue!!, best)
    }

    /**
     *  Whether [next], the request next in [queue], comes before the waiting [preemptor]: it is more urgent, or
     *  it is equally urgent and ahead of it (in the preemptor's own queue by the queue's discipline, in another
     *  queue by the time it began to wait). In its own queue the two positions are compared: the request the
     *  free units would serve next is not always ahead of the preemptor, which they skip when it needs more
     *  units than are free.
     */
    private fun comesBefore(
        next: ProcessModel.Entity.Request,
        queue: RequestQ,
        preemptor: ProcessModel.Entity.ResourceRequest
    ): Boolean {
        val nextPriority = next.entity.priority
        val priority = preemptor.entity.priority
        if (nextPriority != priority) return nextPriority < priority
        return if (queue === preemptor.queue) queue.precedes(next, preemptor)
        else next.timeEnteredQueue < preemptor.timeEnteredQueue
    }

    protected open inner class ResourceState(aName: String, stateStatistics: Boolean = false) :
        State(name = aName, useStatistic = stateStatistics) {
        // need to have state implementations for: failed?
    }

    protected inner class InactiveState(aName: String, stateStatistics: Boolean = false) :
        ResourceState(aName, stateStatistics) {
        override fun onEnter() {
            if (!myQuietAvailabilityHooks) resourceBecameInactive()
        }

        override fun onExit() {
            if (!myQuietAvailabilityHooks) resourceBecameActive()
        }
    }

    protected var myNoticeCount : Int = 0
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
    val isPendingCapacityChange : Boolean
        get() = myCurrentChangeNotice != null

    /** The units the pending capacity decrease still waits for; 0 with no change pending. */
    internal val pendingChangeAmountNeeded: Int
        get() = myCurrentChangeNotice?.amountNeeded ?: 0

    /**
     * When the pending capacity change ends. `NaN` when no change is pending
     * (see [isPendingCapacityChange]). Positive infinity when the pending change lasts until another
     * change supersedes it (a notice with an infinite duration, as shift changes use), or when its end
     * is not yet known because, under the WAIT rule, it is still waiting for busy units to be released.
     *
     * Under the IGNORE rule, a decrease that arrives while another is pending must not end before it.
     * Check against this time before requesting such a change.
     */
    val pendingCapacityChangeEndTime: Double
        get() {
            val notice = myCurrentChangeNotice ?: return Double.NaN
            return notice.changeEvent?.time ?: Double.POSITIVE_INFINITY
        }

    /**
     * Schedules the end of a capacity change. A change with an infinite duration lasts until another
     * change supersedes it, so it has no end event: one scheduled at infinity would never be placed on
     * the calendar, and anything waiting for it would wait forever.
     *
     * @return true when an end was scheduled, false when the change is open-ended
     */
    private fun scheduleEndOfChange(notice: CapacityChangeNotice): Boolean {
        if (notice.duration.isInfinite()) {
            notice.changeEvent = null
            return false
        }
        notice.changeEvent =
            schedule(this::capacityChangeAction, notice.duration, message = notice, priority = notice.priority)
        logger.trace { "$time > Resource: $name, scheduled end of capacity change for ${notice.changeEvent?.time}" }
        return true
    }

    /** Cancels the end event of a change, if it has one on the calendar. */
    private fun cancelEndOfChange(notice: CapacityChangeNotice?) {
        val event = notice?.changeEvent ?: return
        // an end that falls after the run's end was never placed on the calendar, so there is nothing to cancel
        if (event.isScheduled) {
            event.cancel = true
        }
    }

    /**
     * Under the IGNORE rule, makes the next waiting change current and applies it, once the current
     * change has finished. A decrease that waited is applied now, as if it had just arrived; before
     * R1.8 it became current without its decrease ever being applied.
     */
    private fun startNextWaitingChangeIgnoreRule() {
        applyNextWaitingChangeIgnoreRule()
        // a reservation a decrease just gave up has its units offered now the change is applied
        offerUnitsOfReturnedReservations(KSLEvent.DEFAULT_PRIORITY)
    }

    private fun applyNextWaitingChangeIgnoreRule() {
        myCurrentChangeNotice = null
        while (myWaitingChangeNotices.isNotEmpty()) {
            val next = myWaitingChangeNotices.removeFirst()
            logger.trace { "$time > Resource: $name, notice $next was waiting but is now being processed" }
            if (next.capacity > capacity) {
                processPositiveCapacityChange(next)
            } else if (next.capacity < capacity) {
                negativeChangeNoPendingChangeIgnoreRule(next)
                if (myCurrentChangeNotice != null) {
                    return
                }
            }
        }
    }

    /**
     * Under the WAIT rule, processes the waiting changes in order once the current change has
     * finished, until one of them is pending (waiting for units, or applied with an end to come) or
     * none are left. A change with an infinite duration is finished as soon as it is applied, so the
     * next one starts at once rather than waiting for an end that never comes.
     */
    private fun startNextWaitingChangeWaitRule() {
        applyNextWaitingChangeWaitRule()
        // a reservation a decrease just gave up has its units offered now the change is applied
        offerUnitsOfReturnedReservations(KSLEvent.DEFAULT_PRIORITY)
    }

    private fun applyNextWaitingChangeWaitRule() {
        myCurrentChangeNotice = null
        while (myWaitingChangeNotices.isNotEmpty()) {
            val next = myWaitingChangeNotices.removeFirst()
            myCurrentChangeNotice = next
            logger.trace { "$time > Resource: $name, notice $next was waiting but is now being processed" }
            if (next.capacity > capacity) {
                processPositiveCapacityChange(next)
                if (scheduleEndOfChange(next)) {
                    return
                }
                myCurrentChangeNotice = null
            } else if (next.capacity < capacity) {
                negativeChangeAfterPendingChangeWaitRule(next)
                if (myCurrentChangeNotice != null) {
                    return
                }
            } else {
                myCurrentChangeNotice = null
            }
        }
    }

    override fun afterReplication() {
        // terminates the behavior's entity if it is still suspended, releasing anything it holds
        super.afterReplication()
        myWaitingChangeNotices.clear()
        myCurrentChangeNotice = null
        // the stop action does not run at the end of a replication
        clearBehaviorState()
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
                cancelEndOfChange(myCurrentChangeNotice)
                for (notice in myWaitingChangeNotices) {
                    cancelEndOfChange(notice)
                }
                myWaitingChangeNotices.clear()
                myCurrentChangeNotice = null
            }
        }
    }

    protected fun handleIgnoreRuleDeallocation(allocation: Allocation) {
        handleIgnoreRuleRelease(allocation.amountReleased)
    }

    /** Gives [amountReleased] freed units to the pending change under the IGNORE rule. */
    private fun handleIgnoreRuleRelease(amountReleased: Int) {
        // a capacity change is pending and needs units that were deallocated
        val amountNeeded = myCurrentChangeNotice!!.amountNeeded
        // number busy went down and number available went up by amount released
        val amountToDecrease = minOf(amountReleased, amountNeeded)
        // give the units to the pending change
        myCurrentChangeNotice!!.amountNeeded = myCurrentChangeNotice!!.amountNeeded - amountToDecrease
        logger.trace { "$time > Resource: $name, provided $amountToDecrease units to notice $myCurrentChangeNotice" }
        if (myCurrentChangeNotice!!.amountNeeded == 0) {
            // the capacity change has been filled
            logger.trace { "$time > Resource: $name, notice $myCurrentChangeNotice has been completed" }
            // if the rule was IGNORE, it was previously scheduled, no need to schedule
            // check if there are more changes
            // start the next waiting change, if any, applying its change now
            startNextWaitingChangeIgnoreRule()
        }
    }

    /**
     *  Gives [amount] units withdrawn from a promise to the pending capacity change, if any, as a release gives
     *  its units: the request they were promised to will not take them. Under IGNORE a decrease that arrived
     *  while they were promised counted them as busy, and waits for them; under WAIT a decrease waits for
     *  units to become idle. Without this the change would wait for the next release, and a later change,
     *  such as a shift's end, would wait behind it. Nothing is done once the run has ended.
     */
    internal fun unitsWithdrawnFromPromise(amount: Int): Unit = change {
        if (amount <= 0 || !isPendingCapacityChange || isRunEnded) return@change
        if (capacityChangeRule == CapacityChangeRule.IGNORE) {
            handleIgnoreRuleRelease(amount)
        } else if (capacityChangeRule == CapacityChangeRule.WAIT && myCurrentChangeNotice!!.changeEvent == null) {
            handleWaitRuleRelease(amount)
        }
    }

    protected fun handleWaitRuleDeallocation(allocation: Allocation) {
        handleWaitRuleRelease(allocation.amountReleased)
    }

    /** Gives [amountReleased] freed units to the pending change under the WAIT rule. */
    private fun handleWaitRuleRelease(amountReleased: Int) {
        // a capacity change is pending and needs units that were deallocated
        val amountNeeded = myCurrentChangeNotice!!.amountNeeded
        // capacity needs to go down by amount needed
        // number busy went down and number available went up by amount released
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
            // it does not schedule its duration until it gets all the needed change. A change that lasts
            // until superseded is finished now, so the changes waiting behind it can start.
            if (!scheduleEndOfChange(myCurrentChangeNotice!!)) {
                startNextWaitingChangeWaitRule()
            }
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
    fun changeCapacity(notice: CapacityChangeNotice): Unit = change {
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
        offerUnitsOfReturnedReservations(notice.priority)
    }

    protected fun handleIncomingChangeNoticeIgnoreRule(notice: CapacityChangeNotice) {
        if (isPendingCapacityChange) {
            // capacity change is pending for the IGNORE rule
            if (notice.capacity >= capacity) {
                // notice the above >= with = meaning keep the current capacity
                // positive change with a negative change pending, cancel the pending negative change
                cancelEndOfChange(myCurrentChangeNotice)
                // make there be no pending change after the positive change
                myCurrentChangeNotice = null
                // assume that the positive change cancels all waiting negative changes
                for (waiting in myWaitingChangeNotices) {
                    cancelEndOfChange(waiting)
                }
                myWaitingChangeNotices.clear()
                // process the positive change now since there is no pending change anymore
                if (notice.capacity > capacity) {
                    processPositiveCapacityChange(notice)
                } else if (!isRunEnded) {
                    // the capacity stays as it is, so no increase follows to offer anything; a waiting preemptor that
                    // the cleared cuts held back may take from lower-priority holders now, as under WAIT
                    offerFreedUnits(notice.priority, ResumeSource.REQUEST_Q_CAPACITY_INCREASE, "decrease_withdrawn_resource=$name")
                }
            } else if (notice.capacity < capacity) {
                // negative change with change pending
                // a change is scheduled, find end time of newly arriving negative change notice
                val endTime = time + notice.duration
                val pendingChangeEndTime = pendingCapacityChangeEndTime
                // do not permit an incoming negative change to "interrupt" an in-progress negative change,
                // after is okay. A pending change that lasts until superseded has no end to compare with.
                if (pendingChangeEndTime.isFinite()) {
                    require(endTime > pendingChangeEndTime) {
                        "$time > Resource: $name, the incoming capacity decrease $notice would end at $endTime, " +
                                "before the pending change $myCurrentChangeNotice ends at $pendingChangeEndTime. " +
                                "Check pendingCapacityChangeEndTime before requesting a decrease."
                    }
                }
                // always schedule the end of the incoming change immediately
                // capture the time of the change in the event time
                scheduleEndOfChange(notice)
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
        if (available <= 0) {
            logger.trace { "$time > Resource: $name, had 0 units available after the positive capacity change. No reason to notify entities." }
            // none is free, but a waiting preemptor may now take what it needs from lower-priority holders
            if (!isRunEnded && !myFailureDueAtRelease) replanWaitingPreemptors(notice.priority, ResumeSource.REQUEST_Q_CAPACITY_INCREASE, "capacity_increase_resource=$name")
        } else if (myCapacityChangeQSet.isEmpty()) {
            // no queues are currently associated with this resource, no reason to notify
            logger.trace { "$time > Resource: $name, The resource is not associated with any capacity change queues. No queues were notified of the positive capacity change." }
        } else {
            // this causes the newly available capacity to be allocated to any waiting requests
            // which may resume the entity processes at the current simulated time
            notifyWaitingRequestsOfCapacityIncrease(available, notice.priority)
        }
        // a subclass whose waiting requests the queues above do not reach offers its units here; not while a
        // failure due at the release in progress is still to be applied
        if (!isRunEnded && !myFailureDueAtRelease) afterUnitsRestored()
    }

    protected fun notifyWaitingRequestsOfCapacityIncrease(available: Int, priority: Int) {
        offerFreedUnits(priority, ResumeSource.REQUEST_Q_CAPACITY_INCREASE, "capacity_increase_resource=$name, available=$available")
    }

    /**
     * Offers this resource's available units to the requests waiting for them, wherever they wait: in the
     * queues registered on this resource, and in [alsoQueue], the queue of an allocation just released,
     * when that queue is not registered. A waiting request may name this resource or a pool it belongs to.
     *
     * Every way units become free calls this, whether released directly, released through a pool, or
     * added by a capacity increase, so a unit freed one way is offered to the requests that asked for it
     * another. Before R1.8 each way woke only the queue it came through, and a request waiting by another
     * route could wait for the rest of the replication beside an idle unit.
     *
     * Under the default notification rule, the request that entered its queue first, across all of these
     * queues, is offered the units first; each queue keeps its own discipline. A rule of the model's own
     * offers them queue by queue, in the order it gives.
     */
    internal fun offerFreedUnits(
        priority: Int,
        source: ResumeSource,
        detail: String?,
        alsoQueue: RequestQ? = null
    ): Unit = change {
        // once the run has ended, units restored by the termination of the entities, as a shift's end in a
        // finally block, are offered to no one: nothing can be resumed
        if (isInactive || isRunEnded || myFailureDueAtRelease) return@change
        // a waiting preemptor plans again first, even when no unit is free: units that came back to their
        // holders, at a repair or a return to shift, can be taken from them
        replanWaitingPreemptors(priority, source, detail)
        val free = numAvailableUnits
        if (free > 0) {
            // a preemption waiting for units its victims used takes them ahead of the queue
            serveReservations(priority, source, detail)
            if (numAvailableUnits > 0) offerToQueues(priority, source, detail, alsoQueue)
        }
        // a preemptor that gave way to a reservation or a more urgent request takes what it can now that they are
        // served: the units left, and those of the holders it outranks
        if (numAvailableUnits != free && myWaitingPreemptors.isNotEmpty()) replanWaitingPreemptors(priority, source, detail)
    }

    /**
     *  Offers the available units to the requests in the registered queues and [alsoQueue], by the notification
     *  rule. A waiting preemptor plans again before each request is served, so it is compared with each in turn.
     */
    private fun offerToQueues(priority: Int, source: ResumeSource, detail: String?, alsoQueue: RequestQ?) {
        val queues: Set<RequestQ> =
            if (alsoQueue == null || alsoQueue in myCapacityChangeQSet) myCapacityChangeQSet
            else LinkedHashSet(myCapacityChangeQSet).apply { add(alsoQueue) }
        if (queues.isEmpty()) return
        if (requestQNotificationRule === DefaultRequestQueueNotificationRule) {
            while (numAvailableUnits > 0) {
                if (!replanBeforeNextRequest(priority, source, detail)) break
                val (bestQueue, best) = nextByDefaultRule(queues) ?: break
                if (!bestQueue.wake(best, this, priority, source, detail)) break
                ResourceInvariants.checkAfterWake(this)
            }
        } else {
            val itr = requestQNotificationRule.ruleIterator(queues)
            while (itr.hasNext() && numAvailableUnits > 0) {
                val q = itr.next()
                while (numAvailableUnits > 0) {
                    if (!replanBeforeNextRequest(priority, source, detail)) break
                    val candidate = q.nextRequestServableBy(this) ?: break
                    if (!q.wake(candidate, this, priority, source, detail)) break
                    ResourceInvariants.checkAfterWake(this)
                }
            }
        }
    }

    /** Lets the waiting preemptors plan before the next request is served; false when no unit is left to offer. */
    private fun replanBeforeNextRequest(priority: Int, source: ResumeSource, detail: String?): Boolean {
        if (myWaitingPreemptors.isNotEmpty()) replanWaitingPreemptors(priority, source, detail)
        return numAvailableUnits > 0
    }

    protected fun negativeChangeNoPendingChangeIgnoreRule(notice: CapacityChangeNotice) {
        logger.trace { "$time > Resource: $name, change notice $notice is decreasing the capacity from $capacity to ${notice.capacity}." }
        // notice.capacity < capacity, need to decrease the capacity
        val decrease = capacity - notice.capacity
        returnReservationsBeyond(notice.capacity)
        if (numIdleUnits >= decrease) {
            // there are enough available units to handle the change w/o using busy resources
            capacity = capacity - decrease
            logger.trace { "$time > Resource: $name, enough units idle to immediately reduce capacity by $decrease." }
        } else {
            // not enough available, ignore rule causes entire change at the current time
            // some of the change will need to be supplied when the busy resources are released
            logger.trace { "$time > Resource: $name, not enough units idle to reduce capacity by $decrease." }
            // the idle units go now with the rest of the change; it waits only for the busy and promised units
            notice.amountNeeded = decrease - numIdleUnits
            // always schedule the end of the incoming change immediately, unless it waited behind another
            // change and its end was scheduled when it arrived
            if (notice.changeEvent == null) {
                scheduleEndOfChange(notice)
            }
            // make the notice the current notice for processing
            myCurrentChangeNotice = notice
            logger.trace { "$time > Resource: $name, notice $notice is now being processed" }
            capacity = capacity - decrease
            // ignore takes away all needed, immediately, by decreasing the capacity by the full amount of the change
            // capacity was decreased but change notice still needs those busy units to be released
            logger.trace { "$time > Resource: $name, reduced capacity to $capacity because of notice $notice" }
        }
    }

    protected fun handleIncomingChangeNoticeWaitRule(notice: CapacityChangeNotice) {
        val pending = myCurrentChangeNotice
        if (pending != null && pending.duration.isInfinite() && pending.amountNeeded > 0 &&
            notice.capacity >= capacity
        ) {
            // An open-ended decrease still waiting for busy units, and a change that undoes it, such as
            // going back on shift before the unit was released: the decrease never takes effect.
            logger.trace { "$time > Resource: $name, notice $notice withdraws the pending decrease $pending" }
            myCurrentChangeNotice = null
            if (myWaitingChangeNotices.isNotEmpty()) {
                myWaitingChangeNotices.add(notice)
                startNextWaitingChangeWaitRule()
                return
            }
            if (notice.capacity == capacity) {
                // The capacity never fell, so no increase follows to offer anything. A waiting preemptor kept back
                // by the decrease may take from lower-priority holders now, as it may at any return of units.
                if (!isRunEnded) offerFreedUnits(notice.priority, ResumeSource.REQUEST_Q_CAPACITY_INCREASE, "decrease_withdrawn_resource=$name")
                return
            }
        }
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
        returnReservationsBeyond(notice.capacity)
        // units promised to a reservation still collecting its units are as good as idle to a decrease
        if (numIdleUnits < decrease) reclaimFromReservationsCollecting(decrease - numIdleUnits.coerceAtLeast(0))
        if (numIdleUnits >= decrease) {
            // there are enough available units to handle the change w/o using busy resources
            capacity = capacity - decrease
            // removed idle units, but remaining units are (busy or idle) or all units have been removed
            // may still be busy, idle, or if capacity is zero should be inactive
            // all units needed were allocated, no resulting pending notice
            // change stays until next change arrives
            logger.trace { "$time > Resource: $name, enough units idle to immediately reduce capacity by $decrease." }
        } else {
            // not enough idle: the idle units go now, so none can be allocated against the decrease, and the
            // rest waits for busy units to be released. Its duration starts once it has all its units.
            logger.trace { "$time > Resource: $name, not enough units idle to reduce capacity by $decrease." }
            val idle = numIdleUnits.coerceAtLeast(0)
            capacity = capacity - idle
            notice.amountNeeded = decrease - idle
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
    protected fun capacityChangeAction(event: KSLEvent<CapacityChangeNotice>): Unit = change {
        val endingChangeNotice = event.message!!
        logger.trace { "$time > Resource: $name, notice $endingChangeNotice ended its duration" }
        if (capacityChangeRule == CapacityChangeRule.IGNORE) {
            // if ending notice is same as current notice, we can stop the change associated with the current notice
            // if it was not the current, then the ending change notice was previously completed, nothing to do
            if (myCurrentChangeNotice == endingChangeNotice) {
                startNextWaitingChangeIgnoreRule()
            }
        } else if (capacityChangeRule == CapacityChangeRule.WAIT) {
            // finished processing the current change notice; start the next one, if any
            startNextWaitingChangeWaitRule()
        }
    }

    protected fun negativeChangeAfterPendingChangeWaitRule(notice: CapacityChangeNotice) {
        logger.trace { "$time > Resource: $name, change notice $notice is decreasing the capacity from $capacity to ${notice.capacity}." }
        // notice.capacity < capacity, need to decrease the capacity
        val decrease = capacity - notice.capacity
        returnReservationsBeyond(notice.capacity)
        // units promised to a reservation still collecting its units are as good as idle to a decrease
        if (numIdleUnits < decrease) reclaimFromReservationsCollecting(decrease - numIdleUnits.coerceAtLeast(0))
        if (numIdleUnits >= decrease) {
            // there are enough available units to handle the change w/o using busy resources
            capacity = capacity - decrease
            // removed idle units, but remaining units are (busy or idle) or all units have been removed
            // may still be busy, idle, or if capacity is zero should be inactive
            // all units needed were taken by the process change
            logger.trace { "$time > Resource: $name, enough units idle to immediately reduce capacity by $decrease." }
            // schedule the end of the change, wait rule has full change for duration; a change that lasts
            // until superseded is finished once applied
            if (!scheduleEndOfChange(notice)) {
                myCurrentChangeNotice = null
            }
        } else {
            // not enough idle: the idle units go now, and the rest waits for busy units to be released. Its
            // duration starts once it has all its units.
            logger.trace { "$time > Resource: $name, not enough units idle to reduce capacity by $decrease." }
            val idle = numIdleUnits.coerceAtLeast(0)
            capacity = capacity - idle
            notice.amountNeeded = decrease - idle
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
            notifyInactive(myCapacityChangeQSet.first())
            return
        }
        val itr = requestQNotificationRule.ruleIterator(myCapacityChangeQSet)
        while (itr.hasNext()) {
            notifyInactive(itr.next())
        }
    }

    /**
     *  Tells the entities of the requests in [queue] that want this resource, directly or through a pool it
     *  belongs to, that it became inactive. A queue may hold requests for other resources too, and those are
     *  not told. The requests are copied first, because an entity may move its request out of the queue.
     */
    private fun notifyInactive(queue: RequestQ) {
        for (request in queue.toList()) {
            if (queue.wants(request, this)) {
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

        val id : Int = ++myNoticeCount

        /** True for a capacity schedule's item, as against a change the model asked for (a cut, an off-shift). */
        internal var isScheduleItem: Boolean = false

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
            val notice = CapacityChangeNotice(item.capacity, item.duration, item.priority).also { it.isScheduleItem = true }
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
