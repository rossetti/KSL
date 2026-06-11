package ksl.modeling.supplychain

/**
 * Transports orders from a shipper (origin) to a receiver
 * (destination). Mirrors [DemandCarrierIfc] for orders; same
 * two-method shape and the same separation of *what* and
 * *whether*.
 *
 * See `sc.transportlayer.OrderCarrierIfc`
 */
interface OrderCarrierIfc {
    /** Transport [order] from its origin to its destination. */
    fun transportOrder(order: SupplyChainModel.Order)

    /**
     * Side-effect-free probe: returns true iff [order] would route
     * successfully under the carrier's current configuration. See
     * [DemandCarrierIfc.canShip] for the rationale.
     */
    fun canShip(order: SupplyChainModel.Order): Boolean
}
