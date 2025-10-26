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
 */
fun interface RequestSelectionRuleIfc {

    /**
     *  Determines the requests that can be allocated from the specified amount available from the
     *  request queue. The total amount requested by the returned requests must not exceed the
     *  amount available.
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

    /** Removes the request from the queue and tells the associated entity to terminate its process.  The process
     *  that was suspended because the entity's request was placed in the queue is immediately terminated.
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
        remove(request, waitStats)
        request.entity.terminateProcess(afterTermination)
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
     *  available.  Thus, any request in the returned list could be satisfied at the current time.
     *
     *  @param resource the resource to check
     *  @return the list with the items ordered by the queue discipline. If no items are
     *  selected, then the returned list will be empty.
     */
    fun filterRequestsByResource(resource: ResourceIfc): List<ProcessModel.Entity.Request> {
        return filteredOrderedList { it.resource == resource && it.amountRequested <= resource.numAvailableUnits }
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
        if (isEmpty) {
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
        // 2 or more could be satisfied
        val list = mutableListOf<ProcessModel.Entity.Request>()
        // process the filtered requests in order until all the available units that can be
        // provided are used. This may cause skipping over of waiting requests.
        // For example, suppose there are is only 1 unit left to allocate and the current
        // request needs 2 units. The search will skip the current request and check the remaining
        // requests until it finds a request that can be fully filled or none are found.
        var amountAvailable = resource.numAvailableUnits
        for (request in filtered) {
            if (request.amountRequested <= amountAvailable) {
                list.add(request)
                amountAvailable = amountAvailable - request.amountRequested
                if (amountAvailable == 0){
                    break
                }
            }
        }
        return list
    }

    /**
     *   Returns the next request that can be fully satisfied by the resource. If no requests
     *   can be satisfied, then null is returned. The selection is based on the
     *   queue discipline.
     *   @param resource the resource to check
     *   @return the selected request or null
     */
    fun nextRequestForResource(resource: ResourceIfc): ProcessModel.Entity.Request? {
        if (isEmpty) {
            return null
        }
        // no need to select if there is only one waiting
        if (size == 1) {
            if (this[0].resource != resource) {
                return null
            }
            return if (resource.numAvailableUnits >= this[0].amountRequested) {
                this[0]
            } else {
                null
            }
        }
        val filtered = filterRequestsByResource(resource)
        return if (filtered.isEmpty()) {
            null
        } else {
            filtered.first()
        }
    }

    /**
     *  The purpose of this function is to resume requests that have been selected for allocation.
     *  This function is called when a resource or resource pool has some units released. The requests
     *  that will be resumed are based on the available units in the resource and the order
     *  in which they are waiting in the request queue.
     *  @param resource the resource that may have waiting requests
     *  @param resumePriority the priority to resume the requests
     *  @return the total amount to be allocated from the resource because of the processing.
     */
    internal fun processWaitingRequestsForResource(resource: ResourceIfc, resumePriority: Int): Int {
        if (resource.numAvailableUnits <= 0) {
            return 0
        }
        val selected = requestSelectionRule?.selectRequests(resource, this)
            ?: selectRequestsByResource(resource)
        if (selected.isEmpty()){
            return 0
        }
        var sum = 0
        val itr = selected.iterator()
        // ensure that res
        while (itr.hasNext() && sum <= resource.numAvailableUnits) {
            val request = itr.next()
            request.entity.resumeProcess(0.0, resumePriority)
            sum = sum + request.amountRequested
        }
        return sum
    }

}