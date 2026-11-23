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

package ksl.modeling.entity.stress

import ksl.modeling.entity.Allocation
import ksl.modeling.entity.DefaultRequestQueueNotificationRule
import ksl.modeling.entity.InterruptRule
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.Resource
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.queue.Queue

/**
 *  A holder of units of the resource at the start of an offer: an allocation, with what of it is still in service
 *  and so could be lent to a preemptor that outranks it.
 */
internal class HolderSnap(
    val allocation: Allocation,
    val entity: ProcessModel.Entity,
    val priority: Int,
    val amount: Int,
    /** The units still in service: the amount less those lent or missing to a failure. */
    val inService: Int,
    val timeAllocated: Double,
    val id: Int
) {
    override fun toString() = "${entity.name}#$id(p$priority n=$amount inService=$inService t=$timeAllocated)"
}

/** What a waiting request is, as a claimant of units. */
internal enum class ClaimantKind { PREEMPTOR, RESERVATION, QUEUED }

/** A request waiting in the resource's queue at the start of an offer. */
internal class RequestSnap(
    val request: ProcessModel.Entity.Request,
    val name: String,
    val amount: Int,
    val priority: Int,
    val timeEntered: Double,
    /** Its place in its queue, in the queue's order; 0 is the front. */
    val position: Int,
    val preemptRule: InterruptRule?,
    val isWaitingPreemptor: Boolean,
    val hasReservation: Boolean,
    val reservationComplete: Boolean,
    val stillNeeded: Int,
    val promised: Int,
    val resumePending: Boolean,
    val passedAllocation: Allocation?,
    val held: Boolean
) {
    /** The kind of claimant this request is now, or null when the offer cannot serve it. */
    fun kind(reachableCapacity: Int): ClaimantKind? = when {
        resumePending || passedAllocation != null || held -> null
        hasReservation -> if (!reservationComplete && amount <= reachableCapacity) ClaimantKind.RESERVATION else null
        isWaitingPreemptor -> ClaimantKind.PREEMPTOR
        else -> ClaimantKind.QUEUED
    }

    override fun toString(): String {
        val what = when {
            hasReservation -> "reservation(still=$stillNeeded${if (reservationComplete) " complete" else ""})"
            isWaitingPreemptor -> "preempt-$preemptRule"
            else -> "seize"
        }
        return "$name(p$priority n=$amount $what since=$timeEntered pos=$position promised=$promised" +
                "${if (resumePending) " woken" else ""}${if (passedAllocation != null) " passed" else ""})"
    }
}

/**
 *  The state of a resource as an offer of its units begins, taken from the resource's internal records, and the
 *  reason the reference allocator cannot judge the offer, if any.
 */
internal class ResourceSnapshot(
    val resource: Resource,
    val time: Double,
    val capacity: Int,
    val reachableCapacity: Int,
    val free: Int,
    val isFailed: Boolean,
    val isInactive: Boolean,
    /** Units of ending loans going back to their lenders, by lender. */
    val returning: Map<Allocation, Int>,
    val holders: List<HolderSnap>,
    /** The requests of the resource's queue, in the queue's order. */
    val requests: List<RequestSnap>,
    /** Why the reference cannot judge this offer; null when it can. */
    val outOfScope: String?
) {
    override fun toString(): String = buildString {
        append("t=$time cap=$capacity reachable=$reachableCapacity free=$free")
        if (isFailed) append(" FAILED")
        if (isInactive) append(" INACTIVE")
        if (returning.isNotEmpty()) append(" returning=${returning.entries.joinToString { "${it.key.myEntity.name}#${it.key.id}:${it.value}" }}")
        append("\n      holders=$holders")
        append("\n      waiting=$requests")
    }

    companion object {

        private val reachableGetter = runCatching {
            Resource::class.java.getDeclaredMethod("getReachableCapacity").apply { isAccessible = true }
        }.getOrNull()

        /** The capacity [resource] is heading to: its current change, and any change asked for and waiting. */
        private fun reachableCapacityOf(resource: Resource): Int =
            reachableGetter?.invoke(resource) as? Int ?: resource.capacity

        fun take(resource: Resource, returning: Map<Allocation, Int>): ResourceSnapshot {
            val reachable = reachableCapacityOf(resource)
            val scope = scopeProblem(resource)
            val holders = resource.allocations().map { a ->
                HolderSnap(
                    a, a.myEntity, a.myEntity.priority, a.amount,
                    a.amount - a.missingByLoan - a.missingByFailure, a.timeAllocated, a.id
                )
            }
            val preemptors = resource.waitingPreemptors.toSet()
            val queue = (resource as? ResourceWithQ)?.myWaitingQ
            val requests = queue?.orderedList()?.withIndex()?.map { (i, r) ->
                RequestSnap(
                    request = r,
                    name = r.entity.name,
                    amount = r.amountRequested,
                    priority = r.entity.priority,
                    timeEntered = r.timeEnteredQueue,
                    position = i,
                    // an arriving preempt is a waiting preemptor for the walk that decides its arrival
                    preemptRule = if (r === resource.arrivingRequest) resource.arrivingRule ?: r.waitingPreemptRule else r.waitingPreemptRule,
                    isWaitingPreemptor = (r in preemptors && r.waitingPreemptRule != null) ||
                            (r === resource.arrivingRequest && resource.arrivingRule != null),
                    hasReservation = r.reservation != null,
                    reservationComplete = r.reservation?.isComplete ?: false,
                    stillNeeded = r.reservation?.stillNeeded ?: r.amountRequested,
                    promised = r.promises[resource] ?: 0,
                    resumePending = r.resumePending,
                    passedAllocation = r.passedAllocation,
                    held = r.registration?.isHeld == true
                )
            } ?: emptyList()
            return ResourceSnapshot(
                resource, resource.time, resource.capacity, reachable, resource.numAvailableUnits,
                resource.isFailed, resource.isInactive, returning.filterValues { it > 0 }, holders, requests, scope
            )
        }

        /** The reference covers one resource with its own FIFO queue, no selection rule, the default notification rule and no pool. */
        private fun scopeProblem(resource: Resource): String? {
            if (resource !is ResourceWithQ) return "not a ResourceWithQ"
            val queue = resource.myWaitingQ
            if (resource.registeredQueues != setOf(queue)) return "other queues registered"
            if (queue.currentDiscipline != Queue.Discipline.FIFO) return "queue discipline ${queue.currentDiscipline}"
            if (queue.requestSelectionRule != null) return "request selection rule"
            if (resource.requestQNotificationRule !== DefaultRequestQueueNotificationRule) return "notification rule"
            if (resource.myResourcePools.isNotEmpty()) return "in a pool"
            if (reachableGetter == null) return "reachable capacity unreadable"
            for (r in queue.orderedList()) {
                if (r !is ProcessModel.Entity.ResourceRequest || r.resource !== resource) return "a request for another resource"
                if (r.registration != null) return "a seize trigger"
            }
            return null
        }
    }
}
