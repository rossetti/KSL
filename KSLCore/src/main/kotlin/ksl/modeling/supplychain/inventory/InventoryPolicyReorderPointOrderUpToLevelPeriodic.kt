package ksl.modeling.supplychain.inventory

import ksl.modeling.supplychain.*

import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement

/**
 * A periodic-review (r, S) inventory policy: every [reviewPeriod] time
 * units (starting at [initialReviewTime]), checks the inventory position
 * and orders up to [orderUpToPoint] if at or below [reorderPoint].
 *
 * Unlike the continuous-review variants, [checkInventory] is a no-op
 * here — review happens via a scheduled event.
 *
 * See `sc.inventorylayer.InventoryPolicyReorderPointOrderUpToLevelPeriodic`
 */
open class InventoryPolicyReorderPointOrderUpToLevelPeriodic @JvmOverloads constructor(
    parent: ModelElement,
    reorderPoint: Int = 0,
    orderUpToPoint: Int = 1,
    reviewPeriod: Double = 1.0,
    initialReviewTime: Double = 0.0,
    name: String? = null,
) : InventoryPolicyAbstract(parent, name) {

    private var myReorderPoint: Int = reorderPoint
    private var myOrderUpToPoint: Int = orderUpToPoint
    private var myReviewPeriod: Double = reviewPeriod
    private var myInitialReviewTime: Double = initialReviewTime

    val reorderPoint: Int get() = myReorderPoint
    val orderUpToPoint: Int get() = myOrderUpToPoint
    val reviewPeriod: Double get() = myReviewPeriod
    val initialReviewTime: Double get() = myInitialReviewTime

    private val reviewAction = ReviewAction()

    init {
        setInitialPolicyParameters(
            reorderPoint, orderUpToPoint, reviewPeriod, initialReviewTime,
        )
    }

    override fun checkInventory() { /* periodic — review on schedule, not on demand */ }

    override fun initialize() {
        super.initialize()
        reviewAction.schedule(myInitialReviewTime)
    }

    private inner class ReviewAction : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            if (inventoryPosition <= myReorderPoint) {
                val orderSize = myOrderUpToPoint - inventoryPosition
                requestReplenishment(orderSize)
            }
            schedule(myReviewPeriod)
        }
    }

    /**
     * `parameters` may have length 2, 3, or 4. Missing trailing values
     * are taken from [myInitialPolicyParameters] — matches Java behavior.
     */
    override fun setInitialPolicyParameters(parameters: DoubleArray) {
        val r = parameters[0].toInt()
        val s = parameters[1].toInt()
        val period = if (parameters.size >= 3) parameters[2]
            else myInitialPolicyParameters[2]
        val first = if (parameters.size >= 4) parameters[3]
            else myInitialPolicyParameters[3]
        setInitialPolicyParameters(r, s, period, first)
    }

    fun setInitialPolicyParameters(
        reorderPoint: Int,
        orderUpToPoint: Int,
        reviewPeriod: Double,
        initialReviewTime: Double,
    ) {
        require(orderUpToPoint >= 1) { "The order up to point must be >= 1" }
        require(reorderPoint < orderUpToPoint) {
            "The reorder point must be < order up to point"
        }
        require(reviewPeriod > 0.0) { "The review period must be > 0.0" }
        require(initialReviewTime >= 0.0) { "The initial review time must be >= 0.0" }
        myInitialPolicyParameters = doubleArrayOf(
            reorderPoint.toDouble(),
            orderUpToPoint.toDouble(),
            reviewPeriod,
            initialReviewTime,
        )
    }

    override fun getPolicyParameters(): DoubleArray = doubleArrayOf(
        myReorderPoint.toDouble(),
        myOrderUpToPoint.toDouble(),
        myReviewPeriod,
        myInitialReviewTime,
    )

    override fun setPolicyParameters(parameters: DoubleArray) {
        val r = parameters[0].toInt()
        val s = parameters[1].toInt()
        val period = if (parameters.size >= 3) parameters[2] else myReviewPeriod
        val first = if (parameters.size >= 4) parameters[3] else myInitialReviewTime
        setPolicyParameters(r, s, period, first)
    }

    fun setPolicyParameters(
        reorderPoint: Int,
        orderUpToPoint: Int,
        reviewPeriod: Double = myReviewPeriod,
        initialReviewTime: Double = myInitialReviewTime,
    ) {
        require(orderUpToPoint >= 1) { "The order up to point must be >= 1" }
        require(reorderPoint < orderUpToPoint) {
            "The reorder point must be < order up to point"
        }
        require(reviewPeriod > 0.0) { "The review period must be > 0.0" }
        require(initialReviewTime >= 0.0) { "The initial review time must be >= 0.0" }
        myReorderPoint = reorderPoint
        myOrderUpToPoint = orderUpToPoint
        myReviewPeriod = reviewPeriod
        myInitialReviewTime = initialReviewTime
    }
}
