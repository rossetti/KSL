package ksl.modeling.supplychain.cost

/**
 * Identifier for a tier of the multi-echelon network.  Used as the
 * index into [CostFormulation.byTierResponse] for per-tier rollup
 * queries.  Each line-item Response a calculator produces is
 * attributable to exactly one tier, determined by the source kind.
 */
sealed class NodeTier(val displayName: String) {
    /** Inventory-holding-point tier. */
    object IHP : NodeTier("IHP")

    /** Cross-dock tier. */
    object CD : NodeTier("CD")

    /** External-supplier (single-node) tier. */
    object ES : NodeTier("ES")

    override fun toString(): String = displayName

    companion object {
        /**
         * Every tier the framework defines, in iteration order.
         *
         * `by lazy` for the same sealed-class initialization-order
         * reason documented on [CostLine.all]: an eager companion
         * initializer would capture a `null` if a nested object were the
         * first [NodeTier] symbol touched.
         */
        val all: List<NodeTier> by lazy { listOf(IHP, CD, ES) }
    }
}
