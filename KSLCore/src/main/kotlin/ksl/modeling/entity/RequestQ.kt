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
class RequestQ(
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
    fun countRequestsFor(resource: ResourceCIfc) : Int {
        var count = 0
        for(request in myList){
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
    fun countRequestsFor(pool: ResourcePool) : Int {
        var count = 0
        for(request in myList){
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
    fun countRequestsFor(pool: MovableResourcePool) : Int {
        var count = 0
        for(request in myList){
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
    fun removeAllAndTerminate(
        waitStats: Boolean = false,
        afterTermination: ((entity: ProcessModel.Entity) -> Unit)? = null
    ) {
        while (isNotEmpty) {
            val request = peekNext()
            removeAndTerminate(request!!, waitStats, afterTermination)
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
        // the selected request can be satisfied at the current time, tell the entities to stop waiting
        // the entity will ask the resource for its allocation
        var sum = 0
        val itr = selectedRequests.iterator()
        // ensure that res
        while (itr.hasNext() && sum <= amountAvailable) {
            val request = itr.next()
            request.entity.resumeProcess(0.0, resumePriority)
            sum = sum + request.amountRequested
        }
        return sum
    }
}