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

    /** Places the request in the queue at [requestPriority], as the untimed seize does at the entity's priority. */
    abstract fun enqueue(entity: ProcessModel.Entity, requestPriority: Int): R

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
        val request = enqueue(process.entity, process.entity.priority)
        return with(process) { complete(request) }
    }

    /** Called when the request has been queued and could not be served at once. */
    internal open fun offerFreeUnitsAfterArrival() {}

    override fun register(group: WaitGroup): Registration {
        val entity = group.entity
        val registration = SeizeRegistration(this, group)
        // ranked by the priority when the wait began, whatever a trigger registered before it did to the entity's
        val request = enqueue(entity, group.entityPriority)
        registration.attach(request)
        if (canTakeNow(entity, request)) {
            // It can take its units now. They are promised to it, as a wake promises them, so nothing else takes
            // them before the wait goes on to allocate; the claim is made while the wait registers, so it
            // schedules no resume.
            check(queue.promiseUnits(request, request.resource)) { "The units of $seizedName could not be promised to request ${request.id}." }
            queue.resumeWoken(request, ProcessModel.RESUME_PRIORITY, ResumeSource.UNKNOWN_INTERNAL, "seize trigger served at once")
        } else {
            entity.emitAnimation { AnimationEvent.SeizeWaiting(queue.time, entity.id, seizedName) }
            offerFreeUnitsAfterArrival()
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

    // A request selection rule chooses among queued requests, so with one the request must be queued before it can
    // be chosen: the wait then registers it, and serves it at once when the rule chooses it (canTakeNow).
    override fun canFireNow(entity: ProcessModel.Entity): Boolean =
        queue.requestSelectionRule == null &&
                resource.canAllocate(amountNeeded) && queue.nextRequestForResource(resource) == null

    override val decidesWhenQueued: Boolean
        get() = queue.requestSelectionRule != null

    override fun offerFreeUnitsAfterArrival() {
        if (resource.canAllocate(amountNeeded)) queue.offerFreeUnitsUnderRule(resource, "seize_trigger_arrived_not_chosen")
    }

    override fun enqueue(entity: ProcessModel.Entity, requestPriority: Int) =
        entity.enqueueResourceRequest(resource, amountNeeded, queue, requestPriority)

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

    // as a resource's seize trigger decides: the pool can allocate and no request it could serve waits ahead; with a
    // request selection rule, only once queued (decidesWhenQueued)
    override fun canFireNow(entity: ProcessModel.Entity): Boolean =
        queue.requestSelectionRule == null && resourcePool.canAllocate(resourceSelectionRule, amountNeeded) &&
                queue.nextRequestForResource(resourcePool) == null

    override val decidesWhenQueued: Boolean
        get() = queue.requestSelectionRule != null

    override fun offerFreeUnitsAfterArrival() {
        if (resourcePool.canAllocate(resourceSelectionRule, amountNeeded)) queue.offerFreeUnitsUnderRule(resourcePool, "seize_trigger_arrived_not_chosen")
    }

    override fun enqueue(entity: ProcessModel.Entity, requestPriority: Int) =
        entity.enqueuePoolRequest(resourcePool, amountNeeded, queue, resourceSelectionRule, resourceAllocationRule, requestPriority)

    override fun canTakeNow(entity: ProcessModel.Entity, request: ProcessModel.Entity.ResourcePoolRequest) =
        resourcePool.canAllocate(resourceSelectionRule, amountNeeded) && queue.nextRequestForResource(resourcePool) === request

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

    // as a resource's seize trigger decides: the pool can allocate and no request it could serve waits ahead; with a
    // request selection rule, only once queued (decidesWhenQueued)
    override fun canFireNow(entity: ProcessModel.Entity): Boolean =
        queue.requestSelectionRule == null && movableResourcePool.canAllocate(resourceSelectionRule) &&
                queue.nextRequestForResource(movableResourcePool) == null

    override val decidesWhenQueued: Boolean
        get() = queue.requestSelectionRule != null

    override fun offerFreeUnitsAfterArrival() {
        if (movableResourcePool.canAllocate(resourceSelectionRule)) queue.offerFreeUnitsUnderRule(movableResourcePool, "seize_trigger_arrived_not_chosen")
    }

    override fun enqueue(entity: ProcessModel.Entity, requestPriority: Int) = entity.enqueueMovablePoolRequest(
        movableResourcePool, queue, requestLocation ?: entity.currentLocation, resourceSelectionRule, resourceAllocationRule,
        requestPriority
    )

    override fun canTakeNow(entity: ProcessModel.Entity, request: ProcessModel.Entity.MovableResourcePoolRequest) =
        movableResourcePool.canAllocate(resourceSelectionRule) && queue.nextRequestForResource(movableResourcePool) === request

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
 *  does. Its win is provisional (`winsProvisionally`): what it was promised can go away in the instant between
 *  its wake and its resume, so the wait's other registrations are held in place until the units are taken, and
 *  are released then. If the promise broke, the wait is reopened: it goes on with the same request, for every
 *  trigger, each still in its place, and for the time left; another trigger that ends the reopened wait ends
 *  the whole wait. A loser leaves the queue when it is released, its wait recorded unless the trigger says not
 *  to and its leaving announced to the animation, and the free units of what it asked for are offered on once
 *  the release is over. While held, it is passed over by the offers as a woken request is. Termination removes
 *  the request as termination removes any waiting request.
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

    override val winsProvisionally: Boolean
        get() = true

    override fun onReleaseAsLoser() {
        val queue = request.queue as? RequestQ ?: return
        leftQueue = queue
        if (queue.removeLeaving(request, seizeTrigger.recordRenegedWait)) {
            val entity = group.entity
            // what the request asks for now, which is not what the trigger named if the request was moved
            entity.emitAnimation { AnimationEvent.SeizeWithdrawn(queue.time, entity.id, request.resource.name, queue.name) }
        }
    }

    override fun onReleaseOnTermination() {
        // the units are offered once every registration of the ended wait is released (notifyDeferred)
        val queue = request.queue as? RequestQ ?: return
        if (queue.removeAbandoned(request, offer = false)) leftQueue = queue
    }

    override val deferredNotificationSource: Any?
        get() = if (leftQueue != null) request.resource else null

    override fun notifyDeferred() {
        leftQueue?.offerFreeUnitsOf(request.resource, "request_id=${request.id} left without service")
    }

    /** Held while another seize trigger's win was undecided, the request may have been passed over: offer to it. */
    override fun lookAgain(process: ProcessModel.Entity.ProcessCoroutine) {
        val queue = request.queue as? RequestQ ?: return
        queue.offerFreeUnitsOf(request.resource, "request_id=${request.id} waits again")
    }

    /**
     *  The promise of this winner's units broke before it could take them: reopens its wait, so that the other
     *  registrations are no longer held and can be served where they stand, and marks this one waiting again.
     *  Called before the withdrawn units are offered on. Does nothing for a wait this registration has not won
     *  provisionally.
     */
    fun reopenAfterBrokenPromise() {
        if (!group.isProvisional || group.winnerRegistration !== this) return
        group.reopen()
        markWaitingAgain()
    }

    /**
     *  After a reopening, the request can take other units that are free now: it wins the reopened wait again,
     *  provisionally, holding the other registrations until the take settles it. Does nothing if something
     *  else claimed the wait meanwhile.
     */
    fun winAgain() {
        if (group.claimed) return
        claimWait(null, ResumeSource.UNKNOWN_INTERNAL, ProcessModel.RESUME_PRIORITY)
    }

    /** Set when another trigger of the wait ended a reopened wait: what the wait ends with instead. */
    private var otherWinner: AfterResume.Other? = null

    override suspend fun ProcessModel.Entity.ProcessCoroutine.afterWinningResume(): AfterResume {
        markReleased()
        otherWinner = null
        val value = with(seizeTrigger) { take(request, group.suspensionName, rewait = { rewait() }) }
        if (value != null) {
            // the units are taken: the win holds, and the other registrations are released as losers
            group.settle()
            return AfterResume.Value(value)
        }
        return otherWinner ?: AfterResume.TimedOut
    }

    /**
     *  Waits again after the promise broke, for the time left to the original deadline. The wait was reopened
     *  before the withdrawn units were offered on (`reopenAfterBrokenPromise`), and that offer may already have
     *  served another of its triggers. Otherwise the other registrations are still in place: each source is asked, in argument order, to look at its
     *  registration again (a message already in the mailbox, items now in the blocking queue, units free for
     *  another seize trigger, an allocation now whole), and the first that can serve it claims the wait. A
     *  trigger that was never registered, because this one won while the wait was registering, registers now.
     *  Otherwise the wait suspends again. This request's own units were offered on as the promise was withdrawn.
     *
     *  @return true when this seize trigger claimed the reopened wait; false when the timer did, or another
     *  trigger, whose result is then kept for [afterWinningResume]
     */
    private suspend fun ProcessModel.Entity.ProcessCoroutine.rewait(): Boolean {
        // reopened already, before the withdrawn units were offered (reopenAfterBrokenPromise); that offer may
        // have served another trigger of the wait, which then claimed it
        reopenAfterBrokenPromise()
        if (!group.claimed) {
            val byTrigger = group.registrations.associateBy { it.trigger }
            for (trigger in group.triggers) {
                if (trigger === seizeTrigger) continue
                val registration = byTrigger[trigger]
                if (registration == null) trigger.registerAgain(group) else registration.lookAgain(this)
                if (group.claimed) break
            }
        }
        if (!group.claimed) waitAgain(group)
        if (group.timedOut) return false
        val winner = group.winnerRegistration ?: return false
        if (winner === this@SeizeRegistration) return true
        otherWinner = when (val after = with(winner) { afterWinningResume() }) {
            is AfterResume.Value -> AfterResume.Other(winner.trigger, after.value)
            is AfterResume.Other -> after
            AfterResume.TimedOut -> null
        }
        return false
    }
}
