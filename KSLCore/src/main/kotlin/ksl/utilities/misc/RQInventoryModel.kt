package ksl.utilities.misc

import ksl.utilities.distributions.LossFunctionDistributionIfc
import ksl.utilities.distributions.Poisson
import kotlin.math.sqrt

fun main(){
    val d: Poisson = Poisson(1.726) // mean demand during replenishment
    val m = RQInventoryModel(3.0, 4.0, d) // example on pg 85 of HS
    println(m)
    println("Done!")
}

class RQInventoryModel(
    orderPt: Double,
    orderQty: Double,
    var ltdDist: LossFunctionDistributionIfc
) {
    init {
        require(orderQty > 0.0) { "Reorder Quantity must be > 0)" }
        require(orderPt >= -orderQty) { "Reorder Point must be >= -Reorder Quantity)" }
    }

    var reorderQty: Double = orderQty
        set(value) {
            require(value > 0.0) { "Reorder Quantity must be > 0)" }
            field = value
        }

    var reorderPt: Double = orderPt
        set(value) {
            require(value >= -reorderQty) { "Reorder Point must be >= -Reorder Quantity)" }
            field = value
        }

    var unitCost: Double = 1.0
        set(value) {
            require(value > 0.0) { "The unit cost must be > 0.0" }
            field = value
        }

    var costPerOrder: Double = 1.0
        set(value) {
            require(value > 0.0) { "The cost per order must be > 0.0" }
            field = value
        }

    var holdingCostRatePerPeriod: Double = 1.0
        set(value) {
            require(value > 0.0) { "The holding cost rate per period must be > 0.0" }
            field = value
        }

    var backOrderCostPerUnitPerPeriod: Double = 1.0
        set(value) {
            require(value > 0.0) { "The backorder cost rate per period must be > 0.0" }
            field = value
        }

    var costPerStockout: Double = 1.0
        set(value) {
            require(value > 0.0) { "The cost per stockout  must be > 0.0" }
            field = value
        }

    var leadTime: Double = 1.0
        set(value) {
            require(value > 0.0) { "The lead time must be > 0.0" }
            field = value
        }

    var periodLength: Double = 1.0
        set(value) {
            require(value > 0.0) { "The period length must be > 0.0" }
            field = value
        }

    val demandPerPeriod: Double
        get() = ((ltdDist.mean() * periodLength) / leadTime)

    val orderFrequency: Double
        get() = (demandPerPeriod / reorderQty)

    val fillRate: Double
        get() {
            val g1r = ltdDist.firstOrderLossFunction(reorderPt)
            val g1rQ = ltdDist.firstOrderLossFunction(reorderPt + reorderQty)
            return (1.0 - ((g1r - g1rQ) / reorderQty))
        }

    val probStockout: Double
        get() = (1.0 - fillRate)

    val expectedStockoutsPerPeriod: Double
        get() = (demandPerPeriod * probStockout)

    val expectedBackOrders: Double
        get() {
            val g2r = ltdDist.secondOrderLossFunction(reorderPt)
            val g2rQ = ltdDist.secondOrderLossFunction(reorderPt + reorderQty)
            return (((g2r - g2rQ) / reorderQty))
        }

    val expectedOnHandInventory: Double
        get() = (0.5 * (reorderQty + 1.0) + reorderPt - ltdDist.mean() + expectedBackOrders)

    val expectedCustomerWaitTime: Double
        get() = (expectedBackOrders / demandPerPeriod)

    val orderCostPerPeriod: Double
        get() = (costPerOrder * orderFrequency)

    val holdingCostPerPeriod: Double
        get() = (holdingCostRatePerPeriod * expectedOnHandInventory)

    val backOrderCostPerPeriod: Double
        get() = (backOrderCostPerUnitPerPeriod * expectedBackOrders)

    val stockoutCostPerPeriod: Double
        get() = (costPerStockout * expectedStockoutsPerPeriod)

    val backOrderModelTotalCostPerPeriod: Double
        get() = (orderCostPerPeriod + holdingCostPerPeriod + backOrderCostPerPeriod)

    val stockoutModelTotalCostPerPeriod: Double
        get() = (orderCostPerPeriod + holdingCostPerPeriod + stockoutCostPerPeriod)

    val holdingCostPerUnitPerPeriod: Double
        get() = (unitCost * holdingCostRatePerPeriod)

    fun approxEOQ(): Double {
        val d: Double = demandPerPeriod
        val a: Double = costPerOrder
        val h = holdingCostPerUnitPerPeriod
        return (sqrt((2.0 * d * a) / h))
    }

    fun approxOptimalReorderPtViaBackOrderModel(): Double {
        val q = approxEOQ()
        val h = holdingCostPerUnitPerPeriod
        val b: Double = backOrderCostPerUnitPerPeriod
        return (ltdDist.invCDF(b / (b + h)))
    }

    fun approxOptimalReorderPtViaStockoutModel(): Double {
        val d: Double = demandPerPeriod
        val kd: Double = costPerStockout * d
        val q = approxEOQ()
        val h = holdingCostPerUnitPerPeriod
        return (ltdDist.invCDF(kd / (kd + h * q)))
    }

    override fun toString(): String {
        return buildString {
            appendLine("Reorder point = $reorderPt Reorder Quantity = $reorderQty")
            appendLine("Fill rate = $fillRate")
            appendLine("Probability of Stockout = $probStockout")
            appendLine("Expected backorders = $expectedBackOrders")
            appendLine("Expected customer wait time = $expectedCustomerWaitTime")
            appendLine("Expected on hand inventory = $expectedOnHandInventory")
            appendLine("Order Frequency = $orderFrequency")
            appendLine("Order cost per period = $orderCostPerPeriod")
            appendLine("Holding cost per period = $holdingCostPerPeriod")
            appendLine("Backorder cost per period = $backOrderCostPerPeriod")
            appendLine("Stockout cost per period = $stockoutCostPerPeriod")
            appendLine("Backorder model total cost per period = $backOrderModelTotalCostPerPeriod")
            appendLine("Stockout model total cost per period = $stockoutModelTotalCostPerPeriod")
            appendLine("Approximate EOQ = ${approxEOQ()}")
            appendLine("Approximate optimal reorder point via backorder model = ${approxOptimalReorderPtViaBackOrderModel()}")
            appendLine("Approximate optimal reorder point via stockout model = ${approxOptimalReorderPtViaStockoutModel()}")
        }
    }

}