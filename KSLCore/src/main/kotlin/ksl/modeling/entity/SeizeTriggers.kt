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
 *  waiting. Otherwise its request waits in the queue exactly as a `seize` request does; when its queue
 *  serves it, its units are allocated and its claim on the wait is made, finally, at that moment, and
 *  the process takes the allocation when it resumes.
 *
 *  @param V the allocation the seize produces
 *  @param R the kind of request it places
 */
internal abstract class SeizeTrigger<V, R : ProcessModel.Entity.Request>(
    val queue: RequestQ,
    val seizePriority: Int,
    val recordRenegedWait: Boolean
) : WaitTrigger<V>() {

    /**
     *  Places the request in the queue at [requestPriority], as the untimed seize does at the entity's priority.
     *  [beforeQueued] is given the request once it is made and before it enters the queue.
     */
    abstract fun enqueue(entity: ProcessModel.Entity, requestPriority: Int, beforeQueued: (R) -> Unit = {}): R

    /** True when the request just placed can take its units at once, as the untimed seize decides. */
    abstract fun canTakeNow(entity: ProcessModel.Entity, request: R): Boolean

    /** Takes the allocation the library made when it served the request, or the units after any wait. */
    abstract suspend fun ProcessModel.Entity.ProcessCoroutine.take(
        request: R,
        suspensionName: String?
    ): V

    /** Takes the units of a request that can be served now, without waiting, naming the allocation [suspensionName]. */
    abstract fun ProcessModel.Entity.ProcessCoroutine.complete(request: R, suspensionName: String?): V

    /** The name of what is seized, for the animation. */
    abstract val seizedName: String

    override val yieldPriority: Int
        get() = seizePriority

    override val aloneState: AloneState
        get() = AloneState.WAITING_FOR_RESOURCE

    override fun fireNow(process: ProcessModel.Entity.ProcessCoroutine, suspensionName: String?): V {
        // As seize does when it can take the units at once: the request enters the queue and leaves it in the
        // same instant, which records a wait of zero.
        val request = enqueue(process.entity, process.entity.priority)
        return with(process) { complete(request, suspensionName) }
    }

    /** Called when the request has been queued and could not be served at once. */
    internal open fun offerFreeUnitsAfterArrival() {}

    override fun register(group: WaitGroup): Registration {
        val entity = group.entity
        val registration = SeizeRegistration(this, group)
        // ranked by the priority when the wait began, whatever a trigger registered before it did to the entity's
        // attached before it is queued: the queuing can run model code (a queue's listener) that ends the wait, and the
        // claim then releases this registration with its request
        val request = enqueue(entity, group.entityPriority) { registration.attach(it) }
        if (registration.isReleased) {
            // the wait ended as the request was queued
        } else if (canTakeNow(entity, request)) {
            // It can take its units now: it is served as at a wake, and its claim, made while the wait registers,
            // schedules no resume
            check(queue.serve(request, ProcessModel.RESUME_PRIORITY, ResumeSource.UNKNOWN_INTERNAL, "seize trigger served at once")) {
                "The units of $seizedName could not be allocated to request ${request.id}."
            }
        } else if (!registration.isReleased) {
            entity.emitAnimation { AnimationEvent.SeizeWaiting(queue.time, entity.id, seizedName) }
            offerFreeUnitsAfterArrival()
        }
        // else its arrival let the queue serve another trigger of the same wait, which won and released this one
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
    // With a waiting preemptor or a reservation collecting, the resource's walk of claimants decides (canTakeNow).
    override fun canFireNow(entity: ProcessModel.Entity): Boolean =
        queue.requestSelectionRule == null && !resource.hasPreemptionClaimants &&
                resource.canAllocate(amountNeeded) && queue.nextRequestForResource(resource) == null

    override val decidesWhenQueued: Boolean
        get() = queue.requestSelectionRule != null || resource.hasPreemptionClaimants

    override fun offerFreeUnitsAfterArrival() {
        if (resource.canAllocate(amountNeeded)) queue.offerFreeUnitsUnderRule(resource, "seize_trigger_arrived_not_chosen")
    }

    override fun enqueue(entity: ProcessModel.Entity, requestPriority: Int, beforeQueued: (ProcessModel.Entity.ResourceRequest) -> Unit) =
        entity.enqueueResourceRequest(resource, amountNeeded, queue, requestPriority, beforeQueued = beforeQueued)

    override fun canTakeNow(entity: ProcessModel.Entity, request: ProcessModel.Entity.ResourceRequest) =
        entity.canTakeResourceNow(request, queue)

    override suspend fun ProcessModel.Entity.ProcessCoroutine.take(
        request: ProcessModel.Entity.ResourceRequest,
        suspensionName: String?
    ): Allocation = takeResource(request, resource, queue, suspensionName)

    override fun ProcessModel.Entity.ProcessCoroutine.complete(request: ProcessModel.Entity.ResourceRequest, suspensionName: String?): Allocation =
        completeResourceSeize(request, resource, queue, suspensionName)

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

    override fun enqueue(entity: ProcessModel.Entity, requestPriority: Int, beforeQueued: (ProcessModel.Entity.ResourcePoolRequest) -> Unit) =
        entity.enqueuePoolRequest(resourcePool, amountNeeded, queue, resourceSelectionRule, resourceAllocationRule, requestPriority, beforeQueued = beforeQueued)

    override fun canTakeNow(entity: ProcessModel.Entity, request: ProcessModel.Entity.ResourcePoolRequest) =
        resourcePool.canAllocate(resourceSelectionRule, amountNeeded) && queue.nextRequestForResource(resourcePool) === request

    override suspend fun ProcessModel.Entity.ProcessCoroutine.take(
        request: ProcessModel.Entity.ResourcePoolRequest,
        suspensionName: String?
    ): ResourcePoolAllocation = takePool(request, resourcePool, queue, suspensionName)

    override fun ProcessModel.Entity.ProcessCoroutine.complete(request: ProcessModel.Entity.ResourcePoolRequest, suspensionName: String?): ResourcePoolAllocation =
        completePoolSeize(request, resourcePool, queue, suspensionName)

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

    override fun enqueue(entity: ProcessModel.Entity, requestPriority: Int, beforeQueued: (ProcessModel.Entity.MovableResourcePoolRequest) -> Unit) =
        entity.enqueueMovablePoolRequest(
            movableResourcePool, queue, requestLocation ?: entity.currentLocation, resourceSelectionRule, resourceAllocationRule,
            requestPriority, beforeQueued = beforeQueued
        )

    override fun canTakeNow(entity: ProcessModel.Entity, request: ProcessModel.Entity.MovableResourcePoolRequest) =
        movableResourcePool.canAllocate(resourceSelectionRule) && queue.nextRequestForResource(movableResourcePool) === request

    override suspend fun ProcessModel.Entity.ProcessCoroutine.take(
        request: ProcessModel.Entity.MovableResourcePoolRequest,
        suspensionName: String?
    ): Allocation = takeMovablePool(request, movableResourcePool, queue, suspensionName)

    override fun ProcessModel.Entity.ProcessCoroutine.complete(request: ProcessModel.Entity.MovableResourcePoolRequest, suspensionName: String?): Allocation =
        completeMovablePoolSeize(request, movableResourcePool, queue, suspensionName)

    override fun toString(): String = "SeizeTrigger(${movableResourcePool.name})"
}

/**
 *  A seize trigger's request in one wait. The library serves the request as it serves any waiting request
 *  (`RequestQ.serve`): its units are allocated when it is chosen, and its claim on the wait is final, so the wait's
 *  other registrations are released at once. The winner's process takes the allocation passed to it when it
 *  resumes. A loser leaves the queue when it is released, its wait recorded unless the trigger says not to and its
 *  leaving announced to the animation, and the free units of what it asked for are offered on once the release is
 *  over. Termination removes the request as termination removes any waiting request.
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

    /** The request was served when the library chose it; the resume takes the allocation passed to it. */
    override suspend fun ProcessModel.Entity.ProcessCoroutine.afterWinningResume(): AfterResume {
        markReleased()
        return AfterResume.Value(with(seizeTrigger) { take(request, group.suspensionName) })
    }
}
