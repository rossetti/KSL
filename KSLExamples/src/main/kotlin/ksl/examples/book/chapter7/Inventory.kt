package ksl.examples.book.chapter7

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.modeling.queue.Queue
import ksl.modeling.variable.*
import ksl.simulation.ModelElement

interface InventoryFillerIfc {
    /**
     * Represents an arrival of demand to be provided by the filler
     *
     * @param demand
     */
    fun fillInventory(demand: Int)
}

abstract class Inventory(
    parent: ModelElement,
    initialOnHand: Int = 1,
    replenisher: InventoryFillerIfc,
    name: String?
) : ModelElement(parent, name), InventoryFillerIfc {

    init {
        require(initialOnHand >= 0) { "The initial on-hand inventory must be >= 0" }
    }

    protected val myOnHand = TWResponse(this, theInitialValue = initialOnHand.toDouble(),
        name = "${this.name}:OnHand")

    val onHand: Int
        get() = myOnHand.value.toInt()

    val onHandResponse: TWResponseCIfc
        get() = myOnHand

    val probOfStockOnHandResponse: ResponseIfc = myOnHand.attachIndicator(
                             { x -> x > 0 }, name = "${this.name}:PTimeWithStockOnHand")

    @set:KSLControl(
        controlType = ControlType.INTEGER,
        lowerBound = 0.0
    )
    var initialOnHand: Int
        get() = myOnHand.initialValue.toInt()
        set(amount) {
            require(amount >= 0) { "The initial amount on hand must be >= 0" }
            myOnHand.initialValue = amount.toDouble()
        }

    protected val myAmountBackOrdered = TWResponse(this, "${this.name}:AmountBackOrdered")

    val amountBackOrdered: Int
        get() = myAmountBackOrdered.value.toInt()

    val amountBackOrderedResponse: TWResponseCIfc
        get() = myAmountBackOrdered

    protected val myOnOrder = TWResponse(this, "${this.name}:OnOrder")

    val onOrder: Int
        get() = myOnOrder.value.toInt()

    val onOrderedResponse: TWResponseCIfc
        get() = myOnOrder

    val inventoryPosition: Int
        get() = onHand + onOrder - amountBackOrdered

    protected val myBackOrderQ: Queue<Demand> = Queue(this, "${this.name}:BackOrderQ")

    val backOrderQTimeResponse: ResponseIfc
        get() = myBackOrderQ.timeInQ

    val backOrderNumInQResponse: TWResponseCIfc
        get() = myBackOrderQ.numInQ

    protected val myFirstFillRate = Response(this, "${this.name}:FillRate")

    val fillRateResponse: ResponseIfc
        get() = myFirstFillRate

    var replenishmentFiller: InventoryFillerIfc = replenisher

    inner class Demand(val originalAmount: Int = 1, var amountNeeded: Int) : QObject()

    abstract fun setInitialPolicyParameters(param: DoubleArray)

    abstract fun replenishmentArrival(orderAmount: Int)

    protected abstract fun checkInventoryPosition()

    protected open fun fillDemand(demand: Int) {
        myFirstFillRate.value = 1.0
        myOnHand.decrement(demand.toDouble())
    }

    protected open fun backOrderDemand(demand: Int) {
        myFirstFillRate.value = 0.0
        // determine amount to be back ordered
        val amtBO: Int = demand - onHand
        // determine the amount to give
        val amtFilled: Int = onHand
        // give all that can be given
        myOnHand.decrement(amtFilled.toDouble())
        myAmountBackOrdered.increment(amtBO.toDouble())
        // create a demand for the back order queue
        val d = Demand(demand, amtBO)
        myBackOrderQ.enqueue(d)
    }

    protected open fun fillBackOrders() {
        var amtToFill: Int = minOf(amountBackOrdered, onHand)
        myAmountBackOrdered.decrement(amtToFill.toDouble())
        myOnHand.decrement(amtToFill.toDouble())
        // now we have to give the amount to those waiting in the backlog queue
        // we assume filling is from first waiting until all of amtToFill is used
        while (myBackOrderQ.isNotEmpty) {
            val d = myBackOrderQ.peekNext()!!
            if (amtToFill >= d.amountNeeded) {
                amtToFill = amtToFill - d.amountNeeded
                d.amountNeeded = 0
                myBackOrderQ.removeNext()
            } else {
                d.amountNeeded = d.amountNeeded - amtToFill
                amtToFill = 0
            }
            if (amtToFill == 0) {
                break
            }
        }
    }

    protected open fun requestReplenishment(orderAmt: Int) {
        myOnOrder.increment(orderAmt.toDouble())
        replenishmentFiller.fillInventory(orderAmt)
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("Initial on hand = ${myOnHand.initialValue}")
        return sb.toString()
    }
}