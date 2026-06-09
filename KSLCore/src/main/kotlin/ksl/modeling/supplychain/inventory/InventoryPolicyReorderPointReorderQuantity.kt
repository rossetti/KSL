package ksl.modeling.supplychain.inventory

import ksl.modeling.supplychain.*

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.simulation.ModelElement
import kotlin.math.ceil

/**
 * An (r, Q) inventory policy: orders [reorderQty] units when the
 * inventory position falls to [reorderPoint] or below.
 *
 * When the position drops well below the reorder point so a single
 * [reorderQty] won't bring it above [reorderPoint], the policy orders
 * `n × reorderQty` where `n = ceil((reorderPoint − position) / reorderQty)`.
 * If [separateBatchOrders] is true the n batches are ordered as n
 * separate requests; otherwise they go in one consolidated order.
 *
 * @see sc.inventorylayer.InventoryPolicyReorderPointReorderQuantity
 */
open class InventoryPolicyReorderPointReorderQuantity @JvmOverloads constructor(
    parent: ModelElement,
    reorderPoint: Int = 0,
    reorderQty: Int = 1,
    name: String? = null,
) : InventoryPolicyAbstract(parent, name) {

    private var myReorderPoint: Int = reorderPoint
    private var myReorderQty: Int = reorderQty
    private var myReorderPointDelta: Int = 1

    /**
     * If true, when a deep drop requires multiple batches to clear the
     * reorder point, place n separate replenishment requests of size
     * [reorderQty] (rather than one consolidated `n × reorderQty` order).
     */
    var separateBatchOrders: Boolean = false

    val reorderPoint: Int get() = myReorderPoint
    val reorderQty: Int get() = myReorderQty

    init {
        setInitialPolicyParameters(reorderPoint, reorderQty)
    }

    override fun checkInventory() {
        val ip = inventoryPosition
        if (ip > myReorderPoint) return
        if (ip == myReorderPoint) {
            requestReplenishment(myReorderQty)
            return
        }
        // Deep drop — figure out how many batches are needed to clear
        // the reorder point.
        val gap = (myReorderPoint - ip).toDouble()
        val n = ceil(gap / myReorderQty).toInt()
        check(n > 0) { "number of batches was zero" }
        if (separateBatchOrders) {
            repeat(n) { requestReplenishment(myReorderQty) }
        } else {
            requestReplenishment(n * myReorderQty)
        }
    }

    /**
     * The R = (delta − Q) parameterization used by optimization
     * controls — sets the reorder point so that R + Q = delta.
     */
    @set:KSLControl(controlType = ControlType.INTEGER, name = "RDelta", lowerBound = 1.0)
    var initialReorderPointDelta: Int
        get() = myReorderPointDelta
        set(value) {
            require(value >= 1) { "rDelta must be strictly positive" }
            myReorderPointDelta = value
            updateReorderPointFromDelta()
        }

    /** The reorder quantity Q (≥ 1). */
    @set:KSLControl(controlType = ControlType.INTEGER, name = "Q", lowerBound = 1.0)
    var initialReorderQty: Int
        get() = myInitialPolicyParameters[1].toInt()
        set(value) {
            require(value >= 1) { "q must be strictly positive" }
            myInitialPolicyParameters[1] = value.toDouble()
            myReorderQty = value
            updateReorderPointFromDelta()
        }

    private fun updateReorderPointFromDelta() {
        myReorderPoint = myReorderPointDelta - myReorderQty
        myInitialPolicyParameters[0] = myReorderPoint.toDouble()
    }

    /**
     * `parameters[0]` = reorder point (must be ≥ −reorderQty),
     * `parameters[1]` = reorder quantity (must be ≥ 1).
     */
    override fun setInitialPolicyParameters(parameters: DoubleArray) {
        setInitialPolicyParameters(parameters[0].toInt(), parameters[1].toInt())
    }

    /** Two-argument convenience for [setInitialPolicyParameters]. */
    fun setInitialPolicyParameters(reorderPoint: Int, reorderQty: Int) {
        require(reorderQty >= 1) {
            "The reorder quantity must be >= 1. The value was: $reorderQty"
        }
        require(reorderPoint >= -reorderQty) {
            "The reorder point must be >= -reorderQty. The value was: $reorderPoint"
        }
        myInitialPolicyParameters = doubleArrayOf(
            reorderPoint.toDouble(), reorderQty.toDouble(),
        )
    }

    override fun getPolicyParameters(): DoubleArray =
        doubleArrayOf(reorderPoint.toDouble(), reorderQty.toDouble())

    override fun setPolicyParameters(parameters: DoubleArray) {
        setPolicyParameters(parameters[0].toInt(), parameters[1].toInt())
    }

    fun setPolicyParameters(reorderPoint: Int, reorderQty: Int) {
        require(reorderQty >= 1) {
            "The reorder quantity must be >= 1. It was: $reorderQty"
        }
        require(reorderPoint >= -reorderQty) {
            "The reorder point must be >= -reorderQty. It was: $reorderPoint"
        }
        myReorderPoint = reorderPoint
        myReorderQty = reorderQty
    }
}
