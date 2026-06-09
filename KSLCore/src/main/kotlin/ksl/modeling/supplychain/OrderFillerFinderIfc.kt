package ksl.modeling.supplychain

/**
 * Strategy for locating an [OrderFillerIfc] that can fill a given order,
 * used by [OrderGenerator] when sending generated orders.
 *
 * @see sc.inventorylayer.OrderFillerFinderIfc
 */
fun interface OrderFillerFinderIfc {
    /**
     * Returns a filler for [order], or null if no suitable filler is
     * available.
     */
    fun findOrderFiller(order: SupplyChainModel.Order): OrderFillerIfc?
}
