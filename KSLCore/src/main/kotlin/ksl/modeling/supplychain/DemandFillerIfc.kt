package ksl.modeling.supplychain

import ksl.utilities.IdentityIfc

/**
 * Objects that can fill demands. A successful [receive] moves the demand
 * from [SupplyChainModel.sent] into [SupplyChainModel.received];
 * [fillDemand] then completes the fill.
 *
 * See the legacy Java javadoc for the detailed rules around partial
 * filling and backlogging.
 *
 * @see sc.inventorylayer.DemandFillerIfc
 */
interface DemandFillerIfc : IdentityIfc, AvailabilityIfc {

    /**
     * Receive [demand] and place it in [SupplyChainModel.received], or
     * reject by transitioning to [SupplyChainModel.rejected]. The filler
     * may set the demand's [SupplyChainModel.Demand.status] to indicate
     * the rejection reason but is not required to.
     */
    fun receive(demand: SupplyChainModel.Demand)

    /**
     * Fill a previously received demand. Must be called at the same
     * simulation time as the receipt — no time may elapse between
     * [receive] and [fillDemand].
     */
    fun fillDemand(demand: SupplyChainModel.Demand)

    /**
     * Returns a [DemandMessageIfc] describing what would happen if
     * [demand] were sent now, or null if negotiation is not supported.
     */
    fun negotiate(demand: SupplyChainModel.Demand): DemandMessageIfc?

    /** True if this filler can fill demands of [demand]'s item type. */
    fun canFillItemType(demand: SupplyChainModel.Demand): Boolean

    /** True if this filler can fill demands of [type]. */
    fun canFillItemType(type: ItemType): Boolean

    /** All item types this filler is willing to fill. */
    val itemTypes: Collection<ItemType>

    /**
     * The status [demand] would receive if filled now. Only valid at the
     * current simulation time.
     */
    fun determineRequestStatus(demand: SupplyChainModel.Demand): DemandStatusCode

    /** True if [demand] would be rejected if filled now. */
    fun willReject(demand: SupplyChainModel.Demand): Boolean

    /** Carrier used to send filled demands to their destination. */
    var demandCarrier: DemandCarrierIfc?

    /** Optional pre-shipment preparer. */
    var demandPreparer: DemandPreparerIfc?
}
