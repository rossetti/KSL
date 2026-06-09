package ksl.modeling.supplychain

/**
 * Strategy for locating a [DemandFillerIfc] that can fill a given demand,
 * used by [OrderFillerAbstract] when assigning fillers to the demands
 * on an incoming order.
 *
 * @see sc.inventorylayer.DemandFillerFinderIfc
 */
fun interface DemandFillerFinderIfc {
    /**
     * Returns a filler for [demand], or null if no suitable filler is
     * available. The caller will typically throw
     * [NoDemandFillerFoundException] on null.
     */
    fun findDemandFiller(demand: SupplyChainModel.Demand): DemandFillerIfc?
}
