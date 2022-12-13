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
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement

interface RequestSelectionRuleIfc {

    /**
     * @param amountAvailable the amount available
     * @param requestQ the queue to search
     * @return the request that was selected
     */
    fun selectRequest(amountAvailable: Int, requestQ: RequestQ): ProcessModel.Entity.Request?

}

/**
 *  Returns the first request that needs less than or equal to the amount available
 */
class DefaultRequestSelectionRule : RequestSelectionRuleIfc {
    override fun selectRequest(amountAvailable: Int, requestQ: RequestQ): ProcessModel.Entity.Request? {
        for (request in requestQ) {
            if (request.amountRequested <= amountAvailable) {
                return request
            }
        }
        return null
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

    var requestSelectionRule: RequestSelectionRuleIfc? = null

    fun selectRequest(amountAvailable: Int): ProcessModel.Entity.Request? {
        if (requestSelectionRule != null) {
            return requestSelectionRule!!.selectRequest(amountAvailable, this)
        } else {
            return peekNext()
        }
    }

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
     * @param resumePriority the priority associated with resuming the waiting entity that gets
     * its request filled
     */
    internal fun processNextRequest(amountAvailable:Int , resumePriority: Int){
        if (isNotEmpty) {
            //this is peekNext() because the resumed process removes the request
            val request = selectRequest(amountAvailable)
            if (request != null) {
                if (request.amountRequested <= amountAvailable) {
                    // resume the entity's process related to the request
                    request.entity.resumeProcess(0.0, resumePriority)
                }
            }
        }
    }
}