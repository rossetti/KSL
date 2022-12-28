package ksl.examples.book.chapter7

import ksl.modeling.elements.EventGenerator
import ksl.modeling.variable.RandomSourceCIfc
import ksl.modeling.variable.RandomVariable
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV

class RQInventorySystem(parent: ModelElement, name: String? = null): ModelElement(parent, name) {

    private var myReorderPoint: Int = 4
    private var myReorderQty : Int = 10
    
    private var timeBetweenDemand: RandomVariable = RandomVariable(parent, ExponentialRV(365.0 / 14.0))
    val timeBetweenDemandRV: RandomSourceCIfc
        get() = timeBetweenDemand

    private val demandGenerator = EventGenerator(this, this::sendDemand, timeBetweenDemand, timeBetweenDemand)

    private fun sendDemand(generator: EventGenerator){

    }
}