package ksl.modeling.supplychain.transport

import ksl.modeling.supplychain.*

/**
 * Stateless [DemandCarrierIfc] that immediately ships and delivers
 * the demand — no simulated time passes. Also implements
 * [DemandStateChangeListener] so it can be attached to a demand as a
 * post-fill auto-shipping hook (matches Java's `DemandListenerFilledIfc`
 * role on the original class).
 *
 * Singleton — replaces Java's static
 * `NoDelayDemandCarrier.DefaultNoDelayDemandCarrier` per porting plan
 * §4.2.
 *
 * See `sc.transportlayer.NoDelayDemandCarrier`
 */
object NoDelayDemandCarrier : DemandCarrierIfc, DemandStateChangeListener {

    override fun transportDemand(demand: SupplyChainModel.Demand) {
        demand.ship()
        demand.deliver()
    }

    override fun canShip(demand: SupplyChainModel.Demand): Boolean = true

    /** When attached to a demand, fires [transportDemand] on FILLED. */
    override fun onDemandStateChange(
        demand: SupplyChainModel.Demand,
        from: SupplyChainModel.DemandState?,
        to: SupplyChainModel.DemandState,
    ) {
        if (to.stateId === DemandStateId.Filled) transportDemand(demand)
    }
}
