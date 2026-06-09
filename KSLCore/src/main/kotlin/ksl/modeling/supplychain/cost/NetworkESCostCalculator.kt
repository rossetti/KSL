package ksl.modeling.supplychain.cost

import ksl.modeling.supplychain.DemandFillerIfc
import ksl.modeling.supplychain.DemandSenderIfc
import ksl.modeling.supplychain.transport.TimeBasedNetworkDemandCarrier
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.observers.ModelElementObserver
import ksl.simulation.ModelElement

/**
 * NetworkTimeBased-strategy variant of [ESCostCalculator]: observes
 * a shared [TimeBasedNetworkDemandCarrier] and produces
 * [CostLine.ESLoading], summing shipments across every ES → node
 * edge.
 *
 * The shared network carrier is one ModelElement covering every
 * edge in the network; the calculator reads
 * `getNumberOfDemandShipments(esFiller, destination)` for each ES
 * destination and prices the sum at `esLoadingCost`.
 *
 * Tier: [NodeTier.ES].
 *
 * @param parent the [ModelElement] parent
 * @param networkCarrier the shared network carrier (the ES routes
 *        its outbound through this carrier under NetworkTimeBased)
 * @param esFiller the ES itself — used as the "filler" key in the
 *        carrier's per-edge counter map
 * @param destinations every node currently attached to the ES
 * @param params cost-rate parameters
 */
class NetworkESCostCalculator @JvmOverloads constructor(
    parent: ModelElement,
    private val networkCarrier: TimeBasedNetworkDemandCarrier,
    private val esFiller: DemandFillerIfc,
    private val destinations: List<DemandSenderIfc>,
    private val params: CostParams,
    name: String? = null,
) : ModelElement(parent, name ?: "${networkCarrier.name}:ESCostCalc"),
    CostCalculator {

    override val source: ModelElement get() = networkCarrier
    override val tier: NodeTier get() = NodeTier.ES

    private val myESLoading = Response(this, "${this.name}:ESLoading")

    override val lineResponses: Map<CostLine, ResponseCIfc> = mapOf(
        CostLine.ESLoading to myESLoading,
    )

    private inner class SourceObserver : ModelElementObserver() {
        override fun replicationEnded(modelElement: ModelElement) {
            var total = 0.0
            for (dest in destinations) {
                total += networkCarrier.getNumberOfDemandShipments(esFiller, dest)
            }
            myESLoading.value = total * params.esLoadingCost
        }
    }

    init { networkCarrier.attachModelElementObserver(SourceObserver()) }
}
