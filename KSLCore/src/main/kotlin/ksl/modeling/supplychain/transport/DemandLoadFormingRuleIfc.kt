package ksl.modeling.supplychain.transport

import ksl.modeling.supplychain.*

/**
 * Strategy hook called by [DemandLoadBuilder] when its default
 * [DemandLoadBuilder.LoadFormingOption] is `RULE`. Implementations
 * decide when and how to drain the demand queue into a
 * [SupplyChainModel.DemandLoad].
 *
 * @see sc.transportlayer.DemandLoadFormingRuleIfc
 */
fun interface DemandLoadFormingRuleIfc {
    /**
     * Inspect [builder] and form one or more loads if appropriate.
     * Return true if a load was placed in the builder's load queue.
     */
    fun formLoad(builder: DemandLoadBuilder): Boolean
}
