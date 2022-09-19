package ksl.modeling.variable

import ksl.observers.ModelElementObserver
import ksl.simulation.ModelElement

/** A weighted response observes many other response variables. Whenever any response
 * variable that it observes changes, it is assigned the underlying value of the response.
 * Thus, observed responses that change more often occur more in the observed sample and
 * thus contribute more 'weight' to the overall mean response.  This, in essence, produces
 * a weighted average across the observed responses.
 *
 * @param parent the parent model element
 * @param name the name of the aggregate response
 */
class WeightedResponse(parent: ModelElement, name : String? = null) : Response(parent, name) {

    private val responses = mutableSetOf<Response>()
    private val myObserver = ResponseObserver()

    private inner class ResponseObserver: ModelElementObserver(){
        override fun update(modelElement: ModelElement) {
            // must be a response because only attached to responses
            val response = modelElement as Response
            value = response.value
        }
    }

    /** The response will be observed by the weighted response such that whenever the response
     * changes, the weighted response will be assigned the same value.
     *
     * @param response the response to observe
     */
    fun observe(response: ResponseCIfc){
        if (response is Response){
            observe(response)
        }
    }

    /** The response will be observed by the weighted response such that whenever the response
     * changes, the weighted response will be assigned the same value.
     *
     * @param response the response to observe
     */
    fun observe(response: Response){
        if(!responses.contains(response)){
            responses.add(response)
            response.attachModelElementObserver(myObserver)
        }
    }

    /** The response will stop being observed by the weighted response.
     *
     * @param response the response to stop observing
     */
    fun remove(response: Response){
        if (responses.contains(response)){
            responses.remove(response)
            response.detachModelElementObserver(myObserver)
        }
    }

    /** The response will stop being observed by the weighted response.
     *
     * @param response the response to stop observing
     */
    fun remove(response: ResponseCIfc){
        if (response is Response){
            remove(response)
        }
    }
}