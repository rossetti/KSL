package ksl.modeling.supplychain

/**
 * The order-level counterpart to [DemandMessageIfc]. Aggregates one
 * [DemandMessageIfc] per demand on the negotiated order plus order-level
 * metadata.
 *
 * See `sc.inventorylayer.OrderMessageIfc`
 */
interface OrderMessageIfc {
    /** The order filler that produced this message. */
    val orderFiller: OrderFillerIfc

    /**
     * True if every item type on the order can be filled by [orderFiller].
     * False if the order has no demand messages yet, or any added message
     * has [DemandMessageIfc.canFillItemType] false, or any added entry is
     * null (sentinel for "no information available").
     */
    val canFillItemTypes: Boolean

    /**
     * The list of per-demand messages, in the order they were added.
     * Entries may be null (sentinel for "no information available").
     */
    val demandMessages: List<DemandMessageIfc?>

    /** Simulation time at which this message was created. */
    val timeStamp: Double
}
