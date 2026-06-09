package ksl.modeling.supplychain.cost

import ksl.modeling.supplychain.DemandFillerIfc
import ksl.modeling.supplychain.DemandSenderIfc
import ksl.modeling.supplychain.transport.TimeBasedNetworkDemandCarrier
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.observers.ModelElementObserver
import ksl.simulation.ModelElement

/**
 * NetworkTimeBased-strategy variant of [EdgeOutboundCostCalculator]:
 * observes a shared [TimeBasedNetworkDemandCarrier] and produces
 * [CostLine.Loading] + [CostLine.Shipping] for one specific
 * `(filler, destination)` edge.
 *
 * Tier attribution: the **supplier-tier** (carrier-owner-tier), same
 * convention as the PerIHPTimeBased variant.  See
 * [EdgeOutboundCostCalculator] for the full semantic specification.
 *
 * Read source: `networkCarrier.getNumberOfDemandShipments(filler, destination)`.
 *
 * @param parent the [ModelElement] parent
 * @param networkCarrier the shared network carrier this calculator
 *        observes
 * @param filler the upstream supplier on this edge (the "filler" key
 *        in the carrier's per-edge counter map)
 * @param destination the downstream destination on this edge
 * @param supplierTier tier of the supplier — used as
 *        [CostCalculator.tier]
 * @param params cost-rate parameters
 */
class NetworkEdgeOutboundCostCalculator @JvmOverloads constructor(
    parent: ModelElement,
    private val networkCarrier: TimeBasedNetworkDemandCarrier,
    private val filler: DemandFillerIfc,
    private val destination: DemandSenderIfc,
    private val supplierTier: NodeTier,
    private val params: CostParams,
    name: String? = null,
) : ModelElement(parent,
    name ?: "${networkCarrier.name}:${filler.name}->${destination.name}:Out"),
    CostCalculator {

    override val source: ModelElement get() = networkCarrier
    override val tier: NodeTier get() = supplierTier

    private val myLoading = Response(this, "${this.name}:Loading")
    private val myShipping = Response(this, "${this.name}:Shipping")

    override val lineResponses: Map<CostLine, ResponseCIfc> = mapOf(
        CostLine.Loading to myLoading,
        CostLine.Shipping to myShipping,
    )

    private inner class SourceObserver : ModelElementObserver() {
        override fun replicationEnded(modelElement: ModelElement) {
            val n = networkCarrier.getNumberOfDemandShipments(filler, destination)
            myLoading.value = n * params.loadingCost
            myShipping.value = n * params.shippingCost
        }
    }

    init { networkCarrier.attachModelElementObserver(SourceObserver()) }
}
