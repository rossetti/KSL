package ksl.modeling.entity

import ksl.modeling.queue.Queue
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
}