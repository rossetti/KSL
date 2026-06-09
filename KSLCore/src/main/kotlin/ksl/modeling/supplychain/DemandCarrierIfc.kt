package ksl.modeling.supplychain

/**
 * Transports demands from a shipper (origin) to a receiver
 * (destination). Implementations range from the trivial
 * `NoDelayDemandCarrier` (immediate ship+deliver) to the network-
 * aware `NetworkDemandCarrierByTime` (per-edge transport-time
 * sampling with statistics).
 *
 * The two-method surface separates *what a carrier does* from
 * *whether it can do it*:
 *
 * - [transportDemand] performs the work. Implementations may throw
 *   [NoCarrierOptionException] when configuration is missing.
 * - [canShip] is a side-effect-free probe. Callers — including the
 *   carrier itself, as a self-guard in `transportDemand`, and
 *   external configuration code — may invoke it to test whether a
 *   given [demand] would route successfully without actually
 *   triggering the transport.
 *
 * @see sc.transportlayer.DemandCarrierIfc
 */
interface DemandCarrierIfc {
    /** Transport [demand] from its origin to its destination. */
    fun transportDemand(demand: SupplyChainModel.Demand)

    /**
     * Side-effect-free probe: returns true iff [demand] would route
     * successfully under the carrier's current configuration. A false
     * result indicates [transportDemand] would either throw or
     * silently drop the demand. Useful as a self-guard inside
     * carrier implementations and as a configuration test from
     * external code.
     */
    fun canShip(demand: SupplyChainModel.Demand): Boolean
}
