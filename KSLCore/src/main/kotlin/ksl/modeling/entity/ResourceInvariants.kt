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

import ksl.simulation.Executive
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.utilities.observers.ObserverIfc

/**
 *  Checks the accounting of every resource of a model after each event, when switched on by the system
 *  property `ksl.checkInvariants=true`; off, it costs nothing. A broken invariant throws, and the executive
 *  reports it with the event it followed. It is a diagnostic for models and for the library's own tests,
 *  not something a model relies on.
 *
 *  For each resource it checks:
 *  - the units busy are the units allocated less those lent to a borrower, and never more than the highest
 *    capacity the resource has had in the replication. A capacity decrease under the IGNORE rule leaves work
 *    in service running above the new capacity, with or without a change still pending, so the current
 *    capacity is not a bound, but units are allocated only when available, so the highest one is;
 *  - each allocation's units missing by loan are its loans out, and never exceed its amount;
 *  - no loan outlives its borrower, and a lender, if any, still holds its allocation;
 *  - the units promised to woken requests are the promises the requests in its queues hold;
 *  - a pending capacity decrease never waits for more units than are busy or promised, the only ones that
 *    can still come to it, by a release or a withdrawn promise;
 *  - each reservation's request waits in a queue, reserved, and holds what the reservation has promised;
 *  - every wait listed on an allocation's triggers is still open;
 *  - a holder's delay is held only by allocations with units missing, has no event on the calendar while
 *    held, and is never held by one while it reports an interruption instead.
 */
internal object ResourceInvariants {

    /** Whether models made from now on check their resources after every event. */
    @JvmStatic
    var enabled: Boolean = System.getProperty("ksl.checkInvariants") == "true"

    /** Attaches the check to [model]'s executive when [enabled]. */
    fun attachIfEnabled(model: Model, executive: Executive) {
        if (!enabled) return
        executive.attachObserver(object : ObserverIfc<KSLEvent<*>?> {
            // no element can be added while the model runs, so the resources are found once per replication
            // rather than by walking the model after every event
            var resources: List<Resource> = emptyList()

            override fun onChange(newValue: KSLEvent<*>?) {
                when (executive.status) {
                    Executive.Status.INITIALIZED -> resources = model.getModelElements().filterIsInstance<Resource>()
                    Executive.Status.AFTER_EVENT -> for (resource in resources) check(resource)
                    else -> {}
                }
            }
        })
    }

    /** The highest capacity each resource has had in its current replication, as the checks have seen it. */
    private val myHighestCapacity: MutableMap<Resource, Pair<Int, Int>> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap())

    private fun highestCapacity(resource: Resource): Int {
        val replication = resource.model.currentReplicationNumber
        val seen = myHighestCapacity[resource]
        val highest = if (seen == null || seen.first != replication) resource.capacity else maxOf(seen.second, resource.capacity)
        myHighestCapacity[resource] = replication to highest
        return highest
    }

    private fun violation(resource: Resource, message: String): Nothing =
        throw IllegalStateException("Invariant broken at time ${resource.time} on resource ${resource.name}: $message")

    /** Checks every resource of [model]. */
    fun check(model: Model) {
        for (element in model.getModelElements()) {
            if (element is Resource) check(element)
        }
    }

    /** Checks [resource]; the message names the resource and what is out of balance. */
    fun check(resource: Resource) {
        val allocations = resource.allocations()
        val allocated = allocations.sumOf { it.amount }
        val lent = resource.loans.filter { it.lender != null }.sumOf { it.amount }
        if (resource.numBusy != allocated - lent) {
            violation(resource, "${resource.numBusy} units busy, but $allocated are allocated and $lent lent")
        }
        val highest = highestCapacity(resource)
        if (resource.numBusy > highest) {
            violation(resource, "${resource.numBusy} units busy exceeds the highest capacity $highest of the replication")
        }
        for (a in allocations) {
            val out = resource.loans.filter { it.lender === a }.sumOf { it.amount }
            if (a.missingByLoan != out) violation(resource, "allocation ${a.id} has ${a.missingByLoan} units missing by loan, but $out are lent")
            if (a.missingUnits > a.amount || a.missingByFailure < 0 || a.missingByLoan < 0) {
                violation(resource, "allocation ${a.id} has ${a.missingUnits} units missing of ${a.amount}")
            }
            for (registration in a.myTriggerRegistrations) {
                if (registration.group.claimed) violation(resource, "allocation ${a.id} still lists a wait that has already ended")
            }
            checkHolder(resource, a)
        }
        for (loan in resource.loans) {
            if (!loan.borrower.isAllocated) violation(resource, "a loan to the released allocation ${loan.borrower.id} is still recorded")
            val lender = loan.lender
            if (lender != null && !lender.isAllocated) violation(resource, "a loan from the released allocation ${lender.id} is still live")
        }
        // the promises of woken requests, wherever they wait
        var promised = 0
        for (queue in resource.registeredQueues) {
            for (request in queue) promised += request.promises[resource] ?: 0
        }
        if (promised != resource.myPromisedUnits) {
            violation(resource, "${resource.myPromisedUnits} units are promised, but the requests in its queues hold $promised")
        }
        val need = resource.pendingChangeAmountNeeded
        if (need > resource.numBusy + resource.myPromisedUnits) {
            violation(
                resource, "a pending capacity change needs $need units, but only ${resource.numBusy} are busy and " +
                        "${resource.myPromisedUnits} promised"
            )
        }
        for (reservation in resource.reservations) {
            val request = reservation.request
            if (request.queue == null) violation(resource, "a reservation's request ${request.id} is in no queue")
            if (request.reservation !== reservation) violation(resource, "the request ${request.id} of a reservation does not carry it")
            val held = request.promises[resource] ?: 0
            val expected = if (reservation.isComplete) request.amountRequested else request.amountRequested - reservation.stillNeeded
            if (held != expected) violation(resource, "the reservation of request ${request.id} holds $held units, not $expected")
        }
    }

    private fun checkHolder(resource: Resource, a: Allocation) {
        val entity = a.myEntity
        if (entity.isInReportingDelay && entity.myFrozenBy.isNotEmpty()) {
            violation(resource, "entity ${entity.id} is in a delay that reports, yet held by ${entity.myFrozenBy.size} allocations")
        }
        for (by in entity.myFrozenBy) {
            if (by.missingUnits == 0) violation(resource, "entity ${entity.id}'s delay is held by allocation ${by.id}, which has no units missing")
        }
        if (entity.isDelayFrozen && entity.hasScheduledDelayEvent) {
            violation(resource, "entity ${entity.id}'s delay is held, yet its end is on the calendar")
        }
    }
}
