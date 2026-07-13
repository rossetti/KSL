package ksl.examples.general.models.inventory

import ksl.examples.general.models.inventory.Inventory.Companion.ImmediateDeliveryCarrier
import ksl.modeling.variable.RandomVariableCIfc
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.RVariableIfc

/**
 * A two-echelon (r, Q) supply chain modeled as one [ModelElement]: an external supplier (a
 * [LeadTimeReplenisher]) replenishes a distribution-center [RQInventory], which in turn replenishes a
 * downstream base [RQInventory] across a shipping delay (a [TimeBasedDemandCarrier]). Each echelon has
 * its own demand stream (an [ItemDemandGenerator]), reorder point, and reorder quantity; the base is
 * served by the DC, so DC stockouts propagate as base backorders. Aggregates the two echelons' costs
 * into total cost and total ordering-and-holding cost responses — the objective and constraint
 * responses used by the two-echelon optimization problem built in [BuildTwoEchelonModel].
 *
 * @param supplierLeadTimeToDC supplier lead time replenishing the DC
 * @param shippingTimeDCToBase shipping delay from the DC to the base
 */
class TwoEchelonModel(
    parent: ModelElement,
    itemType: ItemType = ItemType(),
    supplierLeadTimeToDC: RVariableIfc,
    timeBtwDemandDC: RVariableIfc,
    demandAmountDC: RVariableIfc,
    reorderPointDC: Int = 1,
    reorderQtyDC: Int = 1,
    initialOnHandDC: Int = 1,
    shippingTimeDCToBase: RVariableIfc,
    timeBtwDemandBase: RVariableIfc,
    demandAmountBase: RVariableIfc,
    reorderPointBase: Int = 1,
    reorderQtyBase: Int = 1,
    initialOnHandBase: Int = 1,
    name: String? = null
) : ModelElement(parent, name) {

    private val mySupplier = LeadTimeReplenisher(
        this, supplierLeadTimeToDC,
        name = "${this.name}:Supplier"
    )
    val supplierLeadTIme: RandomVariableCIfc
        get() = mySupplier.leadTime

    private val myDCInventory: RQInventory = RQInventory(
        this, itemType, reorderPointDC, reorderQtyDC, initialOnHandDC,
        mySupplier, name = "${this.name}:DCInventory"
    )
    val inventoryDC: RQInventoryCIfc
        get() = myDCInventory

    private val myItemDemandGeneratorDC: ItemDemandGenerator = ItemDemandGenerator(
        this, itemType,
        myDCInventory, timeBtwDemandDC, demandAmountDC
    )

    private val myBaseInventory: RQInventory = RQInventory(
        this, itemType, reorderPointBase, reorderQtyBase, initialOnHandBase,
        myDCInventory, name = "${this.name}:BaseInventory"
    )
    val inventoryBase: RQInventoryCIfc
        get() = myBaseInventory

    private val dcToBaseDemandCarrier = TimeBasedDemandCarrier(
        this, mapOf(myBaseInventory to shippingTimeDCToBase)
    )

    init {
        myDCInventory.demandCarrier = dcToBaseDemandCarrier
        myBaseInventory.demandCarrier = ImmediateDeliveryCarrier
    }

    private val myItemDemandGeneratorBase: ItemDemandGenerator = ItemDemandGenerator(
        this, itemType,
        myBaseInventory, timeBtwDemandBase, demandAmountBase
    )

    private val myTotalCost = Response(this, name = "${this.name}:TotalCost")
    val totalCost: ResponseCIfc
        get() = myTotalCost

    private val myTotalOrderingAndHoldingCost = Response(this, name = "${this.name}:TotalOrderingAndHoldingCost")
    val totalOrderingHoldingCost: ResponseCIfc
        get() = myTotalOrderingAndHoldingCost

    override fun replicationEnded(){
        myTotalCost.value = inventoryDC.totalCost.value + inventoryBase.totalCost.value
        myTotalOrderingAndHoldingCost.value = inventoryDC.orderingAndHoldingCost.value + inventoryBase.orderingAndHoldingCost.value
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("Two Echelon Model:")
        sb.appendLine("Supplier Lead Time to DC: ${supplierLeadTIme.initialRandomSource}")
        val dcTBE = myItemDemandGeneratorDC.eventGenerator.initialTimeBtwEvents
        sb.appendLine("DC Time Between Demand: $dcTBE")
        sb.appendLine("DC Demand Amount: ${myItemDemandGeneratorDC.demandAmount.initialRandomSource}")
        sb.appendLine("DC Inventory: ")
        sb.appendLine("$inventoryDC")
        sb.appendLine("Shipping Time from DC to Base: ${dcToBaseDemandCarrier.shippingTimes[myBaseInventory]}")
        val baseTBE = myItemDemandGeneratorBase.eventGenerator.initialTimeBtwEvents
        sb.appendLine("Base Time Between Demand: $baseTBE")
        sb.appendLine("Base Demand Amount: ${myItemDemandGeneratorBase.demandAmount.initialRandomSource}")
        sb.appendLine("Base Inventory:")
        sb.appendLine("$inventoryBase")
        return sb.toString()
    }
}