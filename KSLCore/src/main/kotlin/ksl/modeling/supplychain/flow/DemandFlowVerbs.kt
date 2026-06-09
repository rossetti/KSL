/*
 * Phase 0 — flow substrate.
 *
 * Helper A: named state-flow verbs.
 *
 * Replaces the hand-written verb sequences
 *   demand.receive(this); demand.process(this); ... demand.fill(qty);
 *   if (carrier != null) carrier.transportDemand(demand)
 *   else                 { demand.ship(); demand.deliver() }
 * that are duplicated across the inventory- and facility-layer
 * demand-forwarding pattern with semantically-named verbs whose
 * preconditions match the demand state machine's expectations.
 *
 * Users that need full expressive power can still call
 * `SupplyChainModel.Demand.receive`/`process`/`fill`/`ship`/`deliver`
 * directly; these helpers are additive convenience.
 */
package ksl.modeling.supplychain.flow

import ksl.modeling.supplychain.DemandCarrierIfc
import ksl.modeling.supplychain.DemandFillerIfc
import ksl.modeling.supplychain.SupplyChainModel

/**
 * Accept `this` demand and start processing it: transitions it
 * through RECEIVED → IN_PROCESS at [receiver]. Equivalent to the
 * two-call sequence `receive(receiver); process(receiver)` but
 * named for the intent ("the receiver has taken the demand and is
 * now responsible for finishing it").
 *
 * After this call returns, the demand sits at IN_PROCESS waiting
 * for whatever the receiver needs to do (forward upstream,
 * lead-time delay, backlog wait) before completion. Pair with
 * [fulfillAndDispatch] to finish the demand once the work is done.
 *
 * Required precondition: this demand is in the SENT state. Calling
 * from any other state raises the same [IllegalStateException]
 * that the underlying state machine would have raised.
 *
 * @param receiver the filler taking custody of the demand
 *        (typically the routing node or cloner itself)
 */
fun SupplyChainModel.Demand.receiveForProcessing(receiver: DemandFillerIfc) {
    this.receive(receiver)
    this.process(receiver)
}

/**
 * Fulfill `this` demand in full and dispatch it onward via
 * [carrier], or transition directly through `ship` → `deliver` if
 * [carrier] is null.
 *
 * The fulfill-then-dispatch sequence is the standard way to finish
 * a demand at its destination — used by inventory holding points
 * when stock is available, by cross-docks once the upstream clone
 * returns, and by lead-time fillers after the production delay
 * elapses. This helper consolidates all three.
 *
 * Fills the demand for its full [originalAmountDemanded] — the
 * state machine does not allow `ship` from a partially-filled
 * demand, so partial fulfilment is not a supported flow at this
 * level. Callers needing partial fill should call
 * [SupplyChainModel.Demand.fill] directly.
 *
 * Pair with [receiveForProcessing] — that helper takes the demand
 * into IN_PROCESS; this one drives it out again once the work
 * behind the demand has been done.
 *
 * Required precondition: this demand is in the IN_PROCESS state.
 *
 * @param carrier optional transport carrier. When null the demand
 *        transitions directly through `ship` → `deliver` (the
 *        zero-delay path used when no carrier is configured)
 */
@JvmOverloads
fun SupplyChainModel.Demand.fulfillAndDispatch(
    carrier: DemandCarrierIfc? = null,
) {
    this.fill(this.originalAmountDemanded)
    if (carrier != null) {
        carrier.transportDemand(this)
    } else {
        this.ship()
        this.deliver()
    }
}
