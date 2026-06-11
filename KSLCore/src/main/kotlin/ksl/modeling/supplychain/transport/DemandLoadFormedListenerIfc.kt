package ksl.modeling.supplychain.transport

import ksl.modeling.supplychain.*

/**
 * Notification hook fired by [DemandLoadBuilder] each time a new
 * [SupplyChainModel.DemandLoad] is placed on its outgoing load queue.
 *
 * See `sc.transportlayer.DemandLoadFormedListenerIfc`
 */
fun interface DemandLoadFormedListenerIfc {
    /** Called after [builder] has formed a load. */
    fun loadFormed(builder: DemandLoadBuilder)
}
