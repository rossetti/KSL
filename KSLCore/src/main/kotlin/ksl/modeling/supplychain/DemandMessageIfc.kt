package ksl.modeling.supplychain

/**
 * Information returned by a [DemandFillerIfc] in response to a negotiation
 * request for a single demand. A filler that cannot negotiate returns null;
 * otherwise it returns a [DemandMessageIfc] describing what would happen
 * if the demand were sent at the current time.
 *
 * All responses are relative to the demand instance handed to the filler.
 *
 * @see sc.inventorylayer.DemandMessageIfc
 */
interface DemandMessageIfc {
    /** The filler that produced this message. */
    val demandFiller: DemandFillerIfc

    /** True if the filler is willing to fill demands of this item type. */
    val canFillItemType: Boolean

    /** The status the demand would receive if processed now. */
    val requestStatus: DemandStatusCode

    /** The number of units the filler would allocate now. */
    val requestFillAmount: Int

    /** An optional view of the filler's underlying inventory state. */
    val inventory: InventoryIfc?

    /**
     * Whether the filler may partially fill demands. Does not imply that
     * a partial fill will occur — only that it is possible. A demand that
     * forbids partial filling sent to a filler that allows partial filling
     * is still valid; the filler is responsible for not splitting it.
     */
    val mayPartiallyFillDemands: Boolean

    /**
     * Whether the filler may backlog demands. Does not imply that
     * backlogging will occur — only that it is possible. The sender must
     * be willing to accept that the demand might be backlogged.
     */
    val mayBackLogDemands: Boolean

    /** Simulation time at which this message was created/valid. */
    val timeStamp: Double
}
