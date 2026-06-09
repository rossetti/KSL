package ksl.modeling.supplychain.cost

import ksl.modeling.supplychain.DemandSenderIfc
import ksl.modeling.supplychain.transport.TimeBasedDemandCarrier
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.observers.ModelElementObserver
import ksl.simulation.ModelElement

/**
 * Per-edge inbound cost calculator: observes the **supplier's**
 * outbound carrier and produces the [CostLine.Unloading] line item
 * for one specific [destination] edge.
 *
 * Attribution: the [tier] is the **destination-tier** — Unloading
 * cost is paid by the node that receives the shipment.  This is
 * the same set of shipments observed by
 * [EdgeOutboundCostCalculator] on the same edge, but priced at
 * `unloadingCost` instead of `loadingCost`/`shippingCost` and
 * attributed to the destination rather than the supplier.
 *
 * Formula: `shipmentCount × unloadingCost`, where `shipmentCount`
 * is `carrier.getNumberOfDemandShipments(destination)`.
 *
 * For ES → IHP edges the carrier is the ES carrier and the
 * destination is the IHP; this calculator's tier is the IHP's tier.
 * The ES tier's outbound cost is accounted for separately by
 * [ESCostCalculator] (via [CostLine.ESLoading]) — there is no
 * double-counting because Loading/Shipping (paid by sender) and
 * Unloading (paid by receiver) are conceptually distinct line items.
 *
 * @param parent the [ModelElement] parent
 * @param carrier the supplier's outbound carrier this calculator
 *        observes
 * @param destination the destination [DemandSenderIfc] this
 *        calculator focuses on
 * @param destinationTier tier of the destination — used as the
 *        [CostCalculator.tier]
 * @param params cost-rate parameters
 */
class EdgeInboundCostCalculator @JvmOverloads constructor(
    parent: ModelElement,
    private val carrier: TimeBasedDemandCarrier,
    private val destination: DemandSenderIfc,
    private val destinationTier: NodeTier,
    private val params: CostParams,
    name: String? = null,
) : ModelElement(parent, name ?: "${carrier.name}->${destination.name}:In"),
    CostCalculator {

    override val source: ModelElement get() = carrier
    override val tier: NodeTier get() = destinationTier

    private val myUnloading = Response(this, "${this.name}:Unloading")

    override val lineResponses: Map<CostLine, ResponseCIfc> = mapOf(
        CostLine.Unloading to myUnloading,
    )

    private inner class SourceObserver : ModelElementObserver() {
        override fun replicationEnded(modelElement: ModelElement) {
            val n = carrier.getNumberOfDemandShipments(destination)
            myUnloading.value = n * params.unloadingCost
        }
    }

    init { carrier.attachModelElementObserver(SourceObserver()) }
}
