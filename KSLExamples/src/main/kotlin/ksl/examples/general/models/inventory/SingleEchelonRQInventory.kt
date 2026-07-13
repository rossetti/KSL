package ksl.examples.general.models.inventory

import ksl.examples.general.models.inventory.Inventory.Companion.ImmediateDeliveryCarrier
import ksl.modeling.variable.RandomVariableCIfc
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.RVariableIfc

/**
 * A complete single-echelon (r, Q) inventory system wired together as one [ModelElement]: an
 * [RQInventory] served by a [LeadTimeReplenisher] (an uncapacitated supplier with random lead time)
 * and fed by an [ItemDemandGenerator] that creates customer demand at random inter-demand times and
 * amounts. Filled demand is delivered immediately to the customer. A ready-to-run building block for
 * studying a single (r, Q) stocking location.
 *
 * @param leadTime replenishment lead-time distribution
 * @param timeBtwDemand time between customer demands
 * @param demandAmount demand size per arrival
 * @param reorderPoint the reorder point r
 * @param reorderQty the reorder quantity Q
 */
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