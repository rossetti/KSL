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
            updateState()
            myCapacity.value = field.toDouble()
            instantUtilTW.value = instantaneousUtil
        }

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

    /** The failed state, keeps track of when no units are available because the resource is failed.
     *  It takes precedence over the other three until the resource is repaired.
     */
    protected val myFailedState: ResourceState = ResourceState("${this.name}_Failed")
    override val failedState: StateAccessorIfc
        get() {
            if (isFailed) {
                myState.exit(time)
                myState.enter(time)
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

    /** True from a [fail] until the [repair] that follows it. */
    override val isFailed: Boolean
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
        myState.exit(time)
        myBusyState.resetStateCollection()
        myInactiveState.resetStateCollection()
        myIdleState.resetStateCollection()
        myFailedState.resetStateCollection()
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
     *  that the initial capacity is greater than 0 first.
     */
    fun goOffShift() {
        val shift = myShift ?: ShiftControl(this).also { myShift = it }
        shift.goOffShift()
        myFailureClock?.update()
    }

    /**
     *  Brings this resource back on shift, restoring the [initialCapacity] it had when this
     *  replication's first [goOffShift] was made. The requests waiting for it are offered the units as
     *  for any capacity increase. Nothing happens when the resource is on shift.
     */
    fun goOnShift() {
        val shift = myShift ?: return
        if (!shift.isOffShift) return
        shift.goOnShift()
        myFailureClock?.update()
        afterUnitsRestored()
    }

    /**
     *  Called when this resource's units come back by other than a release: at the end of a [goOnShift]
     *  that put it back on shift, and of a [repair]. A subclass whose waiting requests are not reached by
     *  a capacity increase offers its units here.
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
     *  any release, capacity increase or repair of this resource ahead of the queue until [stillNeeded] is 0.
     */
    internal class Reservation(val request: ProcessModel.Entity.Request, var stillNeeded: Int)

    /** What a preemption would take: the free units, and the units of each victim. */
    internal class PreemptionPlan(val free: Int, val takes: List<Pair<Allocation, Int>>)

    private val myLoans: MutableList<Loan> = mutableListOf()

    private val myReservations: MutableList<Reservation> = mutableListOf()

    /** The loans now recorded on this resource, for checks. */
    internal val loans: List<Loan>
        get() = myLoans

    /**
     *  The number of preemptions of this resource in the replication: each `preempt` that took units from
     *  at least one holder, or reserved them from holders that finish their work.
     */
    var numTimesPreempted: Int = 0
        protected set

    /**
     *  What a preemption by [entity] for [amountNeeded] units would take: the free units, then units of the
     *  holders whose entity's priority is lower (a larger number) than [entity]'s, lowest priority first and,
     *  among equals, the most recently allocated first, only as many as are still needed. Null when the free
     *  units are already enough or when the free and lendable units together are too few.
     */
    internal fun planPreemption(entity: ProcessModel.Entity, amountNeeded: Int): PreemptionPlan? {
        val free = numAvailableUnits
        var short = amountNeeded - free
        if (short <= 0) return null
        val candidates = allocations()
            .filter { it.myEntity.priority > entity.priority && it.amount - it.missingByLoan > 0 }
            .sortedWith(compareByDescending<Allocation> { it.myEntity.priority }.thenByDescending { it.timeAllocated })
        val takes = mutableListOf<Pair<Allocation, Int>>()
        for (victim in candidates) {
            if (short == 0) break
            val k = minOf(short, victim.amount - victim.missingByLoan)
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
    ): Allocation {
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
        }
        numTimesPreempted++
        allocationNotification(allocation)
        return allocation
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
        numTimesPreempted++
    }

    /** Drops the reservation of a request leaving its queue; its promises are withdrawn with the request's. */
    internal fun dropReservation(request: ProcessModel.Entity.Request) {
        myReservations.removeAll { it.request === request }
        request.reservation = null
    }

    /**
     *  Gives the available units to the waiting reservations, oldest first, and resumes each one that has
     *  all it needs.
     */
    private fun serveReservations(priority: Int, source: ResumeSource, detail: String?) {
        while (numAvailableUnits > 0 && myReservations.isNotEmpty()) {
            val reservation = myReservations.first()
            val k = minOf(reservation.stillNeeded, numAvailableUnits)
            reservation.request.promise(this, k)
            reservation.stillNeeded -= k
            if (reservation.stillNeeded > 0) break
            myReservations.removeAt(0)
            val request = reservation.request
            request.reservation = null
            val queue = request.queue as RequestQ
            queue.resumeWoken(request, priority, source, "reservation_completed_resource=$name, request_id=${request.id}, $detail")
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
        myState = myFailedState
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
    fun repair() {
        check(isFailed) { "$name is not failed; check isFailed before calling repair()." }
        for (a in allocations()) {
            if (a.missingByFailure > 0) a.unitsComeBack(byFailure = a.missingByFailure, byLoan = 0)
        }
        myState = stateFromUnits()
        offerFreedUnits(ProcessModel.RELEASE_PRIORITY, ResumeSource.REQUEST_Q_CAPACITY_INCREASE, "repaired_resource=$name")
        failureNotification()
        myFailureClock?.update()
        val failures = myFailuresEntity
        if (failures != null && failures.repaired.isSuspended) failures.repaired.resume()
        afterUnitsRestored()
    }

    private fun failureNotification() {
        for (listener in allocationListeners) {
            listener.failureStateChanged(this)
        }
    }

    /** The failures declared by [failures]; null when none are. */
    private var myFailureSpec: FailureSpec? = null

    private class FailureSpec(
        val timeBetween: RVariableIfc,
        val repairTime: RVariableIfc,
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
     *  repair time ends that failure early. The waiting is done by an entity
     *  of the resource's own, separate from a behavior it may have.
     *
     *  On `BUSY_TIME`, the time counts only while at least one unit is busy and the resource is on shift
     *  and not failed, so a failure comes due at the exact instant that much busy time has passed. On
     *  `USES`, [timeBetween] is rounded to a whole number of releases (at least 1), and the resource fails at
     *  the release that completes it.
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
        myFailureSpec = FailureSpec(timeBetween, repairTime, basis, rule)
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
        if (myUsesLeft <= 0) return
        myUsesLeft--
        if (myUsesLeft == 0) failureCameDue()
    }

    private fun failureCameDue() {
        val due = myFailuresEntity?.due ?: return
        if (due.isSuspended) due.resume()
    }

    private fun failureClockFired(event: KSLEvent<Nothing>) {
        myFailureClock?.fired()
    }

    /**
     *  The busy time left before the next failure on `BUSY_TIME`. It runs while at least one unit is busy
     *  and the resource is on shift and not failed: when it starts, it schedules the failure for the time
     *  left; when it stops, it banks what is left and cancels the event.
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
            event?.cancel = true
            event = null
            armed = false
            running = false
        }

        fun update() {
            if (!armed) return
            val shouldRun = numBusy > 0 && !isFailed && !isOffShift
            if (shouldRun && !running) {
                running = true
                since = time
                event = schedule(::failureClockFired, left)
            } else if (!shouldRun && running) {
                running = false
                left = maxOf(0.0, left - (time - since))
                event?.cancel = true
                event = null
            }
        }

        fun fired() {
            event = null
            armed = false
            running = false
            failureCameDue()
        }
    }

    /** The entity that waits out a resource's declared failures; a new one for every replication. */
    private inner class ResourceFailures : Entity() {
        val due: Suspension = Suspension("${this@Resource.name}:failure due")
        val repaired: Suspension = Suspension("${this@Resource.name}:repaired")

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
                if (isFailed) {
                    // failed by a call of fail(): this failure waits for that repair and its clock starts again
                    suspendFor(repaired)
                    continue
                }
                fail(spec.rule)
                delay(spec.repairTime.value)
                // a repair by hand during the repair time has already ended this failure
                if (isFailed) repair()
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

    /** True while the behavior is running in this replication, from its activation until it ends. */
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
     */
    @set:KSLControl(controlType = ControlType.BOOLEAN)
    var behaviorActiveAtStart: Boolean = true
        set(value) {
            require(model.isNotRunning) { "The behavior of $name cannot be switched on or off at start while the model is running." }
            field = value
        }

    /**
     *  The number of consecutive cycles of a cyclic behavior that may end at the simulated time they
     *  began before the behavior is taken to be looping without waiting, and throws. Large enough for a
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
     *  A cycle that ends at the time it began, [maxCyclesPerInstant] times in a row, throws, naming
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
        entity.terminateProcess()
        endBehavior(stopped = true)
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
    private inner class ResourceBehavior : Entity() {
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
    ): Allocation {
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
        return allocation
    }

    /** Causes the resource to deallocate the amount associated with the allocation
     *  The primary use of this function is to support the release() function
     *  of the process view.
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
        // give back to the resource. Units this allocation lent out stay with their borrowers. Of the units it
        // still holds, those it borrowed go back to their lenders first, newest loan first, and stay busy for
        // them. Borrowed units it lent on are not here to give back: its borrowers now owe them to the
        // original lenders, and the rest of what it lent goes to the queue at the borrowers' release.
        val lentOutLoans = myLoans.filter { it.lender === allocation }
        val lentOut = lentOutLoans.sumOf { it.amount }
        var held = allocation.amount - lentOut
        var returned = 0
        val stillOwed = ArrayDeque<Pair<Allocation, Int>>()
        for (loan in myLoans.filter { it.borrower === allocation }.asReversed()) {
            myLoans.remove(loan)
            val lender = loan.lender ?: continue
            val back = minOf(loan.amount, held)
            if (back > 0) {
                lender.unitsComeBack(byFailure = 0, byLoan = back)
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
        // only the units freed reach the queue or a pending capacity decrease, not those back with a lender
        val freed = allocation.amount - returned - lentOut
        numBusy = numBusy - freed
        numTimesReleased++
        // need to also deallocate from the entity
        allocation.myEntity.deallocate(allocation)
        // deallocate the allocation, so it can't be used again
        allocation.deallocate()
        deallocationNotification(allocation)
        // deallocation completed need to check for pending capacity change
        if (isPendingCapacityChange) {
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
        countUse()
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
    ) {
        if (isInactive || numAvailableUnits <= 0) return
        // a preemption waiting for units its victims used takes them ahead of the queue
        serveReservations(priority, source, detail)
        if (numAvailableUnits <= 0) return
        val queues: Set<RequestQ> =
            if (alsoQueue == null || alsoQueue in myCapacityChangeQSet) myCapacityChangeQSet
            else LinkedHashSet(myCapacityChangeQSet).apply { add(alsoQueue) }
        if (queues.isEmpty()) return
        if (requestQNotificationRule === DefaultRequestQueueNotificationRule) {
            while (numAvailableUnits > 0) {
                var bestQueue: RequestQ? = null
                var best: ProcessModel.Entity.Request? = null
                for (q in queues) {
                    val candidate = q.nextRequestServableBy(this) ?: continue
                    if (best == null || candidate.timeEnteredQueue < best.timeEnteredQueue) {
                        bestQueue = q
                        best = candidate
                    }
                }
                if (best == null || !bestQueue!!.wake(best, this, priority, source, detail)) break
            }
        } else {
            val itr = requestQNotificationRule.ruleIterator(queues)
            while (itr.hasNext() && numAvailableUnits > 0) {
                val q = itr.next()
                while (numAvailableUnits > 0) {
                    val candidate = q.nextRequestServableBy(this) ?: break
                    if (!q.wake(candidate, this, priority, source, detail)) break
                }
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
            // always schedule the end of the incoming change immediately, unless it waited behind another
            // change and its end was scheduled when it arrived
            if (notice.changeEvent == null) {
                scheduleEndOfChange(notice)
            }
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
        if (numAvailableUnits >= decrease) {
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
