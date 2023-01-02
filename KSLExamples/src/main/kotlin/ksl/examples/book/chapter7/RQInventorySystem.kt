package ksl.examples.book.chapter7

import ksl.modeling.elements.EventGenerator
import ksl.modeling.variable.RandomSourceCIfc
import ksl.modeling.variable.RandomVariable
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV

class RQInventorySystem(
    parent: ModelElement,
    reorderPt: Int = 1,
    reorderQty: Int = 1,
    name: String? = null
) : ModelElement(parent, name) {

    private var leadTimeRV = RandomVariable(this, ConstantRV(10.0))
    val leadTime: RandomSourceCIfc
        get() = leadTimeRV

    private var timeBetweenDemand: RandomVariable = RandomVariable(parent, ExponentialRV(365.0 / 14.0))
    val timeBetweenDemandRV: RandomSourceCIfc
        get() = timeBetweenDemand

    private val demandGenerator = EventGenerator(this, this::sendDemand, timeBetweenDemand, timeBetweenDemand)

    private val inventory: RQInventory = RQInventory(
        this, reorderPt, reorderQty, replenisher = Warehouse(), name = "Item"
    )

    fun setInitialOnHand(amount: Int){
        inventory.setInitialOnHand(amount)
    }

    fun setInitialPolicyParameters(reorderPt: Int, reorderQty: Int){
        inventory.setInitialPolicyParameters(reorderPt, reorderQty)
    }

    private fun sendDemand(generator: EventGenerator) {
        inventory.fillInventory(1)
    }

    inner class Warehouse : InventoryFillerIfc {
        override fun fillInventory(demand: Int) {
            schedule(this::endLeadTimeAction, leadTimeRV, message = demand)
        }

        private fun endLeadTimeAction(event: KSLEvent<Int>) {
            inventory.replenishmentArrival(event.message!!)
        }
    }
}