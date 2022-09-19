package ksl.modeling.variable

import ksl.observers.ModelElementObserver
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.toDouble

class IndicatorResponse(predicate: (Double) -> Boolean,
                        observedResponse: Response, name: String? = null) : Response(observedResponse, name) {

    private val myObserver = ResponseObserver()
    private val myPredicate = predicate
    private val myObservedResponse = observedResponse
    init {
        myObservedResponse.attachModelElementObserver(myObserver)
    }

    fun detach(){
        myObservedResponse.detachModelElementObserver(myObserver)
    }

    private inner class ResponseObserver: ModelElementObserver(){
        override fun update(modelElement: ModelElement) {
            // must be a response because only attached to responses
            val response = modelElement as Response
            value = myPredicate.invoke(response.value).toDouble()
        }
    }
}
