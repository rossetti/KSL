package ksl.modeling.supplychain

/**
 * Strategy hook for any pre-shipment preparation a [DemandFillerIfc]
 * needs to perform on a demand before handing it to a carrier.
 *
 * See `sc.inventorylayer.DemandPreparerIfc`
 */
fun interface DemandPreparerIfc {
    fun prepareDemand(demand: SupplyChainModel.Demand)
}
