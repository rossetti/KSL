package ksl.examples.book.chapter7

import ksl.modeling.elements.EventGenerator
import ksl.modeling.elements.EventGeneratorCIfc
import ksl.modeling.elements.EventGeneratorIfc
import ksl.modeling.elements.EventGeneratorRVCIfc
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.RandomVariableCIfc
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

    private var demandAmountRV = RandomVariable(
        this, ConstantRV(1.0),
        name = "${this.name}:DemandAmountRV"
    )

    val demandAmount: RandomVariableCIfc
        get() = demandAmountRV

    private var leadTimeRV = RandomVariable(
        this, ConstantRV(10.0),
        name = "${this.name}:LeadTimeRV"
    )

    var unitHoldingCost: Double
        get() = inventory.unitHoldingCost
        set(value) {
            inventory.unitHoldingCost = value
        }

    var unitBackorderCost: Double
        get() = inventory.unitBackOrderCost
        set(value) {
            inventory.unitBackOrderCost = value
        }

    var costPerOrder: Double
        get() = inventory.costPerOrder
        set(value) {
            inventory.costPerOrder = value
        }

    val leadTime: RandomVariableCIfc
        get() = leadTimeRV

    private var timeBetweenDemandRV = ExponentialRV(365.0 / 14.0)

    private val myDemandGenerator = EventGenerator(this, this::sendDemand,
        timeBetweenDemandRV, timeBetweenDemandRV)
    val demandGenerator: EventGeneratorRVCIfc
        get() = myDemandGenerator

    private val inventory: RQInventory = RQInventory(
        this, reorderPt, reorderQty, replenisher = Warehouse(), name = "${this.name}:Item"
    )

    var initialOnHand: Int
        get() = inventory.initialOnHand
        set(amount) {
            require(amount >= 0) { "The initial amount on hand must be >= 0" }
            inventory.initialOnHand = amount
        }

    var initialReorderPoint: Int
        get() = inventory.initialReorderPoint
        set(value) {
            inventory.initialReorderPoint = value
        }

    var initialReorderQty: Int
        get() = inventory.initialReorderQty
        set(value) {
            inventory.initialReorderQty = value
        }

    fun setInitialPolicyParameters(reorderPt: Int, reorderQty: Int) {
        inventory.setInitialPolicyParameters(reorderPt, reorderQty)
    }

    private fun sendDemand(generator: EventGeneratorIfc) {
        inventory.fillInventory(demandAmountRV.value.toInt())
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