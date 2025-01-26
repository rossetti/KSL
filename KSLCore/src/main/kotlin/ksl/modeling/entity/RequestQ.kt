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
) : Queue<ProcessModel.Entity.Request>(parent, name, discipline) {

    //TODO track the number of requests for each resource
    private val myResources = mutableMapOf<Resource, Int>()

    //TODO track the number of requests for each resource pool
    private val myResourcePools = mutableMapOf<ResourcePool, Int>()

    var requestSelectionRule: RequestSelectionRuleIfc = DefaultRequestSelectionRule()

    //TODO need to override: remove(object), clear(), enqueue()

    override fun enqueue(qObject: ProcessModel.Entity.Request, priority: Int, obj: Any?) {
        super.enqueue(qObject, priority, obj)
        registerResources(qObject)
    }

    private fun registerResources(request: ProcessModel.Entity.Request){
        //TODO track the resource or the pool
        if (request.resource != null) {
            // must be a resource request
            val resource = request.resource!!
            if (myResources.contains(resource)){
                val count = myResources[resource]!! + 1
                myResources[resource] = count
            } else {
                myResources[resource] = 1
            }
            resource.myQueueSet.add(this)
            return
        }
        if (request.resourcePool != null) {
            // must be a request for a pool
            val pool = request.resourcePool!!
            if (myResourcePools.contains(pool)){
                val count = myResourcePools[pool]!! + 1
                myResourcePools[pool] = count
            } else {
                myResourcePools[pool] = 1
            }
            pool.myQueueSet.add(this)
            return
        }
        //TODO may need to handle MovableResourcePool
        throw IllegalStateException("Unable to register $request. The request was not for a resource or a pool")
    }

    override fun clear() {
        super.clear()
        myResources.clear()
        myResourcePools.clear()
    }

    override fun remove(qObj: ProcessModel.Entity.Request, waitStats: Boolean): Boolean {
        val b = super.remove(qObj, waitStats)
        if (!b){
            return false
        }
        unregisterResources(qObj)
        return true
    }

    private fun unregisterResources(request: ProcessModel.Entity.Request){
        // stop tracking the request
        if (request.resource != null){
            // it was a request for a resource
            val resource = request.resource!!
            require(myResources.contains(resource)){"UnregisteringResources: The resource, ${resource.name}, was not registered."}
            val count = myResources[resource]!! - 1
            if (count == 0){
                myResources.remove(resource)
                resource.myQueueSet.remove(this)
            } else {
                myResources[resource] = count
            }
            return
        }
        if (request.resourcePool != null){
            // it was a request for a pool
            val pool = request.resourcePool!!
            require(myResourcePools.contains(pool)){"UnregisteringResources: The resource pool, ${pool.name}, was not registered."}
            val count = myResourcePools[pool]!! - 1
            if (count == 0){
                myResourcePools.remove(pool)
                pool.myQueueSet.remove(this)
            } else {
                myResourcePools[pool] = count
            }
            return
        }
        //TODO may need to handle MovableResourcePool
        throw IllegalStateException("Unable to unregister $request. The request was not for a resource or a pool")
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

    /** The method processes a request queue to allocated units to the next waiting request. If there
     * is a sufficient amount available for the next request, then the next request in the queue is processed
     * and its associated entity is resumed from waiting for the request. The entity then proceeds to
     * have its request allocated.
     *
     * @param amountAvailable the amount of units that are available to allocate to the next request
     * @param resumePriority the priority associated with resuming the waiting entity that gets its request filled
     * @return the number of waiting requests that were processed
     */
    internal fun processWaitingRequests(amountAvailable: Int, resumePriority: Int): Int {
        if (amountAvailable <= 0) {
            return 0
        }
        val selectedRequests = requestSelectionRule.selectRequests(amountAvailable, this)
        for (request in selectedRequests) {
            request.entity.resumeProcess(0.0, resumePriority)
        }
        return selectedRequests.size
    }
}