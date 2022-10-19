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
}