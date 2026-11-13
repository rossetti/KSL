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
import java.util.concurrent.atomic.AtomicInteger

/**
 *  Allocations are created while a model runs, and KSL runs models concurrently, so a plain
 *  counter could hand the same id to allocations in different models. Atomic for the same reason
 *  as the QObject counter, and with the same caveat: the ids are unique but still shared across
 *  models rather than belonging to one.
 */
private val allocationCounter = AtomicInteger(0)

interface AllocationIfc {

    val id: Int

    /**
     *  The time that the allocation was allocated to its resource
     */
    val timeAllocated: Double

    /**
     *  The time that the allocation was deallocated
     */
    val timeDeallocated: Double

    /**
     * The total elapsed time since allocation if not yet deallocated.  If
     * deallocated, the total time between de-allocation and allocation
     */
    val totalTimeAllocated: Double

    /**
     *  An optional name for the allocation
     */
    val name: String?

    /**
     *  The amount of the allocation representing the units allocated of the resource
     */
    val amount: Int

    /**
     *  True if the allocation is currently allocated to a resource
     */
    val isAllocated: Boolean

    /**
     *  True if no units are allocated
     */
    val isDeallocated: Boolean

    /**
     *  The amount of the allocation that has been released
     */
    val amountReleased: Int

    /**
     *  The queue that held the request associated with the allocation
     */
    val queue: QueueCIfc<ProcessModel.Entity.Request>

    /**
     *  The resource associated with the allocation
     */
    val resource: ResourceCIfc

    /**
     *  How many times the holder's work was interrupted: the stretches of time during which units of
     *  this allocation were taken away (down in a failure, or lent to a more urgent request) under the
     *  rule `RESUME` or `RESTART`, whether or not the holder was in a delay at the time.
     */
    val timesInterrupted: Int

    /** The total length of those stretches. */
    val timeInterrupted: Double

    /** True while units of this allocation are missing, to a failure or a preemption. */
    val hasMissingUnits: Boolean
}

/**
 *  An allocation represents a distinct usage of a resource by an entity with an amount allocated.
 *  Entities can have multiple allocations for the same resource. An allocation is in response
 *  to separate requests for units. Multiple requests by the same entity for units of the
 *  resource result in multiple allocations (when filled).  An allocation is not created until
 *  the requested amount is available.
 *
 *  @param myEntity the entity associated with the allocation
 *  @param myResource the resource associated with the allocation
 *  @param theAmount the amount allocated of the resource to the entity
 *  @param myQueue the queue that the entity had to wait in when requesting the allocation
 *  @param allocationName the name of the allocation
 *  processed by the resource.
 */
class Allocation internal constructor(
    internal val myEntity: ProcessModel.Entity,
    internal val myResource: Resource,
    theAmount: Int = 1,
    internal val myQueue: RequestQ,
    allocationName: String? = null
) : AllocationIfc {
    init {
        require(theAmount >= 1) { "The initial allocation must be >= 1 " }
    }

    override val queue: QueueCIfc<ProcessModel.Entity.Request>
        get() = myQueue

    override val resource: ResourceCIfc
        get() = myResource

    /**
     *  The pool that the request was made against, when this allocation was created to satisfy a
     *  pool-level request. Null when the request named an individual resource.
     *
     *  A waiting request names what was *wanted*, and a request from a seize against a pool names
     *  the pool, not whichever member the selection rule later picked. So on release the queue has
     *  to be processed on behalf of the pool, or no waiter can ever be matched against it. The
     *  release of an allocation in ProcessModel is where this is consumed.
     */
    internal var originatingPool: AbstractResourcePool<*>? = null

    override val id = allocationCounter.incrementAndGet()

    override val timeAllocated: Double = myResource.time

    override var timeDeallocated: Double = Double.NaN
        private set

    override val totalTimeAllocated: Double
        get() {
            return if (timeDeallocated.isNaN()){
                myResource.time - timeAllocated
            } else {
                timeDeallocated - timeAllocated
            }
        }

    override var name: String? = allocationName
        private set

    override var amount: Int = theAmount
        private set(value) {
            require(value >= 0) { "The amount allocated must be >= 0" }
            field = value
        }

    override val isAllocated: Boolean
        get() = amount > 0

    override val isDeallocated: Boolean
        get() = !isAllocated

    override var amountReleased = 0
        private set

    internal fun deallocate(){
        // an interruption still open at the release ends with it; nothing is rescheduled, and units
        // coming back later have no holder to return to
        if (missingUnits > 0) {
            timeInterrupted += myResource.time - myInterruptedSince
            myInterruptedSince = Double.NaN
            missingByFailure = 0
            missingByLoan = 0
            freezeRestart = false
        }
        amountReleased = amount
        amount = 0
        timeDeallocated = myResource.time
    }

    /** Units of this allocation down in a failure. */
    internal var missingByFailure: Int = 0
        private set

    /** Units of this allocation lent to a more urgent request. */
    internal var missingByLoan: Int = 0
        private set

    /** Units of this allocation taken away, for either reason. */
    internal val missingUnits: Int
        get() = missingByFailure + missingByLoan

    /** The action that took the units asked for a restart. */
    internal var freezeRestart: Boolean = false
        private set

    override var timesInterrupted: Int = 0
        private set

    override var timeInterrupted: Double = 0.0
        private set

    private var myInterruptedSince: Double = Double.NaN

    override val hasMissingUnits: Boolean
        get() = missingUnits > 0

    /**
     *  Units of this allocation go missing under [rule], which is `RESUME` or `RESTART` (under `FINISH`
     *  nothing is taken from a holder). When the first go missing, an interruption starts and the holder's
     *  delay, if it is in one that depends on this allocation, gains a reason to freeze.
     */
    internal fun unitsGoMissing(byFailure: Int, byLoan: Int, rule: InterruptRule) {
        require(rule != InterruptRule.FINISH) { "Under FINISH nothing is taken from the holder of allocation $id." }
        check(isAllocated) { "Allocation $id has been released; no units of it can go missing." }
        require(byFailure >= 0 && byLoan >= 0) { "The units going missing from allocation $id must be >= 0." }
        val before = missingUnits
        missingByFailure += byFailure
        missingByLoan += byLoan
        check(missingUnits <= amount) { "Allocation $id would have $missingUnits units missing of $amount." }
        if (missingUnits == 0) return
        freezeRestart = freezeRestart || rule == InterruptRule.RESTART
        if (before == 0) {
            timesInterrupted++
            myInterruptedSince = myResource.time
        }
        if (before == 0 || rule == InterruptRule.RESTART) {
            myEntity.addFreezeReason(this, freezeRestart, if (byFailure > 0) InterruptCause.FAILURE else InterruptCause.PREEMPTION)
        }
        // then the waits on this allocation's interrupted trigger, with the cause of this taking
        if (before == 0) {
            claimTriggers(onRestore = false, value = if (byFailure > 0) InterruptCause.FAILURE else InterruptCause.PREEMPTION)
        }
    }

    /**
     *  Units of this allocation come back. When the last return, the interruption ends and the reason it
     *  gave the holder's delay is removed, which reschedules the delay if it was the last reason.
     */
    internal fun unitsComeBack(byFailure: Int, byLoan: Int) {
        require(byFailure >= 0 && byLoan >= 0) { "The units coming back to allocation $id must be >= 0." }
        // the interruption of a released allocation ended at its release
        if (isDeallocated) return
        val before = missingUnits
        missingByFailure -= byFailure
        missingByLoan -= byLoan
        check(missingByFailure >= 0 && missingByLoan >= 0) { "Allocation $id had fewer units missing than came back." }
        if (before > 0 && missingUnits == 0) {
            timeInterrupted += myResource.time - myInterruptedSince
            myInterruptedSince = Double.NaN
            freezeRestart = false
            myEntity.removeFreezeReason(this, null)
            claimTriggers(onRestore = true, value = Unit)
        }
    }

    /** The waits on this allocation's triggers, in the order they registered. */
    internal val myTriggerRegistrations: MutableList<AllocationRegistration> = mutableListOf()

    /** Why units of this allocation are missing now: a failure if any are down in one, else a preemption. */
    internal val currentCause: InterruptCause
        get() = if (missingByFailure > 0) InterruptCause.FAILURE else InterruptCause.PREEMPTION

    /** Registers a wait on one of this allocation's triggers; a released allocation refuses it. */
    internal fun registerTrigger(trigger: WaitTrigger<*>, group: WaitGroup, onRestore: Boolean): AllocationRegistration {
        require(isAllocated) {
            "Allocation $id of ${myResource.name} has been released; a wait on its ${if (onRestore) "restored" else "interrupted"} " +
                    "trigger would never end. Use the triggers of an allocation that is held."
        }
        val registration = AllocationRegistration(trigger, group, this, onRestore)
        myTriggerRegistrations.add(registration)
        return registration
    }

    /**
     *  Claims the waits on this allocation's interrupted trigger ([onRestore] false) or restored trigger
     *  ([onRestore] true), in the order they registered. A copy is walked, since a claim releases the
     *  wait's other registrations, which may be in this list.
     */
    private fun claimTriggers(onRestore: Boolean, value: Any?) {
        for (registration in myTriggerRegistrations.toList()) {
            if (registration.onRestore != onRestore || registration.isReleased) continue
            myTriggerRegistrations.remove(registration)
            registration.group.claim(registration, value, ResumeSource.ALLOCATION, ProcessModel.RESUME_PRIORITY)
        }
    }

    /**
     *  A trigger for `waitForFirst` that fires when units of this allocation go missing, to a failure or a
     *  preemption, with the cause as its value; at once if units are missing already. Any entity may wait
     *  on it, not only the holder. A wait on it ends by its other triggers or its time limit if the
     *  allocation is released first, and a released allocation's trigger cannot be waited on.
     */
    fun interruptedTrigger(): WaitTrigger<InterruptCause> = InterruptedTrigger(this)

    /**
     *  A trigger for `waitForFirst` that fires when all the missing units of this allocation have come
     *  back; at once if none are missing. Any entity may wait on it, not only the holder. A wait on it
     *  ends by its other triggers or its time limit if the allocation is released first, and a released
     *  allocation's trigger cannot be waited on.
     */
    fun restoredTrigger(): WaitTrigger<Unit> = RestoredTrigger(this)

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("Entity ")
        sb.append(myEntity.id)
        sb.append(" holds ")
        sb.append(amount)
        sb.append(" units of resource ")
        sb.append(myResource.name)
        return sb.toString()
    }
}