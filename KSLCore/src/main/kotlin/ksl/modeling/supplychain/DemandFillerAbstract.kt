package ksl.modeling.supplychain

import ksl.simulation.ModelElement

/**
 * Base class for objects that implement [DemandFillerIfc]. Provides
 * availability state, carrier/preparer plumbing, and per-replication
 * initialization. Subclasses must implement the demand-handling
 * abstract methods.
 *
 * Subclasses that override [initialize] must call `super.initialize()`.
 *
 * @param parent the [SupplyChainModel] this filler belongs to
 * @param initialAvailability availability at the start of each replication
 * @param name optional model-element name
 *
 * @see sc.inventorylayer.DemandFillerAbstract
 */
abstract class DemandFillerAbstract @JvmOverloads constructor(
    parent: ModelElement,
    initialAvailability: Boolean = true,
    name: String? = null,
) : ModelElement(parent, name), DemandFillerIfc {
    // Note: parent is ModelElement (not SupplyChainModel) so that nested
    // filler patterns like LeadTimeOrderFiller.InnerDemandFiller (parented
    // to its enclosing order filler) compile. Top-level construction with
    // a SupplyChainModel parent still works because SupplyChainModel
    // extends ModelElement.

    /** Availability at the start of each replication. */
    var initialAvailability: Boolean = initialAvailability

    private var _isAvailable: Boolean = initialAvailability

    final override val isAvailable: Boolean
        get() = _isAvailable

    /**
     * Toggle current-replication availability. Protected because
     * subclasses (e.g., a filler that becomes unavailable on backlog)
     * may need to flip the flag; external callers should not.
     */
    protected fun setAvailability(flag: Boolean) {
        _isAvailable = flag
    }

    override var demandCarrier: DemandCarrierIfc? = null
    override var demandPreparer: DemandPreparerIfc? = null

    /**
     * Resets availability to [initialAvailability] before each replication.
     * The Java original had a typo (`intialize`) so it never actually
     * overrode the framework hook; this port fixes the override.
     */
    override fun initialize() {
        super.initialize()
        _isAvailable = initialAvailability
    }
}
