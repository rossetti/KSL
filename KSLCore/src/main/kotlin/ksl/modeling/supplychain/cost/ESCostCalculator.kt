package ksl.modeling.supplychain.cost

import ksl.modeling.supplychain.DemandSenderIfc
import ksl.modeling.supplychain.transport.TimeBasedDemandCarrier
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.observers.ModelElementObserver
import ksl.simulation.ModelElement

/**
 * External-supplier loading-cost calculator: observes the network's
 * ES outbound carrier and produces the [CostLine.ESLoading] line
 * item, priced at [CostParams.esLoadingCost] per shipment.
 *
 * Formula: `Σ_(destinations) shipmentCount(destination) × esLoadingCost`.
 *
 * This calculator covers the supplier-side cost of ES → IHP edges.
 * The destination-side Unloading cost on the same edges is
 * accounted for separately by an [EdgeInboundCostCalculator] (one
 * per ES → destination edge), so the same shipment contributes to
 * two distinct line items at different tiers — ESLoading at the ES
 * tier and Unloading at the destination's tier — exactly as the
 * legacy framework distinguished them.
 *
 * Tier: [NodeTier.ES].
 *
 * @param parent the [ModelElement] parent
 * @param esCarrier the ES outbound carrier this calculator observes
 * @param destinations every node currently attached to the ES;
 *        the calculator sums per-destination shipment counts
 * @param params cost-rate parameters
 */
class ESCostCalculator @JvmOverloads constructor(
    parent: ModelElement,
    private val esCarrier: TimeBasedDemandCarrier,
    private val destinations: List<DemandSenderIfc>,
    private val params: CostParams,
    name: String? = null,
) : ModelElement(parent, name ?: "${esCarrier.name}:ESCostCalc"),
    CostCalculator {

    override val source: ModelElement get() = esCarrier
    override val tier: NodeTier get() = NodeTier.ES

    private val myESLoading = Response(this, "${this.name}:ESLoading")

    override val lineResponses: Map<CostLine, ResponseCIfc> = mapOf(
        CostLine.ESLoading to myESLoading,
    )

    private inner class SourceObserver : ModelElementObserver() {
        override fun replicationEnded(modelElement: ModelElement) {
            var total = 0.0
            for (dest in destinations) {
                total += esCarrier.getNumberOfDemandShipments(dest)
            }
            myESLoading.value = total * params.esLoadingCost
        }
    }

    init { esCarrier.attachModelElementObserver(SourceObserver()) }
}
