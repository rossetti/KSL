package ksl.modeling.supplychain

/**
 * Indicates whether an object is currently available to participate in
 * supply-chain activity, e.g., a demand filler accepting new demands or
 * a carrier accepting new shipments.
 *
 * @see sc.inventorylayer.AvailabilityIfc in the legacy Java source.
 */
interface AvailabilityIfc {
    /** True when this object can currently be selected for use. */
    val isAvailable: Boolean
}
