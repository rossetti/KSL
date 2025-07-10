package ksl.modeling.variable

import ksl.observers.ModelElementObserver
import ksl.simulation.ModelElement

/**
 *  Applies the supplied function to the observed value of the time-weighted
 *  response variable and collects time-weighted statistics on the functional.
 *
 *  @param function the function of the observed response to tally
 *  @param observedResponse the response that is being observed
 *  @param name the name of the functional response
 */
class TWResponseFunction @JvmOverloads constructor(
    function: (Double) -> Double,
    observedResponse: TWResponse,
    name: String? = null
) : TWResponse(observedResponse, name) {

    @JvmOverloads
    @Suppress("unused")
    constructor(
        function: (Double) -> Double,
        observedResponse: TWResponseCIfc,
        name: String? = null
    ) : this(function, observedResponse as TWResponse, name)

    private val myObserver = ResponseObserver()
    private val myFunction = function
    private val myObservedResponse = observedResponse

    init {
        myObservedResponse.attachModelElementObserver(myObserver)
    }

    /**
     *  Detaches the functional from observing the response.
     */
    @Suppress("unused")
    fun detach() {
        myObservedResponse.detachModelElementObserver(myObserver)
    }

    private inner class ResponseObserver : ModelElementObserver() {
        override fun update(modelElement: ModelElement) {
            // must be a response because only attached to responses
            value = myFunction.invoke(myObservedResponse.value)
        }
    }
}