package ksl.modeling.supplychain

/**
 * Default rejection-handling listener that dispatches on the demand's
 * [SupplyChainModel.Demand.status] when the demand reaches the
 * rejected state. Each dispatch method has a default
 * `IllegalStateException`-throwing body that subclasses can override.
 *
 * Replaces the Java `DemandListenerRejectedIfc` implementation (we
 * collapsed listeners to a single SAM in Session 3); this class
 * filters [DemandStateChangeListener] events on
 * `to.stateId === DemandStateId.Rejected`.
 *
 * @see sc.inventorylayer.DemandRejectionListener
 */
open class DemandRejectionListener : DemandStateChangeListener {
    override fun onDemandStateChange(
        demand: SupplyChainModel.Demand,
        from: SupplyChainModel.DemandState?,
        to: SupplyChainModel.DemandState,
    ) {
        if (to.stateId !== DemandStateId.Rejected) return
        when (demand.status) {
            DemandStatusCode.FillerUnavailable -> fillerUnavailable(demand)
            DemandStatusCode.ItemTypeMismatch -> itemTypeMismatch(demand)
            DemandStatusCode.NonBackloggableDemandToBackloggingReceiverNotImmediateFill ->
                nonBackloggableToBackloggingReceiver(demand)
            DemandStatusCode.BackloggableDemandToNonBackloggingReceiverNotImmediateFill ->
                backloggableToNonBackloggingReceiver(demand)
            DemandStatusCode.NonBackloggableDemandToNonBackloggingReceiverNotImmediateFill ->
                nonBackloggableToNonBackloggingReceiver(demand)
            DemandStatusCode.OrderRejected -> orderRejected(demand)
            else -> { /* no dispatch */ }
        }
    }

    protected open fun fillerUnavailable(demand: SupplyChainModel.Demand): Unit =
        error("Replenishment demand was rejected: filler was unavailable.")

    protected open fun itemTypeMismatch(demand: SupplyChainModel.Demand): Unit =
        error("Replenishment demand was rejected: item type mismatch for $demand")

    protected open fun nonBackloggableToBackloggingReceiver(
        demand: SupplyChainModel.Demand,
    ): Unit = error(
        "Replenishment demand was rejected: non-backloggable demand sent " +
            "to backlogging filler with no immediate fill."
    )

    protected open fun backloggableToNonBackloggingReceiver(
        demand: SupplyChainModel.Demand,
    ): Unit = error(
        "Replenishment demand was rejected: backloggable demand sent " +
            "to non-backlogging filler with no immediate fill."
    )

    protected open fun nonBackloggableToNonBackloggingReceiver(
        demand: SupplyChainModel.Demand,
    ): Unit = error(
        "Replenishment demand was rejected: non-backloggable demand sent " +
            "to non-backlogging filler with no immediate fill."
    )

    protected open fun orderRejected(demand: SupplyChainModel.Demand): Unit =
        error("Replenishment demand was rejected: the order it was placed on was rejected.")
}
