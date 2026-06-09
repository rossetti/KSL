package ksl.modeling.supplychain.inventory

import ksl.modeling.supplychain.*

import ksl.simulation.ModelElement

/**
 * An (r, S) inventory policy: when the inventory position falls to
 * [reorderPoint] or below, orders enough units to bring the position
 * up to [orderUpToPoint].
 *
 * @see sc.inventorylayer.InventoryPolicyReorderPointOrderUpToLevel
 */
open class InventoryPolicyReorderPointOrderUpToLevel @JvmOverloads constructor(
    parent: ModelElement,
    reorderPoint: Int = 0,
    orderUpToPoint: Int = 1,
    name: String? = null,
) : InventoryPolicyAbstract(parent, name) {

    private var myReorderPoint: Int = reorderPoint
    private var myOrderUpToPoint: Int = orderUpToPoint

    val reorderPoint: Int get() = myReorderPoint
    val orderUpToPoint: Int get() = myOrderUpToPoint

    init {
        setInitialPolicyParameters(reorderPoint, orderUpToPoint)
    }

    override fun checkInventory() {
        if (inventoryPosition <= myReorderPoint) {
            val orderSize = myOrderUpToPoint - inventoryPosition
            requestReplenishment(orderSize)
        }
    }

    override fun setInitialPolicyParameters(parameters: DoubleArray) {
        setInitialPolicyParameters(parameters[0].toInt(), parameters[1].toInt())
    }

    /** Two-argument convenience for [setInitialPolicyParameters]. */
    fun setInitialPolicyParameters(reorderPoint: Int, orderUpToPoint: Int) {
        require(orderUpToPoint >= 1) { "The order up to point must be >= 1" }
        require(reorderPoint < orderUpToPoint) {
            "The reorder point must be < order up to point"
        }
        myInitialPolicyParameters = doubleArrayOf(
            reorderPoint.toDouble(), orderUpToPoint.toDouble(),
        )
    }

    override fun getPolicyParameters(): DoubleArray =
        doubleArrayOf(reorderPoint.toDouble(), orderUpToPoint.toDouble())

    override fun setPolicyParameters(parameters: DoubleArray) {
        setPolicyParameters(parameters[0].toInt(), parameters[1].toInt())
    }

    fun setPolicyParameters(reorderPoint: Int, orderUpToPoint: Int) {
        require(orderUpToPoint >= 1) { "The order up to point must be >= 1" }
        require(reorderPoint < orderUpToPoint) {
            "The reorder point must be < order up to point"
        }
        myReorderPoint = reorderPoint
        myOrderUpToPoint = orderUpToPoint
    }
}
