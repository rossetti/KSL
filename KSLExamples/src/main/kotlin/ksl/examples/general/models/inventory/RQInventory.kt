package ksl.examples.general.models.inventory

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.simulation.ModelElement

/**
 * A continuous-review (r, Q) inventory policy modeled as a [ModelElement]. Inventory position is
 * checked after every demand and every replenishment receipt; whenever it falls to or below the
 * reorder point r, an order is placed in whole multiples of the reorder quantity Q to bring the
 * position back above r. Demand that cannot be met from on-hand stock is backordered and filled
 * (first-come-first-served) when replenishment arrives. Extends [Inventory] and realizes
 * [RQInventoryCIfc].
 *
 * The reorder point, reorder quantity, and the per-order / unit-holding / unit-backorder costs are
 * exposed as [KSLControl]s so they can be driven by parameter sweeps and simulation optimization.
 * At each replication end the ordering, holding, backorder, ordering-and-holding, and total cost
 * [Response]s are accumulated from the time-weighted on-hand, backorder, and order-frequency
 * statistics.
 *
 * @param itemType the stock-keeping item this inventory carries
 * @param reorderPt the reorder point r; an order is placed when inventory position <= r
 * @param reorderQty the reorder quantity Q; must be > 0
 * @param initialOnHand initial on-hand units (defaults to r + Q)
 * @param inventoryFiller the upstream filler (e.g. a [LeadTimeReplenisher]) that supplies replenishment orders
 */
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

    // True once RDelta has been set, so that setting Q holds r + Q fixed rather than r.
    private var myDeltaMode = false

    @set:KSLControl(
        controlType = ControlType.INTEGER,
        lowerBound = 0.0
    )
    override var initialReorderPoint: Int
        get() = myInitialReorderPt
        set(value) {
            myDeltaMode = false
            setInitialPolicyParameters(value, myInitialReorderQty)
        }

    /**
     *  The reorder point expressed as RDelta = r + Q, the parameterization optimizers use. Its
     *  lower bound of 0 is exactly the class's own rule r >= -Q, so every value in a box of
     *  (RDelta, Q) is a valid policy, including the negative reorder points that are optimal for
     *  some cheap-backorder items and that the reorder point control's lower bound of 0 excludes.
     *
     *  Once this is set, setting the reorder quantity holds RDelta fixed and moves r, so the pair
     *  can be applied in either order. Setting the reorder point directly returns to the default,
     *  in which setting the reorder quantity holds r fixed.
     */
    @set:KSLControl(
        controlType = ControlType.INTEGER,
        name = "RDelta",
        lowerBound = 0.0
    )
    var initialReorderPointDelta: Int
        get() = myInitialReorderPt + myInitialReorderQty
        set(value) {
            require(value >= 0) { "RDelta = r + Q must be >= 0" }
            myDeltaMode = true
            setInitialPolicyParameters(value - myInitialReorderQty, myInitialReorderQty)
        }

    @set:KSLControl(
        controlType = ControlType.INTEGER,
        lowerBound = 1.0
    )
    override var initialReorderQty: Int
        get() = myInitialReorderQty
        set(value) {
            if (myDeltaMode) {
                setInitialPolicyParameters(initialReorderPointDelta - value, value)
            } else {
                setInitialPolicyParameters(myInitialReorderPt, value)
            }
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
     *  @param param the 2-element array, where element 0 is the reorder point and element 1 is the order quantity
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
            // Order the fewest whole batches that lift the position strictly above r:
            // floor((r - IP)/Q) + 1. The difference is non-negative here, so integer division is
            // the floor. A ceiling instead leaves the position exactly at r whenever r - IP is a
            // multiple of Q, one batch short.
            val n = (myReorderPt - inventoryPosition) / myReorderQty + 1
            requestReplenishment(n * myReorderQty)
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