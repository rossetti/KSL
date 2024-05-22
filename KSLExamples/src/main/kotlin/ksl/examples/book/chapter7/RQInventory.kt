package ksl.examples.book.chapter7

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.simulation.ModelElement
import kotlin.math.ceil

class RQInventory(
    parent: ModelElement,
    reorderPt: Int = 1,
    reorderQty: Int = 1,
    initialOnHand : Int = reorderPt + reorderQty,
    replenisher: InventoryFillerIfc,
    name: String?
) : Inventory(parent, initialOnHand, replenisher, name){

    private var myInitialReorderPt: Int
    private var myInitialReorderQty : Int

    init {
        require(reorderQty > 0) {"The reorder quantity must be > 0"}
        require(reorderPt >= -reorderQty){"reorder point ($reorderPt) must be >= - reorder quantity ($reorderQty)"}
        myInitialReorderPt = reorderQty
        myInitialReorderQty = reorderQty
    }

    private var myReorderPt = myInitialReorderPt
    private var myReorderQty = myInitialReorderQty

    @set:KSLControl(
        controlType = ControlType.INTEGER,
        lowerBound = 0.0
    )
    var initialReorderPoint: Int
        get() = myInitialReorderPt
        set(value) {
            setInitialPolicyParameters(value, myInitialReorderQty)
        }

    @set:KSLControl(
        controlType = ControlType.INTEGER,
        lowerBound = 0.0
    )
    var initialReorderQty: Int
        get() = myInitialReorderQty
        set(value) {
            setInitialPolicyParameters(myInitialReorderPt, value)
        }

    fun setInitialPolicyParameters(reorderPt: Int = myInitialReorderPt, reorderQty: Int = myInitialReorderQty) {
        require(reorderQty > 0) { "reorder quantity must be > 0" }
        require(reorderPt >= -reorderQty) { "reorder point must be >= - reorder quantity" }
        myInitialReorderPt = reorderPt
        myInitialReorderQty = reorderQty
    }

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

    override fun replenishmentArrival(orderAmount: Int) {
        myOnOrder.decrement(orderAmount.toDouble())
        myOnHand.increment(orderAmount.toDouble())
        // need to fill any back orders
        if (amountBackOrdered > 0) { // back orders to fill
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

    override fun fillInventory(demand: Int) {
        require(demand > 0) { "arriving demand must be > 0" }
        if (onHand >= demand) { // fully filled
            fillDemand(demand)
        } else { // some is back ordered
            backOrderDemand(demand)
        }
        checkInventoryPosition()
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("Inventory Policy = R-Q")
        sb.append(super.toString())
        sb.appendLine("Reorder point = $myReorderPt")
        sb.appendLine("Reorder quantity = $myReorderQty")
        return sb.toString()
    }

}