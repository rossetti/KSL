package ksl.modeling.supplychain.inventory

import ksl.modeling.supplychain.*

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.simulation.ModelElement

/**
 * An (r, Q) inventory policy: orders [reorderQty] units when the
 * inventory position falls to [reorderPoint] or below.
 *
 * When the position is at or below the reorder point, the policy orders
 * `n × reorderQty` with `n = floor((reorderPoint − position) / reorderQty) + 1`,
 * the fewest whole batches that lift the position strictly above
 * [reorderPoint], so that afterwards it lies in `reorderPoint + 1 .. reorderPoint + reorderQty`.
 * If [separateBatchOrders] is true the n batches are ordered as n
 * separate requests; otherwise they go in one consolidated order.
 *
 * With lot-sized demand, when every demand is a multiple of some lot size m, the
 * difference between the position and the reorder point keeps its remainder modulo m
 * for the whole run. Long-run results then depend on the initial on-hand as well as on
 * the reorder point and quantity. Starting each replication at `reorderPoint + reorderQty`
 * avoids this, and an optimizer that changes the reorder point should change the initial
 * on-hand with it.
 *
 * See `sc.inventorylayer.InventoryPolicyReorderPointReorderQuantity`
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
    @set:KSLControl(controlType = ControlType.BOOLEAN)
    var separateBatchOrders: Boolean = false

    val reorderPoint: Int get() = myReorderPoint
    val reorderQty: Int get() = myReorderQty

    init {
        setInitialPolicyParameters(reorderPoint, reorderQty)
    }

    override fun checkInventory() {
        val ip = inventoryPosition
        if (ip > myReorderPoint) return
        // Enough batches to lift the position strictly above r: floor((r - ip)/Q) + 1. The
        // difference is non-negative here, so integer division is the floor. A ceiling instead
        // leaves the position exactly at r whenever r - ip is a multiple of Q, one batch short.
        val n = (myReorderPoint - ip) / myReorderQty + 1
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
    @set:KSLControl(controlType = ControlType.INTEGER, name = "RDelta", lowerBound = 0.0)
    var initialReorderPointDelta: Int
        get() = myReorderPointDelta
        set(value) {
            require(!model.isRunning) {
                "The initial reorder-point delta cannot be changed while the model is running; " +
                        "initial policy parameters are replication initial conditions."
            }
            require(value >= 0) { "rDelta must be >= 0" }
            myReorderPointDelta = value
            updateReorderPointFromDelta()
        }

    /** The reorder quantity Q (≥ 1). */
    @set:KSLControl(controlType = ControlType.INTEGER, name = "Q", lowerBound = 1.0)
    var initialReorderQty: Int
        get() = myInitialPolicyParameters[1].toInt()
        set(value) {
            require(!model.isRunning) {
                "The initial reorder quantity cannot be changed while the model is running; " +
                        "initial policy parameters are replication initial conditions."
            }
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
        // Keep the RDelta parameterization in step, so that setting only Q afterwards holds
        // r + Q fixed rather than recomputing r from a stale delta.
        myReorderPointDelta = reorderPoint + reorderQty
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
