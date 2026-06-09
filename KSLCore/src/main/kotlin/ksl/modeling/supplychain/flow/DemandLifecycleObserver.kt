/*
 * Phase 0 — flow substrate.
 *
 * Helper B (Demand variant): named lifecycle hooks.
 *
 * Replaces the boilerplate
 *   DemandStateChangeListener { d, _, to ->
 *       if (to.stateId === DemandStateId.Delivered) { ... }
 *   }
 * with an interface whose hook methods name the lifecycle events
 * directly. Users implement only the hooks they care about.
 *
 * Coexists with the raw DemandStateChangeListener; the framework
 * wires this observer to the underlying listener with a single
 * adapter, so there is no performance penalty.
 */
package ksl.modeling.supplychain.flow

import ksl.modeling.supplychain.DemandStateChangeListener
import ksl.modeling.supplychain.DemandStateId
import ksl.modeling.supplychain.SupplyChainModel

/**
 * Named lifecycle hooks for [SupplyChainModel.Demand] state
 * transitions. Implement only the hooks you care about; the others
 * default to no-op.
 *
 * Each hook fires exactly when the underlying state machine
 * transitions the demand into the matching state. Note that the
 * pre-active states ([DemandStateId.InPreparation],
 * [DemandStateId.Negotiating], [DemandStateId.Sent]) are silent in
 * the underlying state machine and therefore have no hooks here.
 * `received` / `rejected` are gated by order membership (see
 * `Demand.transitionTo`); hooks fire only when the demand is not
 * part of an order — order-attached demands route through the
 * order's own lifecycle observer.
 *
 * Attach via [observe].
 */
interface DemandLifecycleObserver {
    /** Fired when the demand transitions to [DemandStateId.Received]. */
    fun onReceived(d: SupplyChainModel.Demand) {}

    /** Fired when the demand transitions to [DemandStateId.InProcess]. */
    fun onInProcess(d: SupplyChainModel.Demand) {}

    /** Fired when the demand transitions to [DemandStateId.Filled]. */
    fun onFilled(d: SupplyChainModel.Demand) {}

    /** Fired when the demand transitions to [DemandStateId.Shipped]. */
    fun onShipped(d: SupplyChainModel.Demand) {}

    /**
     * Fired when the demand transitions to [DemandStateId.Delivered]
     * — the carrier finished transport and the items are physically
     * at the destination.  Not terminal: the destination's delivery
     * endpoint typically transitions on to [DemandStateId.Stored]
     * (or back to [DemandStateId.Shipped] for multi-hop pass-through).
     */
    fun onDelivered(d: SupplyChainModel.Demand) {}

    /**
     * Fired when the demand transitions to [DemandStateId.Stored]
     * — the destination has finalised receipt and accounting listeners
     * fire here.  Terminal state for storing destinations.
     */
    fun onStored(d: SupplyChainModel.Demand) {}

    /** Fired when the demand transitions to [DemandStateId.Rejected]. */
    fun onRejected(d: SupplyChainModel.Demand) {}

    /** Fired when the demand transitions to [DemandStateId.Cancelled]. */
    fun onCancelled(d: SupplyChainModel.Demand) {}

    /** Fired when the demand transitions to [DemandStateId.BackLogged]. */
    fun onBackLogged(d: SupplyChainModel.Demand) {}
}

/**
 * Attach [observer] to this demand. Returns the underlying
 * [DemandStateChangeListener] so callers can remove the
 * subscription later via
 * [SupplyChainModel.Demand.removeStateChangeListener].
 */
fun SupplyChainModel.Demand.observe(
    observer: DemandLifecycleObserver,
): DemandStateChangeListener {
    val adapter = DemandStateChangeListener { d, _, to ->
        when (to.stateId) {
            DemandStateId.Received -> observer.onReceived(d)
            DemandStateId.InProcess -> observer.onInProcess(d)
            DemandStateId.Filled -> observer.onFilled(d)
            DemandStateId.Shipped -> observer.onShipped(d)
            DemandStateId.Delivered -> observer.onDelivered(d)
            DemandStateId.Stored -> observer.onStored(d)
            DemandStateId.Rejected -> observer.onRejected(d)
            DemandStateId.Cancelled -> observer.onCancelled(d)
            DemandStateId.BackLogged -> observer.onBackLogged(d)
            // Pre-active states are silent in the underlying machine
            // and therefore unreachable here. else exhausts.
            else -> {}
        }
    }
    this.addStateChangeListener(adapter)
    return adapter
}
