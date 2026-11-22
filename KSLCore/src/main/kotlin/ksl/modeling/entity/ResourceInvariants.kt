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
 *  Checks the accounting of every resource of a model, and the waiting requests of every blocking queue, after
 *  each event, when switched on by the system property `ksl.checkInvariants=true`; off, it costs nothing. A broken invariant throws, and the executive
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
 *    can still come to it, by a release or a withdrawn promise; under WAIT, units promised to a reservation
 *    still collecting its units do not count, since the decrease takes them back as it starts;
 *  - each reservation's request waits in a queue, reserved, and holds what the reservation has promised;
 *  - every wait listed on an allocation's triggers is still open;
 *  - a holder's delay is held only by allocations with units missing, has no event on the calendar while
 *    held, and is never held by one while it reports an interruption instead;
 *  - no request waits, un-woken, in one of its queues beside units it could take. A woken request is
 *    already on its way; a failed or inactive resource can serve no one; and a request that the queue's
 *    selection rule holds back, or that does not fit in the units available, is not one it could serve;
 *  - every request in one of its queues for a wait for the first of several things belongs to a wait that is
 *    still open, or is the one that ended it and has yet to resume.
 *  - no waiting preemptor is passed over by a request the offer of freed units wakes behind it in its queue, no
 *    more urgent, when that request's units and those of the holders the preemptor outranks would have served it
 *    (judged at the wake, only for a working resource on shift, a preemptor not under FINISH, and no reservation
 *    as urgent collecting).
 *
 *  For each blocking queue, it checks the last of these for the requests waiting for its items.
 *
 *  For each blocking queue's requests, each signal's waiting entities, and each mailbox's receivers (as they
 *  join), it checks that they wait in the order their waits began, in a FIFO queue, or among equal priorities in a
 *  ranked one; for a wait for the first of several things, that is when the wait began, even after it waits
 *  again. And each such wait has its time recorded at most once in each queue it waits in.
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
            var blockingQueues: List<BlockingQueue<*>> = emptyList()
            var signals: List<Signal> = emptyList()

            override fun onChange(newValue: KSLEvent<*>?) {
                when (executive.status) {
                    Executive.Status.INITIALIZED -> {
                        val elements = model.getModelElements()
                        resources = elements.filterIsInstance<Resource>()
                        blockingQueues = elements.filterIsInstance<BlockingQueue<*>>()
                        signals = elements.filterIsInstance<Signal>()
                    }
                    Executive.Status.AFTER_EVENT -> {
                        for (resource in resources) check(resource)
                        for (queue in blockingQueues) check(queue)
                        for (signal in signals) check(signal)
                    }
                    else -> {}
                }
            }
        })
    }

    private fun violation(resource: Resource, message: String): Nothing =
        throw IllegalStateException("Invariant broken at time ${resource.time} on resource ${resource.name}: $message")

    private fun violation(queue: BlockingQueue<*>, message: String): Nothing =
        throw IllegalStateException("Invariant broken at time ${queue.time} on blocking queue ${queue.name}: $message")

    /**
     *  The message for a request whose wait has ended though the request still waits; null when its wait is
     *  open, when it is the one that ended the wait and has yet to resume, or when it is not part of such a wait.
     *  The other requests of an ended wait leave their queues as it ends.
     */
    private fun endedWait(registration: Registration?, requestId: Long, entityId: Long, queueName: String): String? {
        if (registration == null) return null
        val group = registration.group
        if (!registration.isReleased && (!group.claimed || registration === group.winnerRegistration || registration.isHeld)) return null
        return "request $requestId of entity $entityId waits in $queueName for a wait that has already ended"
    }

    /** Checks every resource, blocking queue and signal of [model]. */
    fun check(model: Model) {
        for (element in model.getModelElements()) {
            if (element is Resource) check(element)
            if (element is BlockingQueue<*>) check(element)
            if (element is Signal) check(element)
        }
    }

    /**
     *  Checks that every request waiting in [queue] for its items belongs to a wait that is still open, or is the
     *  one that ended it and has yet to resume, and that the requests wait in the order their waits began.
     */
    fun check(queue: BlockingQueue<*>) {
        for (request in queue.waitingRequests) {
            endedWait(request.registration, request.id, request.receiver.id, queue.waitingRequests.name)
                ?.let { violation(queue, it) }
        }
        val requests = queue.waitingRequests
        waitOrderBroken(if (requests.disciplineSwitchedInReplication) null else requests.currentDiscipline, queue.requestWaitOrder())
            ?.let { violation(queue, "its requests $it") }
    }

    /** Checks that the entities waiting for [signal] wait in the order their waits began. */
    fun check(signal: Signal) {
        waitOrderBroken(signal.holdDiscipline, signal.waitOrder())?.let {
            throw IllegalStateException("Invariant broken at time ${signal.time} on signal ${signal.name}: its waiting entities $it")
        }
    }

    /**
     *  Null when the waiters, given in queue order as their priority and the time their wait began, are in the
     *  order of a FIFO queue (by the time their waits began) or of a ranked one (by that time among equal
     *  priorities); else what is out of order. LIFO and random queues are not judged, nor a queue whose discipline
     *  was switched during the replication (null).
     */
    private fun waitOrderBroken(discipline: ksl.modeling.queue.Queue.Discipline?, order: List<Pair<Int, Double>>): String? {
        val ranked = when (discipline) {
            ksl.modeling.queue.Queue.Discipline.FIFO -> false
            ksl.modeling.queue.Queue.Discipline.RANKED -> true
            else -> return null
        }
        val latest = mutableMapOf<Int, Double>()
        for ((index, entry) in order.withIndex()) {
            val key = if (ranked) entry.first else 0
            val before = latest[key]
            if (before != null && entry.second < before) {
                return "are out of the order their waits began: the one at position $index began at ${entry.second}, " +
                        "behind one that began at $before"
            }
            latest[key] = maxOf(before ?: entry.second, entry.second)
        }
        return null
    }

    /**
     *  When the checks are on, called by the offer of freed units just after it wakes a request: no waiting preemptor
     *  is passed over by it. Judged at the wake, with the priorities of that moment; a later step of the same event
     *  may change them.
     */
    fun checkAfterWake(resource: Resource) {
        if (!enabled) return
        resource.waitingPreemptorPassedOver()?.let { violation(resource, it) }
    }

    /** Checks [resource]; the message names the resource and what is out of balance. */
    fun check(resource: Resource) {
        val allocations = resource.allocations()
        val allocated = allocations.sumOf { it.amount }
        val lent = resource.loans.filter { it.lender != null }.sumOf { it.amount }
        if (resource.numBusy != allocated - lent) {
            violation(resource, "${resource.numBusy} units busy, but $allocated are allocated and $lent lent")
        }
        val highest = resource.highestCapacity
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
                if (registration.group.claimed && !registration.isHeld) violation(resource, "allocation ${a.id} still lists a wait that has already ended")
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
            for (request in queue) {
                promised += request.promises[resource] ?: 0
                endedWait(request.registration, request.id, request.entity.id, queue.name)?.let { violation(resource, it) }
            }
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
        if (resource.capacityChangeRule == CapacityChangeRule.WAIT && need > 0) {
            val collecting = resource.reservations.filter { !it.isComplete }.sumOf { it.request.promises[resource] ?: 0 }
            if (need > resource.numBusy + resource.myPromisedUnits - collecting) {
                violation(
                    resource, "a pending capacity decrease needs $need units, but only ${resource.numBusy} are busy and " +
                            "${resource.myPromisedUnits - collecting} promised outside reservations still collecting theirs"
                )
            }
        }
        for (reservation in resource.reservations) {
            val request = reservation.request
            if (request.queue == null) violation(resource, "a reservation's request ${request.id} is in no queue")
            if (request.reservation !== reservation) violation(resource, "the request ${request.id} of a reservation does not carry it")
            val held = request.promises[resource] ?: 0
            val expected = if (reservation.isComplete) request.amountRequested else request.amountRequested - reservation.stillNeeded
            if (held != expected) violation(resource, "the reservation of request ${request.id} holds $held units, not $expected")
        }
        // no request waits, un-woken, beside units it could take
        if (!resource.isFailed && !resource.isInactive && resource.numAvailableUnits > 0) {
            for (queue in resource.registeredQueues) {
                val stranded = queue.nextRequestServableBy(resource) ?: continue
                violation(
                    resource, "request ${stranded.id} of entity ${stranded.entity.id} waits in ${queue.name} for " +
                            "${stranded.amountRequested} units, un-woken, while ${resource.numAvailableUnits} are available"
                )
            }
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
