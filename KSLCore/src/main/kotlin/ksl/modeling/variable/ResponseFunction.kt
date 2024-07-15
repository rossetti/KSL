package ksl.modeling.variable

import ksl.observers.ModelElementObserver
import ksl.simulation.ModelElement

/**
 *  Applies the supplied function to the observed value of the response
 *  and tallies statistics on the functional.
 *  @param function the function of the observed response to tally
 *  @param observedResponse the response that is being observed
 *  @param name the name of the functional response
 */
class ResponseFunction(
    function: (Double) -> Double,
    observedResponse: Response,
    name: String? = null
) : Response(observedResponse, name) {

    private val myObserver = ResponseObserver()
    private val myFunction = function
    private val myObservedResponse = observedResponse

    init {
        myObservedResponse.attachModelElementObserver(myObserver)
    }

    /**
     *  Detaches the functional from observing the response.
     */
    fun detach() {
        myObservedResponse.detachModelElementObserver(myObserver)
    }

    private inner class ResponseObserver : ModelElementObserver() {
        override fun update(modelElement: ModelElement) {
            // must be a response because only attached to responses
            val response = modelElement as Response
            value = myFunction.invoke(response.value)
        }
    }
}