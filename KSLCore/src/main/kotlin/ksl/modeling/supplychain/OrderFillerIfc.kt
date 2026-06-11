package ksl.modeling.supplychain

import ksl.utilities.IdentityIfc

/**
 * Objects that can fill orders.
 *
 * See `sc.inventorylayer.OrderFillerIfc`
 */
interface OrderFillerIfc : IdentityIfc, AvailabilityIfc {

    /**
     * Receive [order] for filling, or reject it. See [DemandFillerIfc.receive]
     * for the analogous demand-level rules; the order-level adaptation is
     * that every demand on the order is routed to a per-demand filler
     * before processing begins.
     */
    fun receive(order: SupplyChainModel.Order)

    /**
     * Fill a previously received order. Must be called at the same
     * simulation time as the receipt.
     */
    fun fill(order: SupplyChainModel.Order)

    /**
     * True if every item type on [order] can be filled by this filler.
     * Does not consider backlogging or partial-shipping constraints.
     */
    fun canFillItemTypes(order: SupplyChainModel.Order): Boolean

    /** True if this filler can fill the item type of [demand]. */
    fun canFillItemType(demand: SupplyChainModel.Demand): Boolean

    /**
     * Returns an [OrderMessageIfc] describing what would happen if
     * [order] were sent now, or null if negotiation is not supported.
     */
    fun negotiate(order: SupplyChainModel.Order): OrderMessageIfc?
}
