package ksl.modeling.supplychain.cost

import ksl.modeling.supplychain.DemandSenderIfc
import ksl.modeling.supplychain.ItemType
import ksl.modeling.supplychain.inventory.NetworkNodeIfc
import ksl.modeling.supplychain.transport.DemandLoadBuilder

/**
 * Typed snapshot contracts for cost calculators.  Each interface
 * represents the data a cost calculator's
 * `ModelElementObserver.replicationEnded` callback needs from one
 * source ModelElement, captured at the moment that source's
 * within-replication statistics are stable (immediately after the
 * source's own `replicationEnded()` runs).
 *
 * These are *typed snapshots*, not live views: every field value is
 * sampled at construction time.  Concrete data-class implementations
 * live in `CostObservablesData`.
 *
 * **Warmup**: every field is sourced from KSL's within-replication
 * statistics (Counter.value, Response.average,
 * TWResponse.weightedAverage), which reset at the warmup event.  The
 * snapshot fields therefore reflect only the post-warmup observation
 * window, with no explicit time-arithmetic on the caller's part.
 */
interface InventoryCostObservables {
    /** Item type the source `Inventory` holds. */
    val item: ItemType
    /** Time-weighted on-hand units over the post-warmup window. */
    val avgOnHand: Double
    /** Time-weighted on-order units. */
    val avgOnOrder: Double
    /**
     * Time-weighted backlog units, sourced from the inventory's
     * backlog policy.  0.0 when the inventory has no backlog policy
     * attached.
     */
    val avgBacklog: Double
    /** Count of replenishment orders placed (post-warmup). */
    val orderCount: Double
    /** Sum of units ordered across all replenishment orders. */
    val totalUnitsOrdered: Double
    /** Count of stockout events. */
    val stockoutCount: Double
    /** Count of lost-sale events (subset of stockouts). */
    val lostSaleCount: Double
    /** Sum of (remainingDemand − amountOnHand) across stockouts. */
    val totalUnitsShort: Double
}

/**
 * Per-edge observables from a carrier.  An edge is identified by
 * its supplier (the network node owning the carrier or, for the ES,
 * the network itself) and its customer (the [DemandSenderIfc]
 * destination of shipments on this edge).
 */
interface EdgeCostObservables {
    val supplier: NetworkNodeIfc?
    val customer: DemandSenderIfc
    /**
     * Count of completed shipments on this edge (post-warmup).
     * Mirrors the carrier's per-destination
     * `getNumberOfDemandShipments`.
     */
    val shipmentCount: Double
    /**
     * Cumulative weight of all shipments on this edge (post-warmup).
     * For a per-demand carrier this is the sum of `demand.weight`;
     * for a load carrier it is the sum of `load.weight`.
     */
    val totalLoadWeight: Double
    /** Cumulative cube; analogous to [totalLoadWeight]. */
    val totalLoadCube: Double
}

/**
 * Observables from a [DemandLoadBuilder].  Drives the
 * shipment-builder holding-cost line per-(node, item) when the
 * builder was constructed with `itemTypes` non-empty (the
 * MultiEchelonNetwork wires this automatically).
 */
interface BuilderCostObservables {
    val builder: DemandLoadBuilder
    /**
     * Time-weighted on-hand units of [item] queued in this builder
     * during the post-warmup window, or null if this builder is not
     * tracking [item].
     */
    fun unitsOnHandAvg(item: ItemType): Double?
    /** Count of loads this builder formed (and the carrier shipped). */
    val totalLoadsShipped: Double
}

/**
 * External-supplier-tier observables.  Single field for now;
 * structured as an interface so future ES-only metrics (e.g.,
 * supplier-availability statistics) can be added without changing
 * cost-calculator signatures.
 */
interface ESCostObservables {
    /** Count of shipments dispatched from the ES (post-warmup). */
    val totalShipmentCount: Double
}
