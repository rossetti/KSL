package ksl.modeling.supplychain.inventory

import ksl.modeling.supplychain.*

import ksl.controls.ControlType
import ksl.controls.KSLControl
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

    // Backing for the SDelta control: the gap S − r (always >= 1). Kept in sync by
    // setInitialPolicyParameters so programmatic (r, S) writes and control writes agree.
    private var myInitialOrderUpToPointDelta: Int = orderUpToPoint - reorderPoint

    private val reviewAction = ReviewAction()

    init {
        setInitialPolicyParameters(
            reorderPoint, orderUpToPoint, reviewPeriod, initialReviewTime,
        )
    }

    /**
     * The initial reorder point r applied at the start of each replication. See the
     * continuous-review variant `InventoryPolicyReorderPointOrderUpToLevel` for the
     * initial-vs-current contract and the S = r + SDelta parameterization rationale;
     * they are identical here.
     */
    @set:KSLControl(controlType = ControlType.INTEGER, name = "r")
    var initialReorderPoint: Int
        get() = myInitialPolicyParameters[0].toInt()
        set(value) {
            require(!model.isRunning) {
                "The initial reorder point cannot be changed while the model is running; " +
                        "initial policy parameters are replication initial conditions."
            }
            myInitialPolicyParameters[0] = value.toDouble()
            myInitialPolicyParameters[1] = (value + myInitialOrderUpToPointDelta).toDouble()
        }

    /**
     * The initial order-up-to gap SDelta = S − r (>= 1), applied at the start of each
     * replication; S is derived as r + SDelta.
     */
    @set:KSLControl(controlType = ControlType.INTEGER, name = "SDelta", lowerBound = 1.0)
    var initialOrderUpToPointDelta: Int
        get() = myInitialOrderUpToPointDelta
        set(value) {
            require(!model.isRunning) {
                "The initial order-up-to gap cannot be changed while the model is running; " +
                        "initial policy parameters are replication initial conditions."
            }
            require(value >= 1) { "SDelta (the order-up-to gap S - r) must be >= 1" }
            myInitialOrderUpToPointDelta = value
            myInitialPolicyParameters[1] = (initialReorderPoint + value).toDouble()
        }

    /** The initial order-up-to level S = r + SDelta (derived, read-only). */
    val initialOrderUpToPoint: Int
        get() = myInitialPolicyParameters[1].toInt()

    /**
     * The initial review period R applied at the start of each replication.
     * The control-set path stores the value without the strict positivity check so a
     * clamped write can never throw; R > 0 is validated when the initial parameters
     * are applied at replication start (`setPolicyParameters`), which fails fast with
     * a clear message before any simulation effort is spent.
     */
    @set:KSLControl(controlType = ControlType.DOUBLE, name = "R", lowerBound = 0.0)
    var initialReviewPeriod: Double
        get() = myInitialPolicyParameters[2]
        set(value) {
            require(!model.isRunning) {
                "The initial review period cannot be changed while the model is running; " +
                        "initial policy parameters are replication initial conditions."
            }
            myInitialPolicyParameters[2] = value
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
        // keep the SDelta control's backing in sync with programmatic (r, S) writes
        myInitialOrderUpToPointDelta = orderUpToPoint - reorderPoint
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
