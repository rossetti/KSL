/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
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
import ksl.simulation.ModelElement

interface RequestSelectionRuleIfc {

    /**
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
class DefaultRequestSelectionRule : RequestSelectionRuleIfc {
    override fun selectRequests(amountAvailable: Int, requestQ: RequestQ): List<ProcessModel.Entity.Request> {
        val list = mutableListOf<ProcessModel.Entity.Request>()
        if (amountAvailable <= 0){
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
 *  If the user supplies a request selection rule then this will override the default
 *  queue discipline for finding the next request from the queue.
 *
 * @param parent containing model element
 * @param name the name of the queue
 * @param discipline the queue discipline for ordering the queue
 */
class RequestQ(
    parent: ModelElement,
    name: String? = null,
    discipline: Discipline = Discipline.FIFO
) :
    Queue<ProcessModel.Entity.Request>(parent, name, discipline) {

    var requestSelectionRule: RequestSelectionRuleIfc = DefaultRequestSelectionRule()

    /** Removes the request from the queue and tells the associated entity to terminate its process.  The process
     *  that was suspended because the entity's request was placed in the queue is immediately terminated.
     *
     * @param request the request to remove from the queue
     * @param waitStats if true the waiting time statistics are collected on the usage of the queue.
     * The default is false.
     */
    fun removeAndTerminate(
        request: ProcessModel.Entity.Request,
        waitStats: Boolean = false
    ) {
        remove(request, waitStats)
        request.entity.terminateProcess()
    }

    /**
     *  Removes and terminates all the requests waiting in the queue
     *
     * @param waitStats if true the waiting time statistics are collected on the usage of the queue.
     * The default is false.
     */
    fun removeAllAndTerminate(waitStats: Boolean = false) {
        while (isNotEmpty) {
            val request = peekNext()
            removeAndTerminate(request!!, waitStats)
        }
    }

    /** The method processes a request queue to allocated units to the next waiting request. If there
     * is a sufficient amount available for the next request, then the next request in the queue is processed
     * and its associated entity is resumed from waiting for the request. The entity then proceeds to
     * have its request allocated.
     *
     * @param amountAvailable the amount of units that are available to allocate to the next request
     * @param resumePriority the priority associated with resuming the waiting entity that gets its request filled
     * @return the number of waiting requests that were processed
     */
    internal fun processWaitingRequests(amountAvailable: Int, resumePriority: Int) : Int {
        if (amountAvailable <= 0){
            return 0
        }
        val selectedRequests = requestSelectionRule.selectRequests(amountAvailable, this)
        for (request in selectedRequests) {
            request.entity.resumeProcess(0.0, resumePriority)
        }
        return selectedRequests.size
    }
}