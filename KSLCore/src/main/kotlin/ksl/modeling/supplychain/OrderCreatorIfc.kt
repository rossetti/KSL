package ksl.modeling.supplychain

/**
 * Strategy for creating orders from scratch. Used by [OrderGenerator]
 * at each event firing. Implementations decide what item types and
 * quantities go on the order.
 *
 * @see sc.inventorylayer.OrderCreatorIfc
 */
fun interface OrderCreatorIfc {
    /**
     * Creates and returns a new order, or null if no order should be
     * created at this call (e.g., probabilistic skipping).
     */
    fun createOrder(): SupplyChainModel.Order?

    /** True if [createOrder] may produce an order containing [type]. */
    fun createsType(type: ItemType): Boolean = false
}
