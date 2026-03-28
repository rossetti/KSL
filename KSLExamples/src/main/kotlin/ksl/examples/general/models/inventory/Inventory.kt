package ksl.examples.general.models.inventory

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.modeling.queue.Queue
import ksl.modeling.queue.QueueCIfc
import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.modeling.variable.TWResponseFunction
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.toDouble


/**
 *  This class represents units of an item type that can be replenished and can fill demand requests from external
 *  customers.
 *
 *  @param parent the model element that serves as this element's parent
 *  @param itemType the type of inventory item available from this inventory element
 *  @param initialOnHand the amount (units) of on hand inventory at the start of the simulation
 *  @param inventoryFiller the inventory filler that should receive demand replenishment requests from
 *  this inventory
 *  @param demandCarrier the demand receiver that is responsible for transporting (carrying) demand filled by this
 *  inventory to their required end-customer (e.g. filledDemandReceiver)
 */
abstract class Inventory(
    parent: ModelElement,
    override val itemType: ItemType,
    initialOnHand: Int = 1,
    var inventoryFiller: InventoryFillerIfc,
    name: String? = null
) : DemandCreator(parent, name), InventoryFillerIfc, InventoryReceiverIfc, InventoryCIfc {

    init {
        require(initialOnHand >= 0) { "The initial on-hand inventory must be >= 0" }
    }

    @set:KSLControl(
        controlType = ControlType.DOUBLE,
        lowerBound = 0.0,
        comment = "$/unit"
    )
    override var costPerUnit: Double = itemType.unitCost
        set(value) {
            require(value >= 0.0) { "The unit cost must be >= 0.0" }
            field = value
        }

    var demandCarrier = MissingDemandCarrier

    protected val myOnHand = TWResponse(
        this, initialValue = initialOnHand.toDouble(),
        name = "${this.name}:${itemType.name}:OnHand"
    )

    override val onHand: Int
        get() = myOnHand.value.toInt()

    @Suppress("Unused")
    override val onHandResponse: TWResponseCIfc
        get() = myOnHand

    @Suppress("Unused")
    private val myProbOfStockOnHandResponse = TWResponseFunction({ x -> (x > 0).toDouble() },
        myOnHand, name = "${this.name}:${itemType.name}:ReadyRate")

    override val probOfStockOnHandResponse: TWResponseCIfc
        get() = myProbOfStockOnHandResponse

    @set:KSLControl(
        controlType = ControlType.INTEGER,
        lowerBound = 0.0
    )
    override var initialOnHand: Int
        get() = myOnHand.initialValue.toInt()
        set(amount) {
            require(model.isNotRunning) { "The initial on-hand cannot be changed while the model is running."}
            require(amount >= 0) { "The initial amount on hand must be >= 0" }
            myOnHand.initialValue = amount.toDouble()
        }

    protected val myAmountBackOrdered = TWResponse(this, "${this.name}:${itemType.name}:AmountBackOrdered")

    override val amountBackOrdered: Int
        get() = myAmountBackOrdered.value.toInt()

    @Suppress("Unused")
    override val amountBackOrderedResponse: TWResponseCIfc
        get() = myAmountBackOrdered

    protected val myOnOrder = TWResponse(this, "${this.name}:${itemType.name}:OnOrder")

    override val onOrder: Int
        get() = myOnOrder.value.toInt()

    @Suppress("Unused")
    override val onOrderedResponse: TWResponseCIfc
        get() = myOnOrder

    protected val myNumReplenishmentOrders: Counter = Counter(this, "${this.name}:${itemType.name}:NumReplenishmentOrders")

    override val numReplenishmentOrders: CounterCIfc
        get() = myNumReplenishmentOrders

    protected val myOrderingFrequency = Response(this, "${this.name}:${itemType.name}:OrderingFrequency")

    override val orderingFrequency: ResponseCIfc
        get() = myOrderingFrequency

    override val inventoryPosition: Int
        get() = onHand + onOrder - amountBackOrdered

    protected val myBackOrderQ: Queue<Demand> = Queue(this, "${this.name}:${itemType.name}:BackOrderQ")

    @Suppress("Unused")
    override val backOrderQ: QueueCIfc<Demand>
        get() = myBackOrderQ

    protected val myFirstFillRate = Response(this, "${this.name}:${itemType.name}:FillRate")

    @Suppress("Unused")
    override val fillRate: ResponseCIfc
        get() = myFirstFillRate

    abstract fun setInitialPolicyParameters(param: DoubleArray)

    protected abstract fun checkInventoryPosition()

    protected fun deliverDemand(demand: Demand) {
        //TODO Need to note the origin, who is sending the demand?
        // delivery is always from some place to the customer
        // the demand knows its filled demand receiver (the customer)
        demandCarrier.transport(demand)
    }

    protected open fun fillImmediately(demand: Demand) {
        myFirstFillRate.value = 1.0
        val amt = demand.amountNeeded.toDouble()
        myOnHand.decrement(amt)
        demand.fill(demand.amountNeeded)
        // the demand is filled here, need to deliver the demand to the end customer
        deliverDemand(demand)
        //TODO in future need to think about where to check inventory
    }

    protected open fun backOrderDemand(demand: Demand) {
        // not filled immediately, so fill rate is 0
        myFirstFillRate.value = 0.0
        // determine amount to be back ordered
        if (onHand > 0) {
            // some can be filled immediately, but not all
            // to get here, we know that onHand < demand.amountNeeded,
            // so the amount filled is the on hand amount, and the rest is back ordered
            val amtFilled: Int = onHand
            myOnHand.decrement(amtFilled.toDouble())
            demand.fill(amtFilled)
        }
        // the rest of the demand is back ordered
        val amtBO: Int = demand.amountNeeded
        myAmountBackOrdered.increment(amtBO.toDouble())
        myBackOrderQ.enqueue(demand)
    }

    protected open fun fillBackOrders() {
        //TODO future check requirements. should not be able to fill backorders unless on hand >= amount back ordered
        var amtToFill: Int = minOf(amountBackOrdered, onHand)
        myAmountBackOrdered.decrement(amtToFill.toDouble())
        myOnHand.decrement(amtToFill.toDouble())
        // now we have to give the amount to those waiting in the backlog queue
        // we assume filling is from first waiting until all of amtToFill is used
        while (myBackOrderQ.isNotEmpty) {
            val d = myBackOrderQ.peekNext()!!
            if (amtToFill >= d.amountNeeded) {
                amtToFill = amtToFill - d.amountNeeded
                d.fill(d.amountNeeded)
                myBackOrderQ.removeNext()
                //the demand is filled here, need to deliver the demand to the end customer
                deliverDemand(d)
            } else {
                // amount available for filling is less than the amount needed
                // give the demand all of it
                d.fill(amtToFill)
                amtToFill = 0
            }
            if (amtToFill == 0) {
                // nothing left for filling
                break
            }
        }
    }

    protected open fun requestReplenishment(orderAmt: Int) {
        // increase the amount on order because a replenishment will be outstanding
        myOnOrder.increment(orderAmt.toDouble())
        myNumReplenishmentOrders.increment()
        // create the demand for the replenishment amount
        val demand = Demand(itemType,orderAmt, this)
        // send the demand for replenishment
        inventoryFiller.fillInventory(demand)
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("Item type = ${itemType.name}")
        sb.appendLine("Initial on hand = ${myOnHand.initialValue}")
        sb.appendLine("unit cost = $costPerUnit")
        return sb.toString()
    }

    companion object {

        val ImmediateDeliveryCarrier: DemandCarrierIfc = DemandCarrierIfc { demand ->
            demand.filledDemandReceiver.receiveInventory(demand)
        }

        val MissingDemandCarrier = DemandCarrierIfc { demand ->
            TODO("You need to register a working instance of DemandCarrierIfc by setting the demandCarrier property")
        }
    }
}