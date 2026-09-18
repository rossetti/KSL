package ksl.modeling.supplychain.cost

/**
 * Identifier for a single line item produced by a [CostFormulation].
 * Used as a key in [CostCalculator.lineResponses] and as the index
 * into [CostFormulation.byLineResponse] for per-line rollup queries.
 *
 * The complete list of v1 line items is exposed via [all] so
 * formulations can iterate over every line when building rollup
 * aggregates.
 *
 * Every line declares its [CostBasis].  The constructor requires it, so a
 * line cannot be added without saying whether it is a rate or a
 * per-replication total — which is what keeps a rollup from silently
 * summing the two.  See [CostBasis] for why the distinction matters.  v2 line items (introduced by Phase 5 — backorder,
 * stockout, lost-sale, unit-shortage) are included here from the
 * start so the formulation infrastructure does not need to grow as
 * those calculators land.
 */
sealed class CostLine(val displayName: String, val basis: CostBasis) {
    /** On-hand inventory holding cost (per (IHP, item)). */
    object Holding : CostLine("HOLDING", CostBasis.RatePerTime)

    /** Goods-in-transit holding cost (per (IHP, item)). */
    object InTransit : CostLine("IN_TRANSIT", CostBasis.RatePerTime)

    /** Replenishment ordering cost (per (IHP, item)). */
    object Ordering : CostLine("ORDERING", CostBasis.PerReplicationTotal)

    /** Continuous-rate backorder cost (per IHP). */
    object Backorder : CostLine("BACKORDER", CostBasis.RatePerTime)

    /** Per-event stockout cost (per (IHP, item)). */
    object Stockout : CostLine("STOCKOUT", CostBasis.PerReplicationTotal)

    /** Per-event lost-sale cost (per (IHP, item)). */
    object LostSale : CostLine("LOST_SALE", CostBasis.PerReplicationTotal)

    /** Per-unit-short shortage cost (per (IHP, item)). */
    object UnitShortage : CostLine("UNIT_SHORTAGE", CostBasis.PerReplicationTotal)

    /** Per-edge inbound shipment unloading cost (per IHP / CD). */
    object Unloading : CostLine("UNLOADING", CostBasis.PerReplicationTotal)

    /** Per-edge outbound shipment loading cost. */
    object Loading : CostLine("LOADING", CostBasis.PerReplicationTotal)

    /** Per-edge outbound shipment transport cost. */
    object Shipping : CostLine("SHIPPING", CostBasis.PerReplicationTotal)

    /**
     * Shipment-builder on-hand holding cost (per (IHP / CD, builder,
     * item) when shipment formation is enabled).
     */
    object ShipmentBuilderHolding : CostLine("SHIPMENT_BUILDER_HOLDING", CostBasis.RatePerTime)

    /** ES-tier outbound loading cost. */
    object ESLoading : CostLine("ES_LOADING", CostBasis.PerReplicationTotal)

    override fun toString(): String = displayName

    companion object {
        /**
         * Every line item the framework defines, in iteration order.
         *
         * `by lazy` (not an eager initializer) on purpose: the list
         * references the nested `object`s above, and an eager companion
         * initializer is built during [CostLine]'s class init — which is
         * triggered the first time *any* nested object is touched, at
         * which point that object's `INSTANCE` is still null and would be
         * captured as a `null` entry.  Deferring construction until the
         * first read of [all] sidesteps that sealed-class
         * initialization-order trap.
         */
        val all: List<CostLine> by lazy {
            listOf(
                Holding, InTransit, Ordering, Backorder, Stockout,
                LostSale, UnitShortage, Unloading, Loading, Shipping,
                ShipmentBuilderHolding, ESLoading,
            )
        }
    }
}
