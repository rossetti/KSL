package ksl.modeling.supplychain.cost

import ksl.modeling.supplychain.DemandFillerIfc
import ksl.modeling.supplychain.DemandSenderIfc
import ksl.modeling.supplychain.transport.TimeBasedNetworkDemandCarrier
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.observers.ModelElementObserver
import ksl.simulation.ModelElement

/**
 * NetworkTimeBased-strategy variant of [EdgeInboundCostCalculator]:
 * observes a shared [TimeBasedNetworkDemandCarrier] and produces
 * [CostLine.Unloading] for one specific `(filler, destination)`
 * edge.
 *
 * Tier attribution: the **destination-tier**, same convention as
 * the PerIHPTimeBased variant.  See [EdgeInboundCostCalculator] for
 * the full semantic specification.
 *
 * Read source: `networkCarrier.getNumberOfDemandShipments(filler, destination)`.
 *
 * @param parent the [ModelElement] parent
 * @param networkCarrier the shared network carrier this calculator
 *        observes
 * @param filler the upstream supplier on this edge
 * @param destination the downstream destination on this edge
 * @param destinationTier tier of the destination — used as
 *        [CostCalculator.tier]
 * @param params cost-rate parameters
 */
class NetworkEdgeInboundCostCalculator @JvmOverloads constructor(
    parent: ModelElement,
    private val networkCarrier: TimeBasedNetworkDemandCarrier,
    private val filler: DemandFillerIfc,
    private val destination: DemandSenderIfc,
    private val destinationTier: NodeTier,
    private val params: CostParams,
    name: String? = null,
) : ModelElement(parent,
    name ?: "${networkCarrier.name}:${filler.name}->${destination.name}:In"),
    CostCalculator {

    override val source: ModelElement get() = networkCarrier
    override val tier: NodeTier get() = destinationTier

    private val myUnloading = Response(this, "${this.name}:Unloading")

    override val lineResponses: Map<CostLine, ResponseCIfc> = mapOf(
        CostLine.Unloading to myUnloading,
    )

    private inner class SourceObserver : ModelElementObserver() {
        override fun replicationEnded(modelElement: ModelElement) {
            val n = networkCarrier.getNumberOfDemandShipments(filler, destination)
            myUnloading.value = n * params.unloadingCost
        }
    }

    init { networkCarrier.attachModelElementObserver(SourceObserver()) }
}
