package ksl.modeling.supplychain.cost

import ksl.modeling.supplychain.DemandSenderIfc
import ksl.modeling.supplychain.transport.TimeBasedDemandCarrier
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.observers.ModelElementObserver
import ksl.simulation.ModelElement

/**
 * Per-edge outbound cost calculator: observes one [carrier] (the
 * outbound carrier of an IHP or CD) and produces the
 * [CostLine.Loading] and [CostLine.Shipping] line items for one
 * specific [destination] edge.
 *
 * Attribution: the [tier] is the **supplier-tier** — Loading and
 * Shipping are paid by the node that owns the outbound carrier
 * (the supplier), not by the destination.  See
 * [EdgeInboundCostCalculator] for the Unloading line, which
 * attributes to the destination-tier instead.
 *
 * Formulas:
 *
 * | [CostLine] | Formula |
 * |---|---|
 * | [CostLine.Loading] | `shipmentCount × loadingCost` |
 * | [CostLine.Shipping] | `shipmentCount × shippingCost` |
 *
 * `shipmentCount` is `carrier.getNumberOfDemandShipments(destination)`
 * which reflects the post-warmup count (KSL's Counter resets at
 * warmup automatically).
 *
 * Under `TransportStrategy.SharedCarrier` the carrier is not a
 * [TimeBasedDemandCarrier] (it's a `NoDelayDemandCarrier` or
 * similar), so this calculator should not be constructed.  The
 * formulation's `buildCalculators` walk skips edges whose carrier
 * is not a time-based one.
 *
 * @param parent the [ModelElement] parent
 * @param carrier the outbound carrier this calculator observes;
 *        must be the carrier of the supplier on this edge
 * @param destination the destination [DemandSenderIfc] this
 *        calculator focuses on
 * @param supplierTier tier of the supplier (carrier owner) — used
 *        as the [CostCalculator.tier]
 * @param params cost-rate parameters
 */
class EdgeOutboundCostCalculator @JvmOverloads constructor(
    parent: ModelElement,
    private val carrier: TimeBasedDemandCarrier,
    private val destination: DemandSenderIfc,
    private val supplierTier: NodeTier,
    private val params: CostParams,
    name: String? = null,
) : ModelElement(parent, name ?: "${carrier.name}->${destination.name}:Out"),
    CostCalculator {

    override val source: ModelElement get() = carrier
    override val tier: NodeTier get() = supplierTier

    private val myLoading = Response(this, "${this.name}:Loading")
    private val myShipping = Response(this, "${this.name}:Shipping")

    override val lineResponses: Map<CostLine, ResponseCIfc> = mapOf(
        CostLine.Loading to myLoading,
        CostLine.Shipping to myShipping,
    )

    private inner class SourceObserver : ModelElementObserver() {
        override fun replicationEnded(modelElement: ModelElement) {
            val n = carrier.getNumberOfDemandShipments(destination)
            myLoading.value = n * params.loadingCost
            myShipping.value = n * params.shippingCost
        }
    }

    init { carrier.attachModelElementObserver(SourceObserver()) }
}
