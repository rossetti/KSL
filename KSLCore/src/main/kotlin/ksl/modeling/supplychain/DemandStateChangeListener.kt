package ksl.modeling.supplychain

/**
 * Notified when a demand transitions between [SupplyChainModel.DemandState]s.
 *
 * Replaces eight single-method listener interfaces in the legacy Java source
 * (`DemandListenerReceivedIfc`, `DemandListenerRejectedIfc`, …,
 * `DemandListenerDeliveredIfc`) plus the composite
 * `DemandStateChangeListenerIfc`. Discriminate on [to] (and optionally [from])
 * to handle the transitions you care about.
 *
 * @see SupplyChainModel.Demand
 */
fun interface DemandStateChangeListener {
    /**
     * @param demand the demand whose state changed
     * @param from the previous state, or null if [demand] has just been created
     * @param to the new state
     */
    fun onDemandStateChange(
        demand: SupplyChainModel.Demand,
        from: SupplyChainModel.DemandState?,
        to: SupplyChainModel.DemandState,
    )
}
