package ksl.examples.general.models.inventory

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.simulation.ModelElement
import kotlin.math.ceil

class RQInventory(
    parent: ModelElement,
    itemType: ItemType,
    reorderPt: Int = 1,
    reorderQty: Int = 1,
    initialOnHand: Int = reorderPt + reorderQty,
    inventoryFiller: InventoryFillerIfc,
    name: String?
) : Inventory(parent, itemType, initialOnHand, inventoryFiller, name), RQInventoryCIfc {

    init {
        require(reorderQty > 0) { "The reorder quantity must be > 0" }
        require(reorderPt >= -reorderQty) { "reorder point ($reorderPt) must be >= - reorder quantity ($reorderQty)" }
    }

    private var myInitialReorderPt: Int = reorderPt
    private var myInitialReorderQty: Int = reorderQty
    private var myReorderPt = myInitialReorderPt
    private var myReorderQty = myInitialReorderQty

    @set:KSLControl(
        controlType = ControlType.INTEGER,
        lowerBound = 0.0
    )
    override var initialReorderPoint: Int
        get() = myInitialReorderPt
        set(value) {
            setInitialPolicyParameters(value, myInitialReorderQty)
        }

    @set:KSLControl(
        controlType = ControlType.INTEGER,
        lowerBound = 1.0
    )
    override var initialReorderQty: Int
        get() = myInitialReorderQty
        set(value) {
            setInitialPolicyParameters(myInitialReorderPt, value)
        }

    @set:KSLControl(
        controlType = ControlType.DOUBLE,
        lowerBound = 0.0,
        comment = "$/order"
    )
    override var costPerOrder: Double = 1.0
        set(value) {
            require(value >= 0.0) { "The ordering cost must be >= 0.0" }
            field = value
        }

    @set:KSLControl(
        controlType = ControlType.DOUBLE,
        lowerBound = 0.0,
        comment = "$/unit/time"
    )
    override var unitHoldingCost: Double = 1.0
        set(value) {
            require(value >= 0.0) { "The holding cost must be >= 0.0" }
            field = value
        }

    @set:KSLControl(
        controlType = ControlType.DOUBLE,
        lowerBound = 0.0,
        comment = "$/unit/time"
    )
    override var unitBackOrderCost: Double = 1.0
        set(value) {
            require(value >= 0.0) { "The backorder cost must be >= 0.0" }
            field = value
        }

    private val myTotalCost = Response(this, "${this.name}:${itemType.name}:TotalCost")
    override val totalCost: ResponseCIfc
        get() = myTotalCost

    private val myOrderingCost = Response(this, "${this.name}:${itemType.name}:OrderingCost")
    override val orderingCost: ResponseCIfc
        get() = myOrderingCost

    private val myHoldingCost = Response(this, "${this.name}:${itemType.name}:HoldingCost")
    override val holdingCost: ResponseCIfc
        get() = myHoldingCost

    private val myBackorderCost = Response(this, "${this.name}:${itemType.name}:BackorderCost")
    override val backOrderCost: ResponseCIfc
        get() = myBackorderCost

    private val myOrderingAndHoldingCost = Response(this, "${this.name}:${itemType.name}:OrderingAndHoldingCost")
    override val orderingAndHoldingCost: ResponseCIfc
        get() = myOrderingAndHoldingCost

    fun setInitialPolicyParameters(reorderPt: Int, reorderQty: Int) {
        require(model.isNotRunning) { "The initial policy parameters cannot be changed while the model is running."}
        require(reorderQty > 0) { "reorder quantity must be > 0" }
        require(reorderPt >= -reorderQty) { "reorder point must be >= - reorder quantity" }
        myInitialReorderPt = reorderPt
        myInitialReorderQty = reorderQty
    }

    /**
     *  @param param the 2 element array, where param[0] = reorder point and param[1] = order quantity
     */
    override fun setInitialPolicyParameters(param: DoubleArray) {
        require(param.size == 2) { "There must be 2 parameters" }
        setInitialPolicyParameters(param[0].toInt(), param[1].toInt())
    }

    override fun initialize() {
        super.initialize()
        myReorderPt = myInitialReorderPt
        myReorderQty = myInitialReorderQty
        checkInventoryPosition()
    }

    override fun receiveInventory(demand: Demand) {
        require(demand.isFilled) { "The demand is not filled." }
        val orderAmount = demand.originalAmount.toDouble()
        myOnOrder.decrement(orderAmount)
        myOnHand.increment(orderAmount)
        // need to fill any back orders
        if (amountBackOrdered > 0) { // back orders to fill
            // on hand will be > 0
            fillBackOrders()
        }
        checkInventoryPosition()
    }

    override fun checkInventoryPosition() {
        if (inventoryPosition <= myReorderPt) {
            // determine the amount to order and request the replenishment
            // need to place an order, figure out the amount below reorder point
            if (inventoryPosition == myReorderPt) { // hit reorder point exactly
                requestReplenishment(myReorderQty)
            } else {
                val gap = (myReorderPt - inventoryPosition).toDouble()
                // find number of batches to order
                val n = ceil(gap / myReorderQty).toInt()
                requestReplenishment(n * myReorderQty)
            }
        }
    }

    override fun fillInventory(demand: Demand) {
        require(demand.isNotFilled) {"The demand is already filled."}
        if (onHand >= demand.amountNeeded) { // can fill immediately
            fillImmediately(demand)
        } else { // some is back ordered
            backOrderDemand(demand)
        }
        checkInventoryPosition()
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("Inventory Policy = R-Q")
        sb.append(super.toString())
        sb.appendLine("holding cost = $unitHoldingCost")
        sb.appendLine("backorder cost = $unitBackOrderCost")
        sb.appendLine("ordering cost = $costPerOrder")
        sb.appendLine("Reorder point = $myReorderPt")
        sb.appendLine("Reorder quantity = $myReorderQty")
        return sb.toString()
    }

    override fun replicationEnded() {
        myHoldingCost.value = unitHoldingCost * myOnHand.withinReplicationStatistic.weightedAverage
        myBackorderCost.value = unitBackOrderCost * myAmountBackOrdered.withinReplicationStatistic.weightedAverage
        myOrderingFrequency.value = myNumReplenishmentOrders.value/(time - myNumReplenishmentOrders.timeOfWarmUp)
        myOrderingCost.value = costPerOrder * myOrderingFrequency.value
        myOrderingAndHoldingCost.value = myOrderingCost.value + myHoldingCost.value
        myTotalCost.value = myOrderingCost.value + myHoldingCost.value + myBackorderCost.value
    }


}