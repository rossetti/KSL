/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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

import ksl.animation.AnimationEvent

/**
 *  The trigger of a seize: units of a resource, or of a resource pool, or a movable resource from a
 *  movable resource pool. It fires at once when the untimed `seize` would take the units without
 *  waiting. Otherwise its request waits in the queue exactly as a `seize` request does, and the claim
 *  is made when the request is woken. The units are allocated after the resume, as `seize` allocates
 *  them.
 *
 *  @param V the allocation the seize produces
 *  @param R the kind of request it places
 */
internal abstract class SeizeTrigger<V, R : ProcessModel.Entity.Request>(
    val queue: RequestQ,
    val seizePriority: Int,
    val recordRenegedWait: Boolean
) : WaitTrigger<V>() {

    /** Places the request in the queue, as the untimed seize does. */
    abstract fun enqueue(entity: ProcessModel.Entity): R

    /** True when the request just placed can take its units at once, as the untimed seize decides. */
    abstract fun canTakeNow(entity: ProcessModel.Entity, request: R): Boolean

    /** Takes the units after any wait; null when the re-wait timed out. */
    abstract suspend fun ProcessModel.Entity.ProcessCoroutine.take(
        request: R,
        suspensionName: String?,
        rewait: (suspend ProcessModel.Entity.ProcessCoroutine.() -> Boolean)?
    ): V?

    /** Takes the units of a request that can be served now, without waiting. */
    abstract fun ProcessModel.Entity.ProcessCoroutine.complete(request: R): V

    /** The name of what is seized, for the animation. */
    abstract val seizedName: String

    override val yieldPriority: Int
        get() = seizePriority

    override val aloneState: AloneState
        get() = AloneState.WAITING_FOR_RESOURCE

    override fun fireNow(process: ProcessModel.Entity.ProcessCoroutine): V {
        // As seize does when it can take the units at once: the request enters the queue and leaves it in the
        // same instant, which records a wait of zero.
        val request = enqueue(process.entity)
        return with(process) { complete(request) }
    }

    override fun register(group: WaitGroup): Registration {
        val entity = group.entity
        val registration = SeizeRegistration(this, group)
        val request = enqueue(entity)
        registration.attach(request)
        if (canTakeNow(entity, request)) {
            // It can take its units now. They are promised to it, as a wake promises them, so nothing else takes
            // them before the wait goes on to allocate; the claim is made while the wait registers, so it
            // schedules no resume.
            check(queue.promiseUnits(request, request.resource)) { "The units of $seizedName could not be promised to request ${request.id}." }
            queue.resumeWoken(request, ProcessModel.RESUME_PRIORITY, ResumeSource.UNKNOWN_INTERNAL, "seize trigger served at once")
        } else {
            entity.emitAnimation { AnimationEvent.SeizeWaiting(queue.time, entity.id, seizedName) }
        }
        return registration
    }
}

/** The trigger of units of a resource, made by `Resource.seizeTrigger`. */
internal class ResourceSeizeTrigger(
    val resource: Resource,
    val amountNeeded: Int,
    seizePriority: Int,
    queue: RequestQ,
    recordRenegedWait: Boolean
) : SeizeTrigger<Allocation, ProcessModel.Entity.ResourceRequest>(queue, seizePriority, recordRenegedWait) {

    init {
        require(amountNeeded >= 1) { "The amount to seize must be >= 1; it was $amountNeeded." }
        if (resource is ksl.modeling.spatial.MovableResource) {
            require(amountNeeded == 1) {
                "A movable resource is seized one unit at a time; the seize trigger of ${resource.name} asked for $amountNeeded."
            }
        }
    }

    override val seizedName: String get() = resource.name

    override fun canFireNow(entity: ProcessModel.Entity): Boolean =
        resource.canAllocate(amountNeeded) && queue.nextRequestForResource(resource) == null

    override fun enqueue(entity: ProcessModel.Entity) = entity.enqueueResourceRequest(resource, amountNeeded, queue)

    override fun canTakeNow(entity: ProcessModel.Entity, request: ProcessModel.Entity.ResourceRequest) =
        entity.canTakeResourceNow(request, queue)

    override suspend fun ProcessModel.Entity.ProcessCoroutine.take(
        request: ProcessModel.Entity.ResourceRequest,
        suspensionName: String?,
        rewait: (suspend ProcessModel.Entity.ProcessCoroutine.() -> Boolean)?
    ): Allocation? = takeResource(request, resource, queue, suspensionName, rewait)

    override fun ProcessModel.Entity.ProcessCoroutine.complete(request: ProcessModel.Entity.ResourceRequest): Allocation =
        completeResourceSeize(request, resource, queue, null)

    override fun toString(): String = "SeizeTrigger(${resource.name}, amount = $amountNeeded)"
}

/** The trigger of units of a resource pool, made by `ResourcePool.seizeTrigger`. */
internal class ResourcePoolSeizeTrigger(
    val resourcePool: ResourcePool,
    val amountNeeded: Int,
    seizePriority: Int,
    queue: RequestQ,
    val resourceSelectionRule: ResourceSelectionRuleIfc,
    val resourceAllocationRule: ResourceAllocationRuleIfc,
    recordRenegedWait: Boolean
) : SeizeTrigger<ResourcePoolAllocation, ProcessModel.Entity.ResourcePoolRequest>(queue, seizePriority, recordRenegedWait) {

    init {
        require(amountNeeded >= 1) { "The amount to seize must be >= 1; it was $amountNeeded." }
    }

    override val seizedName: String get() = resourcePool.name

    // as the untimed pool seize decides: the pool can allocate, whatever else waits
    override fun canFireNow(entity: ProcessModel.Entity): Boolean =
        resourcePool.canAllocate(resourceSelectionRule, amountNeeded)

    override fun enqueue(entity: ProcessModel.Entity) =
        entity.enqueuePoolRequest(resourcePool, amountNeeded, queue, resourceSelectionRule, resourceAllocationRule)

    override fun canTakeNow(entity: ProcessModel.Entity, request: ProcessModel.Entity.ResourcePoolRequest) =
        resourcePool.canAllocate(resourceSelectionRule, amountNeeded)

    override suspend fun ProcessModel.Entity.ProcessCoroutine.take(
        request: ProcessModel.Entity.ResourcePoolRequest,
        suspensionName: String?,
        rewait: (suspend ProcessModel.Entity.ProcessCoroutine.() -> Boolean)?
    ): ResourcePoolAllocation? = takePool(request, resourcePool, queue, suspensionName, rewait)

    override fun ProcessModel.Entity.ProcessCoroutine.complete(request: ProcessModel.Entity.ResourcePoolRequest): ResourcePoolAllocation =
        completePoolSeize(request, resourcePool, queue, null)

    override fun toString(): String = "SeizeTrigger(${resourcePool.name}, amount = $amountNeeded)"
}

/**
 *  The trigger of a movable resource from a movable resource pool, made by
 *  `MovableResourcePool.seizeTrigger`. A null request location means the entity's location when the
 *  wait starts.
 */
internal class MovableResourcePoolSeizeTrigger(
    val movableResourcePool: ksl.modeling.spatial.MovableResourcePool,
    queue: RequestQ,
    val requestLocation: ksl.modeling.spatial.LocationIfc?,
    seizePriority: Int,
    val resourceSelectionRule: ksl.modeling.spatial.MovableResourceSelectionRuleIfc,
    val resourceAllocationRule: ksl.modeling.spatial.MovableResourceAllocationRuleIfc,
    recordRenegedWait: Boolean
) : SeizeTrigger<Allocation, ProcessModel.Entity.MovableResourcePoolRequest>(queue, seizePriority, recordRenegedWait) {

    override val seizedName: String get() = movableResourcePool.name

    // as the untimed movable pool seize decides: the pool can allocate, whatever else waits
    override fun canFireNow(entity: ProcessModel.Entity): Boolean = movableResourcePool.canAllocate(resourceSelectionRule)

    override fun enqueue(entity: ProcessModel.Entity) = entity.enqueueMovablePoolRequest(
        movableResourcePool, queue, requestLocation ?: entity.currentLocation, resourceSelectionRule, resourceAllocationRule
    )

    override fun canTakeNow(entity: ProcessModel.Entity, request: ProcessModel.Entity.MovableResourcePoolRequest) =
        movableResourcePool.canAllocate(resourceSelectionRule)

    override suspend fun ProcessModel.Entity.ProcessCoroutine.take(
        request: ProcessModel.Entity.MovableResourcePoolRequest,
        suspensionName: String?,
        rewait: (suspend ProcessModel.Entity.ProcessCoroutine.() -> Boolean)?
    ): Allocation? = takeMovablePool(request, movableResourcePool, queue, suspensionName, rewait)

    override fun ProcessModel.Entity.ProcessCoroutine.complete(request: ProcessModel.Entity.MovableResourcePoolRequest): Allocation =
        completeMovablePoolSeize(request, movableResourcePool, queue, null)

    override fun toString(): String = "SeizeTrigger(${movableResourcePool.name})"
}

/**
 *  A seize trigger's request in one wait. The winner takes its units after the resume, as `seize`
 *  does; if what it was promised went away in the instant between its wake and its resume, it waits
 *  again, with the same request, for every trigger of its wait and for the time left; another trigger
 *  that ends the re-wait ends the whole wait. A loser leaves the queue at the claim, its wait
 *  recorded unless the trigger says not to, and the free units of what it asked for are offered on
 *  once the claim is over; termination removes the request as termination removes any waiting request.
 */
internal class SeizeRegistration<V, R : ProcessModel.Entity.Request>(
    private val seizeTrigger: SeizeTrigger<V, R>,
    group: WaitGroup
) : Registration(seizeTrigger, group) {

    lateinit var request: R
        private set

    /** The queue the request left as a loser, through which the free units are offered on. */
    private var leftQueue: RequestQ? = null

    fun attach(request: R) {
        this.request = request
        request.registration = this
    }

    override fun onReleaseAsLoser() {
        val queue = request.queue as? RequestQ ?: return
        leftQueue = queue
        queue.removeLeaving(request, seizeTrigger.recordRenegedWait)
    }

    override fun onReleaseOnTermination() {
        (request.queue as? RequestQ)?.removeAbandoned(request)
    }

    override val deferredNotificationSource: Any?
        get() = if (leftQueue != null) request.resource else null

    override fun notifyDeferred() {
        leftQueue?.offerFreeUnitsOf(request.resource, "request_id=${request.id} left without service")
    }

    /** Set when another trigger of the wait ended a re-wait: what the wait ends with instead. */
    private var otherWinner: AfterResume.Other? = null

    override suspend fun ProcessModel.Entity.ProcessCoroutine.afterWinningResume(): AfterResume {
        markReleased()
        otherWinner = null
        val value = with(seizeTrigger) { take(request, group.suspensionName, rewait = { rewait() }) }
        return if (value != null) AfterResume.Value(value) else otherWinner ?: AfterResume.TimedOut
    }

    /**
     *  Waits again, with the same request, for every trigger of the wait, in their order, for the time left to
     *  the original deadline. The other triggers register afresh; one that can fire at once ends the wait as
     *  the fast path of `waitForFirst` does.
     *
     *  @return true when this seize trigger claimed the re-wait; false when the timer did, or another trigger,
     *  whose result is then kept for [afterWinningResume]
     */
    private suspend fun ProcessModel.Entity.ProcessCoroutine.rewait(): Boolean {
        val again = WaitGroup(group.entity, group.processId, group.triggers, entity.processModel.time, group.suspensionName)
        again.deadline = group.deadline
        again.timeoutPriority = group.timeoutPriority
        val seizeAgain = SeizeRegistration(seizeTrigger, again)
        seizeAgain.attach(request)
        for (trigger in group.triggers) {
            if (trigger !== seizeTrigger && trigger.canFireNow(entity)) {
                val value = trigger.fireNow(this)
                // the request loses, as at any claim: it leaves its queue and its units are offered on
                again.registrations.add(seizeAgain)
                again.claim(null, null, ResumeSource.UNKNOWN_INTERNAL, ProcessModel.RESUME_PRIORITY)
                otherWinner = AfterResume.Other(trigger, value)
                return false
            }
        }
        for (trigger in group.triggers) {
            again.registrations.add(if (trigger === seizeTrigger) seizeAgain else trigger.register(again))
            if (again.claimed) break
        }
        if (!again.claimed) waitAgain(again)
        if (again.timedOut) return false
        val winner = again.winnerRegistration ?: return false
        if (winner === seizeAgain) return true
        otherWinner = when (val after = with(winner) { afterWinningResume() }) {
            is AfterResume.Value -> AfterResume.Other(winner.trigger, after.value)
            is AfterResume.Other -> after
            AfterResume.TimedOut -> null
        }
        return false
    }
}
