package ksl.modeling.supplychain.transport

import ksl.modeling.supplychain.*

/**
 * Stateless [OrderCarrierIfc] that immediately ships and delivers
 * the order — no simulated time passes.
 *
 * See `sc.transportlayer.NoDelayOrderCarrier`
 */
object NoDelayOrderCarrier : OrderCarrierIfc {
    override fun transportOrder(order: SupplyChainModel.Order) {
        order.ship()
        order.deliver()
    }

    override fun canShip(order: SupplyChainModel.Order): Boolean = true
}
