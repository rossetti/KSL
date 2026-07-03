package ksl.modeling.supplychain.inventory

import ksl.modeling.supplychain.*

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.simulation.ModelElement

/**
 * An (r, S) inventory policy: when the inventory position falls to
 * [reorderPoint] or below, orders enough units to bring the position
 * up to [orderUpToPoint].
 *
 * See `sc.inventorylayer.InventoryPolicyReorderPointOrderUpToLevel`
 */
open class InventoryPolicyReorderPointOrderUpToLevel @JvmOverloads constructor(
    parent: ModelElement,
    reorderPoint: Int = 0,
    orderUpToPoint: Int = 1,
    name: String? = null,
) : InventoryPolicyAbstract(parent, name) {

    private var myReorderPoint: Int = reorderPoint
    private var myOrderUpToPoint: Int = orderUpToPoint

    // Backing for the SDelta control: the gap S − r (always >= 1). Kept in sync by
    // setInitialPolicyParameters so programmatic (r, S) writes and control writes agree.
    private var myInitialOrderUpToPointDelta: Int = orderUpToPoint - reorderPoint

    val reorderPoint: Int get() = myReorderPoint
    val orderUpToPoint: Int get() = myOrderUpToPoint

    init {
        setInitialPolicyParameters(reorderPoint, orderUpToPoint)
    }

    /**
     * The initial reorder point r applied at the start of each replication.
     *
     * Controls configure replication INITIAL conditions: the current policy parameters
     * are re-seeded from the initial values at each `beforeReplication()`, so every
     * replication begins under the same settings. Changing an initial value during a
     * replication is therefore an error (guarded); the current parameters remain
     * changeable within a replication via `setPolicyParameters` (e.g. by future
     * dynamic policies).
     *
     * The order-up-to level is parameterized as S = r + SDelta (see
     * [initialOrderUpToPointDelta]), following the RDelta precedent of the (r,Q)
     * policy: every clamped (r, SDelta >= 1) combination satisfies r < S by
     * construction, so no cross-field validation is needed at control-set time and
     * optimizers see a box-constrained space. S >= 1 is still validated when the
     * initial parameters are applied at replication start.
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
     * replication; S is derived as r + SDelta. See [initialReorderPoint] for the
     * initial-vs-current contract and the rationale for the delta parameterization.
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
        // keep the SDelta control's backing in sync with programmatic (r, S) writes
        myInitialOrderUpToPointDelta = orderUpToPoint - reorderPoint
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
