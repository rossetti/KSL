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

import ksl.modeling.entity.InterruptRule
import ksl.modeling.entity.ProcessModel

/** What an offer did for one claimant. */
internal sealed class OfferOutcome {
    /** Given all it needs: woken with its units promised, or given an allocation with [lenders]' units (lender id to units). */
    data class Served(val lenders: Map<Int, Int>) : OfferOutcome() {
        override fun toString() = if (lenders.isEmpty()) "served" else "served(lent ${lenders.entries.joinToString { "#${it.key}:${it.value}" }})"
    }

    /** A FINISH preemptor that reserved, promised [promised] free units. */
    data class Reserved(val promised: Int) : OfferOutcome() {
        override fun toString() = "reserved(promised $promised)"
    }

    /** A reservation still collecting, promised [more] free units. */
    data class Collected(val more: Int) : OfferOutcome() {
        override fun toString() = "collected($more)"
    }

    /** Something the reference cannot name, seen in the library's state. */
    data class Other(val what: String) : OfferOutcome() {
        override fun toString() = what
    }

    val label: String
        get() = when (this) {
            is Served -> if (lenders.isEmpty()) "served" else "served-with-loans"
            is Reserved -> "reserved"
            is Collected -> "collected"
            is Other -> what
        }
}

/**
 *  Says, from the rules alone, who should be served when units of a resource become available: the rules of the
 *  entity guide's preemption section and the claim order the library documents. It knows nothing of how the
 *  library decides. Given a snapshot taken as an offer begins, it returns what each claimant should get.
 *
 *  - Claimants: each waiting preemptor, each FINISH reservation still collecting, and the queue's next servable
 *    request (the first in queue order that fits in the free units).
 *  - Order: by priority (lower is more urgent); on equal priority, preemption claimants by the time they began
 *    waiting and then their place in the queue, and a preemption claimant against a queued request by queue position.
 *  - Serving: the first claimant in that order that can be served now is served and the offer shrinks; then the
 *    search starts again from the front. Claimants passed over hold nothing back.
 */
internal object ReferenceAllocator {

    private class Claimant(val snap: RequestSnap, val kind: ClaimantKind) {
        var stillNeeded: Int = snap.stillNeeded
        val priority get() = snap.priority
        val entity get() = snap.request.entity
    }

    private class Holder(val id: Int, val entity: ProcessModel.Entity, val priority: Int, val time: Double, var inService: Int, var returning: Int)

    /** Negative when [a] comes before [b] in the claim order. */
    private fun comesBefore(a: Claimant, b: Claimant): Int {
        if (a.priority != b.priority) return a.priority.compareTo(b.priority)
        val bothPreemption = a.kind != ClaimantKind.QUEUED && b.kind != ClaimantKind.QUEUED
        // the guide: a waiting preemptor gives way to a FINISH preemptor as urgent that is still taking its units
        if (bothPreemption && (a.kind == ClaimantKind.RESERVATION) != (b.kind == ClaimantKind.RESERVATION)) {
            return if (a.kind == ClaimantKind.RESERVATION) -1 else 1
        }
        if (bothPreemption && a.snap.timeEntered != b.snap.timeEntered) return a.snap.timeEntered.compareTo(b.snap.timeEntered)
        return a.snap.position.compareTo(b.snap.position)
    }

    /**
     *  With [preemptorsAlsoQueued] true (a variant for diagnosis, not the rules as settled), a waiting preemptor also
     *  counts as a request of its queue: when it is the queue's next servable request it may be served from the free
     *  units in the queue's order, as a seize there would be.
     */
    fun decide(s: ResourceSnapshot, preemptorsAlsoQueued: Boolean = false): Map<ProcessModel.Entity.Request, OfferOutcome> {
        val outcomes = LinkedHashMap<ProcessModel.Entity.Request, OfferOutcome>()
        if (s.isFailed || s.isInactive) return outcomes
        var free = s.free
        // holders, lowest priority first, the most recently allocated first among equals
        val holders = s.holders.map { h ->
            Holder(h.id, h.entity, h.priority, h.timeAllocated, h.inService, s.returning[h.allocation] ?: 0)
        }.toMutableList()
        // a lender with units going back but no longer allocated still gets them back; record it all the same
        for ((lender, k) in s.returning) {
            if (holders.none { it.id == lender.id }) {
                holders.add(Holder(lender.id, lender.myEntity, lender.myEntity.priority, lender.timeAllocated, 0, k))
            }
        }
        fun holderOrder() = holders.sortedWith(
            compareByDescending<Holder> { it.priority }.thenByDescending { it.time }.thenByDescending { it.id }
        )
        var newId = Int.MAX_VALUE

        val preemption = mutableListOf<Claimant>()
        val queued = mutableListOf<Claimant>()
        for (r in s.requests) {
            when (val kind = r.kind(s.reachableCapacity)) {
                null -> {}
                ClaimantKind.QUEUED -> queued.add(Claimant(r, kind))
                else -> {
                    preemption.add(Claimant(r, kind))
                    if (preemptorsAlsoQueued && kind == ClaimantKind.PREEMPTOR) queued.add(Claimant(r, ClaimantKind.QUEUED))
                }
            }
        }
        // the priorities of the reservations collecting, including those made in this offer
        val collecting = s.requests.filter { it.kind(s.reachableCapacity) == ClaimantKind.RESERVATION }
            .associateTo(LinkedHashMap()) { it.request to it.priority }

        /** Takes [short] units from the holders [c] outranks, returning units first; [inService] allows units in service. */
        fun takeFromHolders(c: Claimant, short: Int, inService: Boolean, commit: Boolean): Map<Int, Int>? {
            var left = short
            val takes = LinkedHashMap<Int, Int>()
            for (h in holderOrder()) {
                if (left == 0) break
                if (h.priority <= c.priority || h.entity === c.entity) continue
                val fromReturning = minOf(left, h.returning)
                left -= fromReturning
                val fromService = if (inService) minOf(left, h.inService) else 0
                left -= fromService
                if (fromReturning + fromService > 0) {
                    takes[h.id] = fromReturning + fromService
                    if (commit) {
                        h.returning -= fromReturning
                        h.inService -= fromService
                    }
                }
            }
            return if (left == 0) takes else null
        }

        /** Serves [c] if it can be served now; true when the offer changed. */
        fun tryServe(c: Claimant): Boolean {
            when (c.kind) {
                ClaimantKind.QUEUED -> {
                    if (c.snap.amount > free) return false
                    free -= c.snap.amount
                    outcomes[c.snap.request] = OfferOutcome.Served(emptyMap())
                    queued.remove(c)
                    preemption.removeAll { it.snap === c.snap }
                    return true
                }
                ClaimantKind.RESERVATION -> {
                    val need = c.stillNeeded
                    if (free >= need) {
                        free -= need
                        outcomes[c.snap.request] = OfferOutcome.Served(emptyMap())
                        preemption.remove(c)
                        collecting.remove(c.snap.request)
                        return true
                    }
                    val lent = takeFromHolders(c, need - free, inService = false, commit = false)
                    if (lent != null) {
                        takeFromHolders(c, need - free, inService = false, commit = true)
                        free = 0
                        outcomes[c.snap.request] = OfferOutcome.Served(lent)
                        preemption.remove(c)
                        collecting.remove(c.snap.request)
                        holders.add(Holder(newId--, c.entity, c.priority, s.time, c.snap.amount, 0))
                        return true
                    }
                    if (free > 0) {
                        val prior = (outcomes[c.snap.request] as? OfferOutcome.Collected)?.more ?: 0
                        outcomes[c.snap.request] = OfferOutcome.Collected(prior + free)
                        c.stillNeeded -= free
                        free = 0
                        return true
                    }
                    return false
                }
                ClaimantKind.PREEMPTOR -> {
                    val n = c.snap.amount
                    // the free units are enough: it is served as a seize would be, taking from no one
                    if (free >= n) {
                        free -= n
                        outcomes[c.snap.request] = OfferOutcome.Served(emptyMap())
                        preemption.remove(c)
                        return true
                    }
                    // nothing is taken or reserved beyond the capacity the resource is heading to
                    if (n > s.reachableCapacity) return false
                    val short = n - free
                    val rule = c.snap.preemptRule!!
                    if (rule == InterruptRule.FINISH) {
                        val lent = takeFromHolders(c, short, inService = false, commit = false)
                        if (lent != null) {
                            takeFromHolders(c, short, inService = false, commit = true)
                            free = 0
                            outcomes[c.snap.request] = OfferOutcome.Served(lent)
                            preemption.remove(c)
                            holders.add(Holder(newId--, c.entity, c.priority, s.time, n, 0))
                            return true
                        }
                        if (takeFromHolders(c, short, inService = true, commit = false) != null) {
                            outcomes[c.snap.request] = OfferOutcome.Reserved(free)
                            free = 0
                            preemption.remove(c)
                            collecting[c.snap.request] = c.priority
                            return true
                        }
                        return false
                    }
                    // RESUME and RESTART: units in service only while no FINISH reservation as urgent or more collects
                    val blocked = collecting.any { (r, p) -> r.entity !== c.entity && p <= c.priority }
                    val lent = takeFromHolders(c, short, inService = !blocked, commit = false) ?: return false
                    takeFromHolders(c, short, inService = !blocked, commit = true)
                    free = 0
                    outcomes[c.snap.request] = OfferOutcome.Served(lent)
                    preemption.remove(c)
                    holders.add(Holder(newId--, c.entity, c.priority, s.time, n, 0))
                    return true
                }
            }
        }

        while (true) {
            queued.removeAll { q -> q.snap.request in outcomes && outcomes[q.snap.request] !is OfferOutcome.Collected }
            val claimants = preemption.toMutableList<Claimant>()
            if (free > 0) queued.firstOrNull { it.snap.amount <= free }?.let { claimants.add(it) }
            claimants.sortWith { a, b -> comesBefore(a, b) }
            if (claimants.none { tryServe(it) }) break
        }
        return outcomes
    }
}
