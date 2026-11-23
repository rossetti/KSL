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

import ksl.modeling.queue.Queue
import ksl.modeling.spatial.MovableResourcePool
import ksl.simulation.ModelElement

/**
 *  Determines the requests that will be allocated from the specified amount available from the
 *  request queue. The total amount requested by the returned requests must not exceed the
 *  amount available.
 *
 *  The list returned is an order. When units come free, and when a request arrives, the first request
 *  of the list that can be served now is served first. A rule that filters the queue returns the requests
 *  in the queue's list order, which for a LIFO or random queue is not its discipline; a rule that
 *  wants another order sorts its list. When units of a resource that belongs to pools come free, the
 *  rule's choices for the resource come before its choices for each of those pools. The queue still holds
 *  requests already woken, whose units are promised and no longer counted as available; they are passed
 *  over when the list is used.
 */
fun interface RequestSelectionRuleIfc {

    /**
     *  Determines the requests that can be allocated from the specified amount available from the
     *  request queue. The total amount requested by the returned requests must not exceed the
     *  amount available. The list's order is the order in which they are served.
     *
     * @param resource the resource to select requests for
     * @param requestQ the queue to search
     * @return the requests that were selected
     */
    @Suppress("unused")
    fun selectRequests(resource: ResourceIfc, requestQ: RequestQ): List<ProcessModel.Entity.Request>

}


/**
 *  If the user supplies a request selection rule then the default
 *  queue discipline will order the requests for finding the next request from the queue based
 *  on the rule.  The rule may cause the ordering of requests to be presented in a different order
 *  than strictly implied by the queue discipline.  For example, a later arriving request
 *  may "jump" forward in the queue if the rule selects it.
 *
 * @param parent containing model element
 * @param name the name of the queue
 * @param discipline the queue discipline for ordering the queue
 */
class RequestQ @JvmOverloads constructor(
    parent: ModelElement,
    name: String? = null,
    discipline: Discipline = Discipline.FIFO
) : Queue<ProcessModel.Entity.Request>(parent, name, discipline) {

    /**
     *  A rule to select requests. Null by default. The default behavior is to use
     *  the selectRequestsByResource() function. If a rule is supplied, then
     *  the selectRequestsByResource() function will not be used.
     */
    var requestSelectionRule: RequestSelectionRuleIfc? = null

    override fun remove(qObj: ProcessModel.Entity.Request, waitStats: Boolean): Boolean {
        val removed = super.remove(qObj, waitStats)
        if (removed) {
            qObj.clearResumePending()
            qObj.reservation?.let { (qObj.resource as Resource).dropReservation(qObj) }
            qObj.clearPromises()
        }
        return removed
    }

    override fun clear() {
        for (request in immutableList) {
            request.clearResumePending()
            request.reservation?.let { (request.resource as Resource).dropReservation(request) }
            request.clearPromises()
        }
        super.clear()
    }

    /**
     *  Returns the number of requests targeting the supplied resource
     *  that are waiting in the queue.
     * @param resource the resource to check
     */
    @Suppress("unused")
    fun countRequestsFor(resource: Resource): Int {
        var count = 0
        for (request in myList) {
            if (request.resource == resource) {
                count++
            }
        }
        return count
    }

    /**
     *  Returns the number of requests targeting the supplied resource pool
     *  that are waiting in the queue.
     * @param pool the resource pool to check
     */
    @Suppress("unused")
    fun countRequestsFor(pool: ResourcePool): Int {
        var count = 0
        for (request in myList) {
            if (request.resource == pool) {
                count++
            }
        }
        return count
    }

    /**
     *  Returns the number of requests targeting the supplied movable resource pool
     *  that are waiting in the queue.
     * @param pool the movable resource pool to check
     */
    @Suppress("unused")
    fun countRequestsFor(pool: MovableResourcePool): Int {
        var count = 0
        for (request in myList) {
            if (request.resource == pool) {
                count++
            }
        }
        return count
    }

    /**
     *  True when a request in this queue is waiting for [resource], either directly or through a pool that
     *  [resource] belongs to. Check this before unregistering this queue from [resource]
     *  (`Resource.unregisterCapacityChangeQueue`), which refuses while it is true.
     *  @param resource the resource to check
     */
    fun hasRequestsWaitingFor(resource: Resource): Boolean = myList.any { wants(it, resource) }

    /** True when [request] wants units of [resource], directly or through a pool that [resource] belongs to. */
    internal fun wants(request: ProcessModel.Entity.Request, resource: Resource): Boolean {
        val target = request.resource
        return target === resource || (target is AbstractResourcePool<*> && target.isMember(resource))
    }

    /** Removes the request from the queue and tells the associated entity to terminate its process.  The process
     *  that was suspended because the entity's request was placed in the queue is immediately terminated.
     *
     *  Units the request had been promised, because a release woke it and its entity had not yet resumed, go
     *  back to the resource before the process is terminated, so the termination's hooks see them there (a
     *  capacity decrease waiting for them has taken them). After the termination they are offered to whoever
     *  else is waiting for them, as when a waiting process is terminated. So are the
     *  free units of what it requested: when the queue has a request selection rule, removing the request the
     *  rule had chosen can let it choose another, which is served at once rather than at the next release.
     *
     * @param request the request to remove from the queue
     * @param waitStats if true the waiting time statistics are collected on the usage of the queue.
     * The default is false.
     * @param afterTermination a function to invoke after the process is successfully terminated
     */
    fun removeAndTerminate(
        request: ProcessModel.Entity.Request,
        waitStats: Boolean = false,
        afterTermination: ((entity: ProcessModel.Entity) -> Unit)? = null
    ) {
        val withdrawn = request.promises.toMap()
        val offerOn = LinkedHashSet<Resource>(withdrawn.keys)
        offerOn.addAll(membersOf(request.resource))
        remove(request, waitStats)
        // the promised units are handed on before the entity's termination runs its hooks, so a hook sees the
        // resource with them back; the offer comes last, so a request a hook makes is offered the units too
        for ((member, amount) in withdrawn) member.unitsWithdrawnFromPromise(amount)
        request.entity.terminateProcess(afterTermination)
        offerOnward(offerOn, "removed_and_terminated_request_id=${request.id}")
        offerGuidedPool(request.resource)
    }

    /** The resources whose units a request for [resource] can take: the resource, or the members of its pool. */
    private fun membersOf(resource: ResourceIfc): List<Resource> = when (resource) {
        is ResourcePool -> resource.resources.filterIsInstance<Resource>()
        is MovableResourcePool -> resource.resources.filterIsInstance<Resource>()
        is Resource -> listOf(resource)
        // a guided transporter pool keeps its own accounting and wakes its own queue
        else -> emptyList()
    }

    /** Offers the available units of what a request for [resource] can take, as when a request leaves. */
    internal fun offerFreeUnitsOf(resource: ResourceIfc, detail: String) {
        offerOnward(membersOf(resource), detail)
    }

    /**
     *  Called when a request arrives, or is moved in, and is not served at once although units are free: under a
     *  request selection rule whose choice depends on what is queued, the arrival can make the rule choose requests
     *  already waiting, and those are offered the free units now rather than at the next release.
     */
    internal fun offerFreeUnitsUnderRule(resource: ResourceIfc, detail: String) {
        if (requestSelectionRule == null) return
        offerFreeUnitsOf(resource, detail)
    }

    /** Offers the available units of [members] to the requests waiting for them, unless the run has ended. */
    private fun offerOnward(members: Collection<Resource>, detail: String) {
        if (members.isEmpty() || executive.isEnded) return
        for (member in members) {
            member.offerFreedUnits(ProcessModel.RELEASE_PRIORITY, ResumeSource.REQUEST_Q_RESOURCE_RELEASE, detail)
        }
    }

    /**
     *  Removes and terminates all the requests waiting in the queue
     *
     * @param waitStats if true the waiting time statistics are collected on the usage of the queue.
     * The default is false.
     * @param afterTermination a function to invoke after the process is successfully terminated
     */
    @Suppress("unused")
    fun removeAllAndTerminate(
        waitStats: Boolean = false,
        afterTermination: ((entity: ProcessModel.Entity) -> Unit)? = null
    ) {
        while (isNotEmpty) {
            val request = peekNext()
            removeAndTerminate(request!!, waitStats, afterTermination)
        }
    }

    /**
     *  Returns a list of requests waiting for the specified resource that have requested
     *  a number of units of the resource that is less than or equal to the number of units
     *  available after accounting for requests that have already been selected for resumption.
     *  Thus, any request in the returned list could be satisfied at the current time.
     *
     *  @param resource the resource to check
     *  @return the list with the items ordered by the queue discipline. If no items are
     *  selected, then the returned list will be empty.
     */
    fun filterRequestsByResource(resource: ResourceIfc): List<ProcessModel.Entity.Request> {
        val amountAvailable = effectiveAvailableFor(resource)
        if (amountAvailable <= 0) {
            return emptyList()
        }
        return filteredOrderedList { servesRequest(it, resource, amountAvailable) }
    }

    /**
     *  Returns a list of requests that can be allocated at the current time based on the amount
     *  available in the resource. Each request that can be fully allocated until the amount available is
     *  used up is returned. The list is ordered in the same order as the RequestQ.  No partial filling is
     *  permitted.
     *
     *  The search will select those requests that can be fully filled. This may cause skipping over of waiting requests.
     *  For example, suppose there are only 2 units left to allocate and the current
     *  request needs 3 units. The search will skip the current request and check the remaining
     *  requests until it finds the next request that needs 2 or fewer units. The search keeps
     *  selecting until all waiting requests are checked or until all available capacity is used.
     *
     *  @param resource the resource to check
     *  @return the list with the items ordered by the queue discipline. If no items are
     *  selected, then the returned list will be empty.
     */
    fun selectRequestsByResource(resource: ResourceIfc): List<ProcessModel.Entity.Request> {
        val amountAvailable = effectiveAvailableFor(resource)
        if (amountAvailable <= 0) {
            return emptyList()
        }
        val filtered = filterRequestsByResource(resource)
        // filtered holds items that are waiting for the resource and their amount requested
        // is less than or equal to the number of available units
        // no need to select if there are no requests waiting for the resource
        // no need to select if there is only one waiting for the resource
        if (filtered.isEmpty() || filtered.size == 1) {
            return filtered
        }
        // process the filtered requests in order until all the available units that can be
        // provided are used. This may cause skipping over of waiting requests.
        // For example, suppose there are is only 1 unit left to allocate and the current
        // request needs 2 units. The search will skip the current request and check the remaining
        // requests until it finds a request that can be fully filled or none are found.
        return selectWithinAvailable(filtered, amountAvailable)
    }

    /**
     *   Returns the next request that can be fully satisfied by the resource. If no requests
     *   can be satisfied, then null is returned. The selection is based on the
     *   queue discipline, or, when the queue has a request selection rule, on the rule, as when units are freed.
     *   @param resource the resource to check
     *   @return the selected request or null
     */
    fun nextRequestForResource(resource: ResourceIfc): ProcessModel.Entity.Request? {
        val amountAvailable = effectiveAvailableFor(resource)
        if (amountAvailable <= 0) {
            return null
        }
        // a request selection rule decides who is next here as it does when units are freed, so that a request it
        // chooses is not left waiting beside free units behind one it never chooses
        val rule = requestSelectionRule ?: return firstMatching { servesRequest(it, resource, amountAvailable) }
        val candidates = rule.selectRequests(resource, this).filter { servesRequest(it, resource, amountAvailable) }
        return selectWithinAvailable(candidates, amountAvailable).firstOrNull()
    }

    /**
     *  For a request of this queue resuming with nothing promised (moved while woken, or its promise broke): whether
     *  the queue's request selection rule would choose it now, the first of the rule's list that can be served,
     *  counting the request itself as waiting. With no rule, true: the discipline was applied when it was woken.
     */
    internal fun wouldChoose(request: ProcessModel.Entity.Request): Boolean {
        val rule = requestSelectionRule ?: return true
        val target = request.resource
        val available = effectiveAvailableFor(target)
        val first = rule.selectRequests(target, this).firstOrNull {
            it === request || servesRequest(it, target, available)
        }
        return first === request
    }

    private fun servesRequest(
        request: ProcessModel.Entity.Request,
        resource: ResourceIfc,
        amountAvailable: Int
    ): Boolean = request.resource == resource &&
            !request.isPassedOver &&
            request.amountRequested <= amountAvailable && poolCanServe(request)

    /**
     *  For a pool's request: whether the pool can supply it now by the selection rule its seize named, as when units
     *  are freed (`nextRequestServableBy`). A pool's free units may be enough in number yet not for that rule, and a
     *  request its rule cannot serve must not be taken as next, ahead of one that could be served.
     */
    private fun poolCanServe(request: ProcessModel.Entity.Request): Boolean {
        val target = request.resource
        return when {
            request is ProcessModel.Entity.ResourcePoolRequest && target is ResourcePool ->
                target.canAllocate(request.selectionRule ?: target.defaultResourceSelectionRule, request.amountRequested)
            request is ProcessModel.Entity.MovableResourcePoolRequest && target is MovableResourcePool ->
                target.canAllocate(request.selectionRule ?: target.defaultMovableResourceSelectionRule)
            else -> true
        }
    }

    private fun pendingResumeAmountFor(resource: ResourceIfc): Int {
        var sum = 0
        for (request in myList) {
            if (request.resource == resource && request.resumePending) {
                sum += request.amountRequested
            }
        }
        return sum
    }

    internal fun effectiveAvailableFor(resource: ResourceIfc): Int {
        // a resource's or pool's available units already exclude what has been promised to woken
        // requests, in this queue or any other; only a guided transporter pool still counts its own
        return if (resource is Resource || resource is ResourcePool || resource is MovableResourcePool) {
            resource.numAvailableUnits
        } else {
            resource.numAvailableUnits - pendingResumeAmountFor(resource)
        }
    }

    /**
     * Promises a woken request the units it will take when it resumes, so that nothing else takes them
     * first. A request for a guided transporter pool is not promised anything; that pool counts its own.
     *
     * @return false when the units are no longer there to promise
     */
    internal fun promiseUnits(request: ProcessModel.Entity.Request, target: ResourceIfc): Boolean = when {
        request is ProcessModel.Entity.ResourceRequest && target is Resource -> {
            if (target.numAvailableUnits >= request.amountRequested) {
                request.promise(target, request.amountRequested); true
            } else false
        }
        request is ProcessModel.Entity.ResourcePoolRequest && target is ResourcePool -> target.promiseMembers(request)
        request is ProcessModel.Entity.MovableResourcePoolRequest && target is MovableResourcePool ->
            target.promiseMember(request)
        else -> true
    }

    private fun selectWithinAvailable(
        candidates: Iterable<ProcessModel.Entity.Request>,
        amountAvailable: Int
    ): List<ProcessModel.Entity.Request> {
        if (amountAvailable <= 0) {
            return emptyList()
        }
        val list = mutableListOf<ProcessModel.Entity.Request>()
        var remaining = amountAvailable
        for (request in candidates) {
            if (request.isPassedOver) continue
            if (request.amountRequested <= remaining) {
                list.add(request)
                remaining -= request.amountRequested
                if (remaining == 0) {
                    break
                }
            }
        }
        return list
    }

    /**
     *  True when [first] is ahead of [second] in this queue's discipline: FIFO, LIFO, ranked, the random queue's
     *  kept draw, or a discipline of the model's own. Both must be in this queue.
     */
    internal fun precedes(first: ProcessModel.Entity.Request, second: ProcessModel.Entity.Request): Boolean =
        firstMatching { it === first || it === second } === first

    /**
     *  The first request in this queue, in its discipline, that units freed on [member] can serve now: one
     *  that names [member] and fits in its available units, or one that names a pool [member] belongs to
     *  and that the pool can supply. Requests already woken are skipped, and so are requests that do not
     *  fit, as when units are freed today. With a request selection rule it is the first of the rule's list
     *  that can be served, as for a seize arriving (`nextRequestForResource`), so an arrival and a release
     *  choose alike.
     */
    internal fun nextRequestServableBy(
        member: Resource,
        skip: (ProcessModel.Entity.Request) -> Boolean = { false }
    ): ProcessModel.Entity.Request? {
        val available = member.numAvailableUnits
        val rule = requestSelectionRule
        fun servable(request: ProcessModel.Entity.Request): Boolean =
            if (request.isPassedOver || skip(request)) {
                false
            } else {
                val target = request.resource
                when {
                    target === member -> request.amountRequested <= available
                    request is ProcessModel.Entity.ResourcePoolRequest && target is ResourcePool ->
                        target.isMember(member) && target.canAllocate(
                            request.selectionRule ?: target.defaultResourceSelectionRule, request.amountRequested
                        )
                    request is ProcessModel.Entity.MovableResourcePoolRequest && target is MovableResourcePool ->
                        target.isMember(member) &&
                                target.canAllocate(request.selectionRule ?: target.defaultMovableResourceSelectionRule)
                    else -> false
                }
            }
        // with no rule, in the queue's own discipline order, which for a LIFO or random queue is not the list order
        if (rule == null) return firstMatching { servable(it) }
        // with a rule, in the order of the rule's list, as an arriving seize takes the first of it: the rule's
        // choices for this member, then its choices for each pool the member belongs to
        val chosen = LinkedHashSet<ProcessModel.Entity.Request>(rule.selectRequests(member, this))
        for (request in this) {
            val target = request.resource
            if (target is AbstractResourcePool<*> && target.isMember(member)) chosen.addAll(rule.selectRequests(target, this))
        }
        return chosen.firstOrNull { it.queue === this && servable(it) }
    }

    /**
     *  Removes a request whose entity will not resume to take what it was promised, because its process
     *  was terminated while it waited, and offers the promised units to whoever else is waiting for them.
     *  Without the offer they would sit free until the next release. The free units of what the request
     *  asked for are offered too: under a request selection rule, removing the request the rule had
     *  chosen can let it choose another.
     */
    internal fun removeAbandoned(request: ProcessModel.Entity.Request, offer: Boolean = true): Boolean {
        val withdrawn = request.promises.toMap()
        val offerOn = LinkedHashSet<Resource>(withdrawn.keys)
        offerOn.addAll(membersOf(request.resource))
        if (!remove(request, false)) return false
        for ((member, amount) in withdrawn) member.unitsWithdrawnFromPromise(amount)
        // a wait releasing all its registrations at a termination offers once they are all released
        if (!offer) return true
        offerOnward(offerOn, "request_abandoned_request_id=${request.id}")
        offerGuidedPool(request.resource)
        return true
    }

    /**
     *  A guided transporter pool promises nothing at a wake: it counts a woken request's transporter as taken
     *  for as long as the request is in its queue. When such a request leaves without resuming, the transporter
     *  is free in the pool's count and nothing has said so; the pool looks at its waiting requests again.
     */
    private fun offerGuidedPool(target: ResourceIfc) {
        if (target is ksl.modeling.guidedpath.GuidedTransporterPoolWithQ && !executive.isEnded) {
            target.transporterBecameDispatchable()
        }
    }

    /**
     *  As a request is moved out of this queue: the units it was promised, [withdrawn], go first to a pending
     *  capacity change. Done before the new target decides whether it can serve the request, so a unit is handed
     *  on before anyone may promise it again, and is never both promised and given to the change.
     */
    internal fun handOnWithdrawn(withdrawn: Map<Resource, Int>) {
        for ((member, amount) in withdrawn) member.unitsWithdrawnFromPromise(amount)
    }

    /**
     *  After a request has been moved out of this queue to another target, and its withdrawn units were handed on
     *  (`handOnWithdrawn`): the free units of what it left, [oldTarget], and of the members it was promised by,
     *  [withdrawn], are offered to the requests still waiting for them, as when a request leaves. The moved
     *  request no longer asks for them, so it is not woken by them.
     */
    internal fun afterMovedAway(withdrawn: Map<Resource, Int>, oldTarget: ResourceIfc, detail: String) {
        val offerOn = LinkedHashSet<Resource>(withdrawn.keys)
        offerOn.addAll(membersOf(oldTarget))
        offerOnward(offerOn, detail)
    }

    /**
     *  Wakes [request]: promises it the units it will take and schedules its resumption.
     *
     *  @return false when the units could not be promised, in which case it is not woken
     */
    internal fun wake(
        request: ProcessModel.Entity.Request,
        freed: Resource,
        resumePriority: Int,
        resumeSource: ResumeSource,
        resumeDetail: String?
    ): Boolean {
        if (!promiseUnits(request, request.resource)) return false
        val detail = "queue=$name, resource=${request.resource.name}, freed=${freed.name}, request_id=${request.id}, " +
                "entity_id=${request.entity.id}, amount=${request.amountRequested}, $resumeDetail"
        resumeWoken(request, resumePriority, resumeSource, detail)
        return true
    }

    /**
     *  Resumes a request that has just been promised its units: the one step every wake of a waiting
     *  request ends with. The request is marked as having a resume pending, so nothing wakes it twice.
     *  A request of an untimed seize then has its entity's resume scheduled. A request of a wait for
     *  the first of several things has its wait claimed instead, which withdraws the wait's other
     *  registrations, cancels its timer and schedules the resume.
     */
    internal fun resumeWoken(
        request: ProcessModel.Entity.Request,
        resumePriority: Int,
        resumeSource: ResumeSource,
        resumeDetail: String?
    ) {
        request.markResumePending(resumeSource, resumeDetail)
        val registration = request.registration
        if (registration == null) {
            request.entity.scheduleResumeProcess(0.0, resumePriority, resumeSource, resumeDetail)
            return
        }
        // A request whose wait has ended has already left the queue, so its wait is still open.
        val claimed = registration.claimWait(null, resumeSource, resumePriority)
        check(claimed) { "$name: request ${request.id} was woken for a wait that had already ended." }
    }

    /**
     *  Removes a request that leaves without being served, because its wait ended in another way: a
     *  time limit ran out, or another thing it waited for came first. Its wait is recorded when
     *  [recordWait] is true and the queue records waits. Such a request was never woken, so it holds
     *  no promise and has no resume pending.
     *
     *  @return true if the request was in the queue
     */
    internal fun removeLeaving(request: ProcessModel.Entity.Request, recordWait: Boolean): Boolean {
        if (recordWait && waitTimeStatOption && contains(request)) request.registration?.let { it.group.noteRecorded(it.trigger, name) }
        return remove(request, recordWait && waitTimeStatOption)
    }

    /**
     *  The purpose of this function is to resume requests that have been selected for allocation.
     *  This function is called when a resource or resource pool has some units released. The requests
     *  that will be resumed are based on the available units in the resource and the order
     *  in which they are waiting in the request queue.
     *  This internal function is called from the following locations:
     *
     *  1. release(Allocation) function
     *  2. release(ResourcePoolAllocation) function
     *  3. Resource.notifyWaitingRequestsOfCapacityIncrease()
     *
     *  @param resource the resource that may have waiting requests
     *  @param resumePriority the priority to resume the requests
     *  @return the total amount to be allocated from the resource because of the processing.
     */
    internal fun processWaitingRequestsForResource(
        resource: ResourceIfc,
        resumePriority: Int,
        resumeSource: ResumeSource,
        resumeDetail: String? = null
    ): Int {
        val amountAvailable = effectiveAvailableFor(resource)
        if (amountAvailable <= 0) {
            return 0
        }
        val candidates = requestSelectionRule?.selectRequests(resource, this)
            ?.filter { it.resource == resource }
            ?: filterRequestsByResource(resource)
        val selected = selectWithinAvailable(candidates, amountAvailable)
        if (selected.isEmpty()){
            return 0
        }
        //TODO need to evaluate effect of capacity change on this
        // check numAvailableUnits calculation for pools and under capacity change conditions
        var sum = 0
        for (request in selected) {
            val detail = "queue=$name, resource=${resource.name}, request_id=${request.id}, " +
                    "entity_id=${request.entity.id}, amount=${request.amountRequested}, $resumeDetail"
            if (!promiseUnits(request, resource)) continue
            resumeWoken(request, resumePriority, resumeSource, detail)
            sum = sum + request.amountRequested
        }
        return sum
    }

}
