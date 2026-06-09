package ksl.modeling.supplychain

/**
 * Notified when an order transitions between [SupplyChainModel.OrderState]s.
 *
 * Replaces eight single-method listener interfaces in the legacy Java source
 * (`OrderListenerReceivedIfc`, …, `OrderListenerDeliveredIfc`).
 * Discriminate on [to] to handle the transitions you care about.
 *
 * @see SupplyChainModel.Order
 */
fun interface OrderStateChangeListener {
    /**
     * @param order the order whose state changed
     * @param from the previous state, or null if the order has just been created
     * @param to the new state
     */
    fun onOrderStateChange(
        order: SupplyChainModel.Order,
        from: SupplyChainModel.OrderState?,
        to: SupplyChainModel.OrderState,
    )
}
