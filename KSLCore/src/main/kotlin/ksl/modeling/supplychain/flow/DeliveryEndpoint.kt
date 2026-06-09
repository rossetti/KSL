/*
 * Phase 1.D — destinations and delivery endpoints.
 *
 * A demand reaches Delivered when its carrier completes transport.
 * What happens between Delivered and the demand's terminal state
 * (Stored or, for re-shipped multi-hop pass-through, the next
 * Shipped leg) is the destination's business.  The framework
 * encapsulates that business in a [DeliveryEndpointIfc] —
 * configurable, replaceable, and inert by default.
 *
 * See `docs/supply-chain-framework-design.md` §3.5.
 */
package ksl.modeling.supplychain.flow

import ksl.modeling.supplychain.SupplyChainModel

/**
 * Destination-side hook fired when a demand transitions into
 * [ksl.modeling.supplychain.DemandStateId.Delivered].  The endpoint
 * is responsible for advancing the demand to its terminal state —
 * by calling `demand.store()` (for storing destinations) or by
 * triggering a re-ship via `demand.ship()` (for pass-through
 * destinations such as cross-docks with downstream routing).
 *
 * Endpoints are persistent simulation structure — typically slotted
 * onto a [ksl.modeling.supplychain.inventory.NetworkNodeIfc].  The
 * framework attaches a Delivered-state observer to every demand at
 * creation time; that observer looks up the demand's destination
 * and invokes [onDelivered] on its endpoint.
 *
 * @see PassThroughStorageEndpoint
 * @see SupplyChainModel.Demand.store
 */
interface DeliveryEndpointIfc {
    /**
     * Called by the framework Delivered observer when [demand]
     * transitions into Delivered at this endpoint's destination.
     *
     * Default implementations:
     * - [PassThroughStorageEndpoint] — calls `demand.store()`
     *   immediately, finishing the demand at Stored at the same
     *   simulation time as Delivered.
     * - A `RoutingEndpoint` (Phase 1.D follow-on) — looks up the
     *   next hop in a routing table and triggers `demand.ship()`.
     * - A `Dock` (Phase 1.E) — adds a sampled service-time delay
     *   before invoking either of the above.
     */
    fun onDelivered(demand: SupplyChainModel.Demand)
}

/**
 * Default delivery endpoint for storing destinations.  On every
 * Delivered transition, immediately calls `demand.store()`,
 * advancing the demand to Stored at the same simulation time.
 *
 * Idempotent and stateless — safe to share across destinations.
 * The framework's Delivered observer uses this object as the
 * fallback when a demand's destination has no explicit endpoint
 * configured (e.g. customers, or any destination that didn't opt
 * in to dock modelling).
 */
object PassThroughStorageEndpoint : DeliveryEndpointIfc {
    override fun onDelivered(demand: SupplyChainModel.Demand) {
        demand.store()
    }
}
