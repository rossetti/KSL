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
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.Resource
import ksl.modeling.entity.ResourceInvariants

/**
 *  Compares each outermost offer of units with the reference allocator: as the offer begins it takes a snapshot
 *  of the resource and asks the reference who should be served; as it ends it reads who was served and how, from
 *  the requests' states, and records every difference.
 */
internal class ReferenceOfferObserver(private val preemptorsAlsoQueued: Boolean = false) : ResourceInvariants.OfferObserver {

    /** One claimant whose treatment differs from the reference's. */
    class Difference(val time: Double, val kind: String, val detail: String, val snapshot: String) {
        override fun toString() = "t=$time $kind: $detail\n      at offer start: $snapshot"
    }

    val differences = mutableListOf<Difference>()
    var offersJudged = 0
        private set
    val skipped = mutableMapOf<String, Int>()

    private val myOpen = mutableMapOf<Resource, Pair<ResourceSnapshot, Map<ProcessModel.Entity.Request, OfferOutcome>>>()

    override fun beforeOffer(resource: Resource, returning: Map<Allocation, Int>) {
        val snap = ResourceSnapshot.take(resource, returning)
        if (snap.outOfScope != null) {
            skipped.merge(snap.outOfScope, 1, Int::plus)
            myOpen.remove(resource)
            return
        }
        myOpen[resource] = snap to ReferenceAllocator.decide(snap, preemptorsAlsoQueued)
    }

    override fun afterOffer(resource: Resource) {
        val (snap, expected) = myOpen.remove(resource) ?: return
        offersJudged++
        for (r in snap.requests) {
            val actual = actualOutcome(resource, r)
            val ref = expected[r.request]
            if (sameOutcome(ref, actual)) continue
            val kind = r.kind(snap.reachableCapacity)
            val who = when (kind) {
                ClaimantKind.PREEMPTOR -> "preemptor-${r.preemptRule}"
                ClaimantKind.RESERVATION -> "reservation"
                ClaimantKind.QUEUED -> "queued"
                null -> "non-claimant"
            }
            val label = when {
                ref is OfferOutcome.Served && actual is OfferOutcome.Served -> "$who: different lenders"
                ref != null && actual != null && ref.label == actual.label -> "$who: different amount ${ref.label}"
                else -> "$who: reference=${ref?.label ?: "none"} library=${actual?.label ?: "none"}"
            }
            differences.add(Difference(snap.time, label, "${r.name}: reference ${ref ?: "none"}, library ${actual ?: "none"}", snap.toString()))
        }
    }

    private fun sameOutcome(a: OfferOutcome?, b: OfferOutcome?): Boolean = a == b

    /** What the offer did for [r], read from its state now against its state as the offer began. */
    private fun actualOutcome(resource: Resource, r: RequestSnap): OfferOutcome? {
        val request = r.request
        // an arriving request takes, or reserves, itself once the walk has decided, just after the offer
        if (request === resource.arrivingRequest) {
            return when (val d = resource.arrivalDecisionSoFar) {
                is Resource.ArrivalDecision.Take -> OfferOutcome.Served(
                    d.plan.takes.groupBy { it.first.id }.mapValues { (_, t) -> t.sumOf { it.second } }
                )
                is Resource.ArrivalDecision.Reserve -> OfferOutcome.Reserved(d.free)
                else -> null
            }
        }
        val passed = request.passedAllocation
        if (r.passedAllocation == null && passed != null) {
            val lenders = resource.loans.filter { it.borrower === passed }
                .groupBy { it.lender?.id ?: -1 }.mapValues { (_, loans) -> loans.sumOf { it.amount } }
            return OfferOutcome.Served(lenders)
        }
        if (!r.resumePending && request.resumePending) return OfferOutcome.Served(emptyMap())
        val promised = request.promises[resource] ?: 0
        if (!r.hasReservation && request.reservation != null) return OfferOutcome.Reserved(promised)
        if (r.hasReservation && request.reservation == null) return OfferOutcome.Other("reservation-dropped")
        if (promised > r.promised) return OfferOutcome.Collected(promised - r.promised)
        if (promised < r.promised) return OfferOutcome.Other("promise-withdrawn")
        return null
    }
}
