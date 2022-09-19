package ksl.modeling.variable

import ksl.observers.ModelElementObserver
import ksl.simulation.ModelElement
import kotlin.math.abs

/** An aggregate time-weighted response observes many other time weighted response variables. Whenever any
 * variable that it observes changes, it is incremented by or decremented by the amount of the change.
 * Thus, the resulting response is the total (aggregate) of all the underlying observed responses
 * at any time.
 *
 * @param parent the parent model element
 * @param name the name of the aggregate response
 */
class AggregateTWResponse(parent: ModelElement, name : String? = null) : TWResponse(parent, name) {

    private val responses = mutableSetOf<TWResponse>()
    private val myObserver = ResponseObserver()

    private inner class ResponseObserver: ModelElementObserver(){
        override fun update(modelElement: ModelElement) {
            // must be a TWResponse because only attached to TWResponse
            val response = modelElement as TWResponse
            val change = response.value - response.previousValue
            if (change >= 0.0){
                increment(change)
            } else {
                decrement(abs(change))
            }
        }
    }

    /** The response will be observed by the aggregate such that whenever the response
     * changes, the aggregate will be assigned the same value.
     *
     * @param response the response to observe
     */
    fun observe(response: TWResponseCIfc){
        if (response is TWResponse){
            observe(response)
        }
    }

    /** The response will be observed by the aggregate such that whenever the response
     * changes, the aggregate will be assigned the same value.
     *
     * @param response the response to observe
     */
    fun observe(response: TWResponse){
        if(!responses.contains(response)){
            responses.add(response)
            response.attachModelElementObserver(myObserver)
        }
    }

    /**
     *  Causes all the responses to be observed
     */
    fun observeAll(responses: Collection<TWResponse>){
        for(response in responses){
            observe(response)
        }
    }

    /** The response will stop being observed by the aggregate.
     *
     * @param response the response to stop observing
     */
    fun remove(response: TWResponse){
        if (responses.contains(response)){
            responses.remove(response)
            response.detachModelElementObserver(myObserver)
        }
    }

    /** The response will stop being observed by the aggregate.
     *
     * @param response the response to stop observing
     */
    fun remove(response: TWResponseCIfc){
        if (response is TWResponse){
            remove(response)
        }
    }

    /**
     *  Causes all the responses to stop being observed
     *  @param responses the responses to stop observing
     */
    fun removeAll(responses: Collection<TWResponse>){
        for(response in responses){
            remove(response)
        }
    }
}