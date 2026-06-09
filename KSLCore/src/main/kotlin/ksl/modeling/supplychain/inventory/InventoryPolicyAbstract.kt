package ksl.modeling.supplychain.inventory

import ksl.modeling.supplychain.*

import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.simulation.ModelElement

/**
 * Abstract base for replenishment policies controlled by an [Inventory].
 *
 * Subclasses implement [checkInventory] (decide when to order),
 * [setPolicyParameters] and [getPolicyParameters] (the mutable
 * parameter vector), and [setInitialPolicyParameters] (the vector
 * restored at the start of each replication).
 *
 * @see sc.inventorylayer.InventoryPolicyAbstract
 */
abstract class InventoryPolicyAbstract @JvmOverloads constructor(
    parent: ModelElement,
    name: String? = null,
) : ModelElement(parent, name) {

    private var myInventory: Inventory? = null

    /**
     * Parameter values restored at the start of each replication.
     * Subclasses store into this from [setInitialPolicyParameters].
     */
    protected var myInitialPolicyParameters: DoubleArray = DoubleArray(0)

    /** Snapshot of the initial parameter vector. */
    fun getInitialPolicyParameters(): DoubleArray = myInitialPolicyParameters.copyOf()

    /**
     * If true (default), the policy parameters are reset to
     * [initialPolicyParameters] before each replication.
     */
    var resetInitialParametersFlag: Boolean = true

    /**
     * If false, suppresses the warning logged when the policy parameters
     * changed during a replication and reset is off.
     */
    var resetInitialParametersWarningFlag: Boolean = true

    /** True if the policy parameters changed at least once this replication. */
    protected var policyChangedDuringRep: Boolean = false

    /** The associated [Inventory], set via [setInventory]. */
    protected val inventory: Inventory
        get() = myInventory
            ?: error("$name not yet associated with an Inventory")

    internal fun setInventory(inventory: Inventory) {
        myInventory = inventory
    }

    protected val inventoryPosition: Int get() = inventory.inventoryPosition

    protected fun requestReplenishment(replenishmentQty: Int) {
        inventory.requestReplenishment(replenishmentQty)
    }

    /**
     * Replace the parameter vector restored at the start of each
     * replication. Subclasses validate the array shape and contents.
     */
    abstract fun setInitialPolicyParameters(parameters: DoubleArray)

    /** Decide whether to place a replenishment order now. */
    protected abstract fun checkInventory()

    /** Bridge so [Inventory] (not a subclass) can request a check. */
    internal fun checkInventoryInternal() = checkInventory()

    /** Change the policy parameters during a replication. */
    protected abstract fun setPolicyParameters(parameters: DoubleArray)

    /**
     * Bridge so [Inventory] can change parameters during a replication
     * and have [policyChangedDuringRep] flipped automatically.
     */
    internal fun setPolicyParametersInternal(parameters: DoubleArray) {
        setPolicyParameters(parameters)
        policyChangedDuringRep = true
    }

    /** Snapshot the current parameter values. */
    abstract fun getPolicyParameters(): DoubleArray

    override fun beforeReplication() {
        super.beforeReplication()
        if (resetInitialParametersFlag) {
            setPolicyParameters(myInitialPolicyParameters)
        } else if (policyChangedDuringRep && resetInitialParametersWarningFlag) {
            logger.warn {
                "Parameters for policy $name were changed during the replication; " +
                    "they were not reset for the next replication."
            }
            policyChangedDuringRep = false
        }
    }

    override fun removedFromModel() {
        super.removedFromModel()
        myInventory = null
        myInitialPolicyParameters = DoubleArray(0)
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
