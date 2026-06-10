/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.examples.general.bookbundle

// Adapted from ksl.examples.book.chapter7.RQInventory for the "KSL Book Examples" bundle
// (edu.uark.ksl.book-examples). Behaviorally identical to the chapter version
// unless noted; the bundle wraps it in BookExamplesBundle.kt.

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.simulation.ModelElement
import kotlin.math.ceil

class RQInventory(
    parent: ModelElement,
    reorderPt: Int = 1,
    reorderQty: Int = 1,
    initialOnHand: Int = reorderPt + reorderQty,
    replenisher: InventoryFillerIfc,
    name: String?
) : Inventory(parent, initialOnHand, replenisher, name) {

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
    var initialReorderPoint: Int
        get() = myInitialReorderPt
        set(value) {
            setInitialPolicyParameters(value, myInitialReorderQty)
        }

    @set:KSLControl(
        controlType = ControlType.INTEGER,
        lowerBound = 1.0
    )
    var initialReorderQty: Int
        get() = myInitialReorderQty
        set(value) {
            setInitialPolicyParameters(myInitialReorderPt, value)
        }

    private val myOrderingFrequency = Response(this, "${this.name}:OrderingFrequency")

    val orderingFrequency: ResponseCIfc
        get() = myOrderingFrequency

    @set:KSLControl(
        controlType = ControlType.DOUBLE,
        lowerBound = 0.0,
        comment = "$/order"
    )
    var costPerOrder: Double = 1.0
        set(value) {
            require(value >= 0.0) { "The ordering cost must be >= 0.0" }
            field = value
        }

    @set:KSLControl(
        controlType = ControlType.DOUBLE,
        lowerBound = 0.0,
        comment = "$/unit/time"
    )
    var unitHoldingCost: Double = 1.0
        set(value) {
            require(value >= 0.0) { "The holding cost must be >= 0.0" }
            field = value
        }

    @set:KSLControl(
        controlType = ControlType.DOUBLE,
        lowerBound = 0.0,
        comment = "$/unit/time"
    )
    var unitBackOrderCost: Double = 1.0
        set(value) {
            require(value >= 0.0) { "The backorder cost must be >= 0.0" }
            field = value
        }

    private val myTotalCost = Response(this, "${this.name}:TotalCost")
    val totalCost: ResponseCIfc
        get() = myTotalCost

    private val myOrderingCost = Response(this, "${this.name}:OrderingCost")
    val orderingCost: ResponseCIfc
        get() = myOrderingCost

    private val myHoldingCost = Response(this, "${this.name}:HoldingCost")
    val holdingCost: ResponseCIfc
        get() = myHoldingCost

    private val myBackorderCost = Response(this, "${this.name}:BackorderCost")
    val backOrderCost: ResponseCIfc
        get() = myBackorderCost

    private val myOrderingAndHoldingCost = Response(this, "${this.name}:OrderingAndHoldingCost")
    val orderingAndHoldingCost: ResponseCIfc
        get() = myOrderingAndHoldingCost

    fun setInitialPolicyParameters(reorderPt: Int, reorderQty: Int) {
        require(model.isNotRunning) { "The initial policy parameters cannot be changed while the model is running."}
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
        //myNumReplenishment.increment()
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
        sb.appendLine("Initial On Hand = $initialOnHand")
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