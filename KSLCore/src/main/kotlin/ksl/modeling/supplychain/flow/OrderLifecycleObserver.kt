/*
 * Phase 0 — flow substrate.
 *
 * Helper B (Order variant): named lifecycle hooks for orders.
 *
 * Mirrors DemandLifecycleObserver for the 12-state Order state
 * machine.
 */
package ksl.modeling.supplychain.flow

import ksl.modeling.supplychain.OrderStateChangeListener
import ksl.modeling.supplychain.OrderStateId
import ksl.modeling.supplychain.SupplyChainModel

/**
 * Named lifecycle hooks for [SupplyChainModel.Order] state
 * transitions. Implement only the hooks you care about; the others
 * default to no-op.
 *
 * Attach via [observe].
 */
interface OrderLifecycleObserver {
    /** Fired on [OrderStateId.Sent]. */
    fun onSent(o: SupplyChainModel.Order) {}

    /** Fired on [OrderStateId.Received]. */
    fun onReceived(o: SupplyChainModel.Order) {}

    /** Fired on [OrderStateId.InProcess]. */
    fun onInProcess(o: SupplyChainModel.Order) {}

    /** Fired on [OrderStateId.Filled]. */
    fun onFilled(o: SupplyChainModel.Order) {}

    /** Fired on [OrderStateId.Shipped]. */
    fun onShipped(o: SupplyChainModel.Order) {}

    /** Fired on [OrderStateId.Delivered]. */
    fun onDelivered(o: SupplyChainModel.Order) {}

    /** Fired on [OrderStateId.Rejected]. */
    fun onRejected(o: SupplyChainModel.Order) {}

    /** Fired on [OrderStateId.Cancelled]. */
    fun onCancelled(o: SupplyChainModel.Order) {}

    /** Fired on [OrderStateId.BackLogged]. */
    fun onBackLogged(o: SupplyChainModel.Order) {}

    /** Fired on [OrderStateId.Negotiating]. */
    fun onNegotiating(o: SupplyChainModel.Order) {}
}

/**
 * Attach [observer] to this order. Returns the underlying
 * [OrderStateChangeListener] so callers can remove the
 * subscription via [SupplyChainModel.Order.removeStateChangeListener].
 */
fun SupplyChainModel.Order.observe(
    observer: OrderLifecycleObserver,
): OrderStateChangeListener {
    val adapter = OrderStateChangeListener { o, _, to ->
        when (to.stateId) {
            OrderStateId.Sent -> observer.onSent(o)
            OrderStateId.Received -> observer.onReceived(o)
            OrderStateId.InProcess -> observer.onInProcess(o)
            OrderStateId.Filled -> observer.onFilled(o)
            OrderStateId.Shipped -> observer.onShipped(o)
            OrderStateId.Delivered -> observer.onDelivered(o)
            OrderStateId.Rejected -> observer.onRejected(o)
            OrderStateId.Cancelled -> observer.onCancelled(o)
            OrderStateId.BackLogged -> observer.onBackLogged(o)
            OrderStateId.Negotiating -> observer.onNegotiating(o)
            // OrderStateId.Created / InPreparation are pre-active.
            else -> {}
        }
    }
    this.addStateChangeListener(adapter)
    return adapter
}
