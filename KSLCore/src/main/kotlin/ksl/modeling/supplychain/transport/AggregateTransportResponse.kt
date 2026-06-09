package ksl.modeling.supplychain.transport

import ksl.modeling.supplychain.*

import ksl.modeling.variable.AggregateCounter
import ksl.modeling.variable.AggregateTWResponse
import ksl.modeling.variable.Counter
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.simulation.ModelElement

/**
 * Aggregate transport statistics across multiple [TransportDelay]
 * instances: amount-in-transit (time-weighted), transit time
 * (per-shipment average), and number of shipments.
 *
 * The transit-time aggregate is a plain [Response] without an
 * observer chain (KSL has no chaining-aggregator for non-time-weighted
 * responses; same Session-8 deviation as in
 * [AggregateInventoryResponse]). Callers that want it filled must push
 * values directly.
 *
 * @param parent parent model element under which the aggregate
 *        responses live
 * @param baseName name prefix for the aggregate responses
 *
 * @see sc.transportlayer.AggregateTransportResponse
 */
class AggregateTransportResponse @JvmOverloads constructor(
    parent: ModelElement,
    baseName: String = parent.name,
) {
    val numInTransit: AggregateTWResponse =
        AggregateTWResponse(parent, "$baseName : #In Transit")

    /**
     * Transit-time aggregate. Plain [Response] — KSL has no chaining
     * aggregator for non-time-weighted responses. Callers update
     * directly.
     */
    val transitTime: Response =
        Response(parent, name = "$baseName : Transit Time")

    val numShipments: AggregateCounter =
        AggregateCounter(parent, "$baseName : #Shipments")

    /**
     * Wire [transportDelay]'s responses into the aggregates. The
     * time-weighted `numInTransit` and the counter chain via
     * `observe()`. The transit-time aggregate is NOT chained.
     *
     * Runtime `is` narrowing is required because [TransportDelay]
     * exposes responses via read-only `*CIfc` interfaces while
     * `observe()` requires the concrete types — safe because
     * [TransportDelay] always constructs the concrete types.
     */
    fun subscribeTo(transportDelay: TransportDelay) {
        val numInTransitRaw = transportDelay.numInTransitResponse
        if (numInTransitRaw is TWResponse) numInTransit.observe(numInTransitRaw)
        val numShipmentsRaw = transportDelay.numShipmentsCounter
        if (numShipmentsRaw is Counter) numShipments.observe(numShipmentsRaw)
    }

    fun unsubscribeFrom(transportDelay: TransportDelay) {
        val numInTransitRaw = transportDelay.numInTransitResponse
        if (numInTransitRaw is TWResponse) numInTransit.remove(numInTransitRaw)
        val numShipmentsRaw = transportDelay.numShipmentsCounter
        if (numShipmentsRaw is Counter) numShipments.remove(numShipmentsRaw)
    }
}
