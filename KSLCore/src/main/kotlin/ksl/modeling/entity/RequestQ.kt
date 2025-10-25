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
import ksl.utilities.io.KSL

/**
 *  Determines the requests that will be allocated from the specified amount available from the
 *  request queue. The total amount requested by the returned requests must not exceed the
 *  amount available.
 */
fun interface RequestSelectionRuleIfc {
    //TODO need to revise the signature to include the resource or pool that is being released
    // needs to be able to determine the amount available and if the request is waiting on the resource/pool that was released
    // if it is not waiting on the thing that now has units available it should not be selected.

    /**
     *  Determines the requests that will be allocated from the specified amount available from the
     *  request queue. The total amount requested by the returned requests must not exceed the
     *  amount available.
     *
     * @param amountAvailable the amount available
     * @param requestQ the queue to search
     * @return the requests that were selected
     */
    fun selectRequests(amountAvailable: Int, requestQ: RequestQ): List<ProcessModel.Entity.Request>

}

//TODO this could be just ResourceIfc
fun interface ResourceRequestSelectionRuleIfc {
    fun selectRequests(resource: Resource, requestQ: RequestQ): List<ProcessModel.Entity.Request>
}

fun interface ResourcePoolRequestSelectionRuleIfc {
    fun selectRequests(resourcePool: ResourcePool, requestQ: RequestQ): List<ProcessModel.Entity.Request>
}

/**
 *  Returns a list of requests that can be allocated at the current time based on the amount
 *  available criteria. Each request that can be fully allocated by the amount available is
 *  returned. The list is ordered in the same order as the RequestQ.  No partial filling is
 *  permitted in this default rule.
 */
object DefaultRequestSelectionRule : RequestSelectionRuleIfc {
    override fun selectRequests(amountAvailable: Int, requestQ: RequestQ): List<ProcessModel.Entity.Request> {
        val list = mutableListOf<ProcessModel.Entity.Request>()
        if (amountAvailable <= 0) {
            return list
        }
        var startingAmount = amountAvailable
        for (request in requestQ) {
            if (request.amountRequested <= startingAmount) {
                list.add(request)
                startingAmount = startingAmount - request.amountRequested
            }
        }
        return list
    }
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

    var requestSelectionRule: RequestSelectionRuleIfc = DefaultRequestSelectionRule

    /**
     *  @param amountAvailable the current amount available to allocate to waiting requests
     *  @return the next request to receive an allocation or null if no requests were selected for allocation
     */
    internal fun nextRequest(amountAvailable: Int): ProcessModel.Entity.Request? {
        //TODO this will need to be by resource
        if (isEmpty) {
            return null
        }
        // no need to select if there is only one waiting
        if (size == 1) {
            if (amountAvailable >= this[0].amountRequested) {
                return this[0]
            } else {
                return null
            }
        }
        // only invoke the rule if there are 2 or more requests from which to select
        val list = requestSelectionRule.selectRequests(amountAvailable, this)
        return if (list.isEmpty()) {
            null
        } else {
            list[0]
        }
    }

    /**
     *  Returns the number of requests targeting the supplied resource
     *  that are waiting in the queue.
     * @param resource the resource to check
     */
    @Suppress("unused")
    fun countRequestsFor(resource: ResourceCIfc): Int {
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
            if (request.resourcePool == pool) {
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
            if (request.resourcePool == pool) {
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
     *  The search will collect those requests that can be fully filled. This may cause skipping over of waiting requests.
     *  For example, suppose there is only 2 units left to allocate and the current
     *  request needs 3 units. The search will skip the current request and check the remaining
     *  requests until it finds the next request that needs 2 or fewer units. The search keeps
     *  allocating until all waiting requests are checked or until all available capacity is allocated.
     *
     *  @param resource the resource to check
     *  @return the list with the items ordered by the queue discipline. If no items are
     *  selected, then the returned list will be empty.
     */
    @Suppress("unused")
    fun selectRequestsByResource(resource: ResourceIfc): List<ProcessModel.Entity.Request> {
        if (isEmpty) {
            return emptyList()
        }
        val filtered = filterRequestsByResource(resource)
        // filtered holds items that are waiting for the resource and their amount requested
        // is less than or equal to the number of available units
        if (filtered.isEmpty()) {
            return emptyList()
        }
        // no need to select if there is only one waiting
        if (filtered.size == 1) {
            return filtered
        }
        // 2 or more could be satisfied
        val list = mutableListOf<ProcessModel.Entity.Request>()
        // process the filtered requests in order until all the available that can be
        // allocated are allocated. This may cause skipping over of waiting requests.
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
    @Suppress("unused")
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

    /** The method processes a request queue to allocate units to the next waiting request. If there
     * is a sufficient amount available for the next request, then the next request in the queue is processed
     * and its associated entity is resumed from waiting for the request. The entity then proceeds to
     * have its request allocated.
     *
     * @param amountAvailable the amount of units that are available to allocate to the next request
     * @param resumePriority the priority associated with resuming the waiting entity that gets its request filled
     * @return the total amount to be allocated for the resumed entities.  This must be less than or equal to
     * the amount available
     */
    internal fun processWaitingRequests(amountAvailable: Int, resumePriority: Int): Int {
        if (amountAvailable <= 0) {
            return 0
        }
        val selectedRequests = requestSelectionRule.selectRequests(amountAvailable, this)
        return processSelectedRequests(amountAvailable, selectedRequests, resumePriority)
    }

    //TODO the purpose of this function is to resume the requests that are selected for allocation
    private fun processSelectedRequests(
        amountAvailable: Int,
        selectedRequests: List<ProcessModel.Entity.Request>,
        resumePriority: Int
    ): Int {
        if (selectedRequests.isEmpty()) {
            return 0
        }
        // the selected request can be satisfied at the current time, tell the entities to stop waiting
        // the entity will ask the resource for its allocation
        var sum = 0
        val itr = selectedRequests.iterator()
        // ensure that res
        while (itr.hasNext() && sum <= amountAvailable) {
            val request = itr.next()
            //TODO
            if (request.entity.id == 17L) {
                KSL.out.println("$time > entity_id = ${request.entity.id} is being resumed from queue $name after waiting for pool ${request.resourcePool}")
            }
            request.entity.resumeProcess(0.0, resumePriority)
            sum = sum + request.amountRequested
        }
        return sum
    }

    /**
     *  The purpose of this function is to resume requests that have been selected for allocation.
     *  This function is called when a resource or resource pool has some units released. The requests
     *  that will be resumed are based on the available units in the resource and the order
     *  in which they are waiting in the request queue.
     */
    internal fun processWaitingRequests(resource: ResourceIfc, resumePriority: Int): Int {
        if (resource.numAvailableUnits <= 0) {
            return 0
        }
        //TODO use selection rule or default function
        val selected = selectRequestsByResource(resource)
        if (selected.isEmpty()){
            return 0
        }
        var sum = 0
        for(request in selected) {
            request.entity.resumeProcess(0.0, resumePriority)
            sum = sum + request.amountRequested
        }
        return sum
    }

}