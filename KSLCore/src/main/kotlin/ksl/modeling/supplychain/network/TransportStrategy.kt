package ksl.modeling.supplychain.network

import ksl.modeling.supplychain.DemandCarrierIfc
import ksl.modeling.supplychain.transport.NoDelayDemandCarrier
import ksl.modeling.supplychain.transport.TimeBasedDemandCarrier
import ksl.modeling.supplychain.transport.TimeBasedNetworkDemandCarrier

/**
 * How a [MultiEchelonNetwork] wires transport between its
 * external supplier, IHPs, and demand generators. A network picks
 * exactly one variant at construction time; the variant determines
 * how `attachIHPToExternalSupplier`, `attachIHPToSupplier`, and
 * `attachDemandGeneratorToIHP` translate their `transportTime`
 * parameter into carrier configuration.
 *
 * @see MultiEchelonNetwork
 */
sealed class TransportStrategy {

    /**
     * One [DemandCarrierIfc] shared by the external supplier and
     * every IHP. The carrier is held inside [MultiEchelonNetwork]
     * via a forwarding adapter so callers can hot-swap it mid-build.
     *
     * `transportTime` is **not** honoured under this strategy —
     * passing a non-null `transportTime` to any `attachX` method
     * throws, matching the original `MultiEchelonNetwork`'s
     * behaviour.
     *
     * @param carrier the initial carrier; defaults to
     *        [NoDelayDemandCarrier]
     */
    data class SharedCarrier(
        val carrier: DemandCarrierIfc = NoDelayDemandCarrier,
    ) : TransportStrategy()

    /**
     * Each IHP (and the external supplier) owns its own
     * [TimeBasedDemandCarrier]. `transportTime` arguments are
     * registered on the supplier's carrier keyed by the customer.
     * Passing null switches the carrier into
     * [TimeBasedDemandCarrier.immediateTransportFlag] mode.
     *
     * Replaces the dedicated
     * `TimeBasedShippingMultiEchelonNetwork` class.
     */
    object PerIHPTimeBased : TransportStrategy()

    /**
     * A single caller-supplied [TimeBasedNetworkDemandCarrier] is
     * shared by every supplier-customer edge. Transport times are
     * keyed by the (filler, sender) pair on the carrier.
     *
     * Replaces the dedicated
     * `TimeBasedNetworkShippingMultiEchelonNetwork` class.
     *
     * @param carrier the shared network carrier; must already live
     *        under the same [ksl.modeling.supplychain.SupplyChainModel]
     *        as the network
     */
    data class NetworkTimeBased(
        val carrier: TimeBasedNetworkDemandCarrier,
    ) : TransportStrategy()
}
