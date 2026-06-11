package ksl.modeling.supplychain

/**
 * Default implementation of [DemandMessageIfc]. Returned by
 * [DemandFillerIfc] implementations from their negotiate path.
 *
 * @param demandFiller the filler that produced this message
 * @param timeStamp simulation time at which the message was created
 * @param canFillItemType true if the filler is willing to fill demands
 *        of the negotiated item type
 * @param requestStatus the proposed status for the demand
 * @param requestFillAmount units the filler would allocate now; must be >= 0
 * @param inventory optional view of the filler's inventory
 * @param mayPartiallyFillDemands see [DemandMessageIfc.mayPartiallyFillDemands]
 * @param mayBackLogDemands see [DemandMessageIfc.mayBackLogDemands]
 *
 * See `sc.inventorylayer.DemandMessage`
 */
data class DemandMessage @JvmOverloads constructor(
    override val demandFiller: DemandFillerIfc,
    override val timeStamp: Double,
    override val canFillItemType: Boolean = false,
    override val requestStatus: DemandStatusCode = DemandStatusCode.NoStatus,
    override val requestFillAmount: Int = 0,
    override val inventory: InventoryIfc? = null,
    override val mayPartiallyFillDemands: Boolean = false,
    override val mayBackLogDemands: Boolean = false,
) : DemandMessageIfc {

    init {
        require(requestFillAmount >= 0) {
            "requestFillAmount must be >= 0"
        }
    }
}
