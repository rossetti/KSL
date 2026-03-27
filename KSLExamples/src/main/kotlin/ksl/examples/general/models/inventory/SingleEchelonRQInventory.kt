package ksl.examples.general.models.inventory

import ksl.examples.general.models.inventory.Inventory.Companion.ImmediateDeliveryCarrier
import ksl.modeling.variable.RandomVariableCIfc
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.RVariableIfc

class SingleEchelonRQInventory(
    parent: ModelElement,
    itemType: ItemType = ItemType(),
    leadTime: RVariableIfc,
    timeBtwDemand: RVariableIfc,
    demandAmount: RVariableIfc,
    reorderPoint: Int = 1,
    reorderQty: Int = 1,
    initialOnHand: Int = 1,
    name: String? = null
) : ModelElement(parent, name) {

    private val myLeadTimeReplenisher = LeadTimeReplenisher(
        this, leadTime,
        name = "${this.name}:LeadTimeReplenisher"
    )
    val leadTime: RandomVariableCIfc
        get() = myLeadTimeReplenisher.leadTime

    private val myRQInventory: RQInventory = RQInventory(
        this, itemType, reorderPoint, reorderQty, initialOnHand,
        myLeadTimeReplenisher, name = "${this.name}:BaseInventory"
    )
    val rqInventory: RQInventoryCIfc
        get() = myRQInventory

    init {
        myRQInventory.demandCarrier = ImmediateDeliveryCarrier
    }
    val inventory: InventoryCIfc
        get() = myRQInventory

    private val myItemDemandGenerator: ItemDemandGenerator = ItemDemandGenerator(
        this, itemType,
        myRQInventory, timeBtwDemand, demandAmount
    )

}