package ksl.modeling.variable

import ksl.simulation.ModelElement

// not subclassing from Variable, Response does not have an initial value, but does have limits
// should be observable
open class Response (parent: ModelElement, name: String?): ModelElement(parent, name)  {
    init {
        //TODO("Response not implemented yet")
    }
}