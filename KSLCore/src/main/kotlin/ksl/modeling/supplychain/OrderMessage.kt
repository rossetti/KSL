package ksl.modeling.supplychain

/**
 * Default implementation of [OrderMessageIfc]. Built up by an
 * [OrderFillerIfc] one demand at a time via [add].
 *
 * @param orderFiller the filler producing this message
 * @param timeStamp simulation time at which the message was created
 *
 * @see sc.inventorylayer.OrderMessage
 */
class OrderMessage(
    override val orderFiller: OrderFillerIfc,
    override val timeStamp: Double,
) : OrderMessageIfc {

    private val myDemandMessages: MutableList<DemandMessageIfc?> = mutableListOf()
    private var canFillItemTypesFlag: Boolean = true

    /** Read-only view of the per-demand messages. Entries may be null. */
    override val demandMessages: List<DemandMessageIfc?>
        get() = myDemandMessages

    /**
     * Returns false for an empty message (i.e., before [add] has been
     * called) — matches the Java behavior.
     */
    override val canFillItemTypes: Boolean
        get() = myDemandMessages.isNotEmpty() && canFillItemTypesFlag

    /** Number of demand messages added so far. */
    val size: Int get() = myDemandMessages.size

    /** True iff no demand messages have been added yet. */
    val isEmpty: Boolean get() = myDemandMessages.isEmpty()

    /** Returns the demand message at [index], or null if a null was added there. */
    fun get(index: Int): DemandMessageIfc? = myDemandMessages[index]

    /**
     * Appends a per-demand message. A null entry — or a message whose
     * [DemandMessageIfc.canFillItemType] is false — permanently flips
     * [canFillItemTypes] to false. The legacy Java code de-referenced the
     * message before the null check (NPE bug); this port fixes the order.
     */
    fun add(message: DemandMessageIfc?) {
        if (message == null || !message.canFillItemType) {
            canFillItemTypesFlag = false
        }
        myDemandMessages += message
    }
}
