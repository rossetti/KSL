package ksl.modeling.supplychain

/**
 * Type-safe identifier for the lifecycle states of a
 * [SupplyChainModel.Demand]. Each state in the demand state machine
 * has exactly one [DemandStateId] singleton; the sealed-class
 * hierarchy lets listener bodies use exhaustive `when` and
 * referential identity (`===`) checks instead of stringly-typed
 * comparison on `displayName`.
 *
 * The state-machine implementation lives on
 * [SupplyChainModel.DemandState] and uses these IDs to label each
 * concrete state class.
 *
 * @property displayName the human-readable name of the state. Matches
 *           the string previously returned by `DemandState.stateName`;
 *           kept stable so that `stateName`-based code paths and any
 *           external persistence keep working.
 */
sealed class DemandStateId(val displayName: String) {
    object InPreparation : DemandStateId("IN_PREPARATION")
    object Negotiating   : DemandStateId("NEGOTIATING")
    object Sent          : DemandStateId("SENT")
    object Received      : DemandStateId("RECEIVED")
    object InProcess     : DemandStateId("IN_PROCESS")
    object BackLogged    : DemandStateId("BACKLOGGED")
    object Rejected      : DemandStateId("REJECTED")
    object Cancelled     : DemandStateId("CANCELLED")
    object Filled        : DemandStateId("FILLED")
    object Shipped       : DemandStateId("SHIPPED")
    object Delivered     : DemandStateId("DELIVERED")
    object Stored        : DemandStateId("STORED")

    override fun toString(): String = displayName
}
