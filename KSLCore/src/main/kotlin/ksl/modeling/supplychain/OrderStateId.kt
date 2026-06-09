package ksl.modeling.supplychain

/**
 * Type-safe identifier for the lifecycle states of a
 * [SupplyChainModel.Order]. Each state in the order state machine
 * has exactly one [OrderStateId] singleton; sealed-class hierarchy
 * lets listener bodies use exhaustive `when` and referential
 * identity (`===`) checks instead of stringly-typed comparison on
 * `displayName`.
 *
 * The state-machine implementation lives on
 * [SupplyChainModel.OrderState] and uses these IDs to label each
 * concrete state class.
 *
 * @property displayName the human-readable name of the state, with
 *           the "ORDER_" prefix preserved from the original Java
 *           naming so existing `stateName`-based code paths keep
 *           working.
 */
sealed class OrderStateId(val displayName: String) {
    object Created       : OrderStateId("ORDER_CREATED")
    object InPreparation : OrderStateId("ORDER_IN_PREPARATION")
    object Sent          : OrderStateId("ORDER_SENT")
    object Negotiating   : OrderStateId("ORDER_NEGOTIATING")
    object Received      : OrderStateId("ORDER_RECEIVED")
    object InProcess     : OrderStateId("ORDER_IN_PROCESS")
    object BackLogged    : OrderStateId("ORDER_BACKLOGGED")
    object Rejected      : OrderStateId("ORDER_REJECTED")
    object Cancelled     : OrderStateId("ORDER_CANCELLED")
    object Filled        : OrderStateId("ORDER_FILLED")
    object Shipped       : OrderStateId("ORDER_SHIPPED")
    object Delivered     : OrderStateId("ORDER_DELIVERED")

    override fun toString(): String = displayName
}
