package ksl.modeling.supplychain.cost

import ksl.modeling.supplychain.inventory.BackLogPolicyAbstract
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.observers.ModelElementObserver
import ksl.simulation.ModelElement

/**
 * Per-IHP-backlog cost calculator: observes one
 * [BackLogPolicyAbstract] and populates the continuous-rate
 * [CostLine.Backorder] line.
 *
 * Formula: `avgBacklogInQ × backorderRate`.  Both factors are pure
 * numerics; the framework performs no time-unit conversion (see
 * `docs/supply-chain-cost-redesign.md` §2).  The emitted Response
 * is a rate in the time unit of the modeler's `backorderRate`.
 *
 * Tier attribution: [NodeTier.IHP].  Backlogs live on IHPs in v1
 * (cross-docks hold no inventory and therefore no backlog).
 *
 * @param parent the [ModelElement] parent
 * @param backlog the policy this calculator observes
 * @param params cost-rate parameters
 */
class BackorderCostCalculator @JvmOverloads constructor(
    parent: ModelElement,
    private val backlog: BackLogPolicyAbstract,
    private val params: CostParams,
    name: String? = null,
) : ModelElement(parent, name ?: "${backlog.name}:CostCalc"),
    CostCalculator {

    override val source: ModelElement get() = backlog
    override val tier: NodeTier get() = NodeTier.IHP

    private val myBackorder = Response(this, "${this.name}:Backorder")

    override val lineResponses: Map<CostLine, ResponseCIfc> = mapOf(
        CostLine.Backorder to myBackorder,
    )

    private inner class SourceObserver : ModelElementObserver() {
        override fun replicationEnded(modelElement: ModelElement) {
            myBackorder.value =
                backlog.avgBacklogInQ * params.backorderRate
        }
    }

    init { backlog.attachModelElementObserver(SourceObserver()) }
}
