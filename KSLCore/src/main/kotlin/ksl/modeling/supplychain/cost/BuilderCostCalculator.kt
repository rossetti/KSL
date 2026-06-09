package ksl.modeling.supplychain.cost

import ksl.modeling.supplychain.transport.DemandLoadBuilder
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.observers.ModelElementObserver
import ksl.simulation.ModelElement

/**
 * Per-builder cost calculator: observes one [DemandLoadBuilder]
 * and produces the [CostLine.ShipmentBuilderHolding] line item,
 * summing per-(builder, item) on-hand carrying cost.
 *
 * Formula:
 * ```
 * Σ_(items the builder is tracking) avgUnitsOnHand(item) × item.unitCost × carryingRate
 * ```
 *
 * `avgUnitsOnHand(item)` is sourced from the builder's per-item
 * on-hand `TWResponse` (created at builder construction when
 * `itemTypes` is non-empty — Phase-3 follow-up #3 work).  Builders
 * constructed without `itemTypes` (the default) have an empty
 * `trackedItemTypes` set and emit 0.0.
 *
 * Tier attribution is supplied at construction time because a
 * builder lives on a carrier, which lives on a network node, which
 * could be an IHP or a CD.  The formulation's `buildCalculators`
 * walk knows the owning node and passes the matching tier.
 *
 * @param parent the [ModelElement] parent
 * @param builder the load builder this calculator observes
 * @param ownerTier tier of the network node that owns the
 *        builder's carrier
 * @param params cost-rate parameters
 */
class BuilderCostCalculator @JvmOverloads constructor(
    parent: ModelElement,
    private val builder: DemandLoadBuilder,
    private val ownerTier: NodeTier,
    private val params: CostParams,
    name: String? = null,
) : ModelElement(parent, name ?: "${builder.name}:CostCalc"),
    CostCalculator {

    override val source: ModelElement get() = builder
    override val tier: NodeTier get() = ownerTier

    private val myBuilderHolding = Response(this, "${this.name}:BuilderHolding")

    override val lineResponses: Map<CostLine, ResponseCIfc> = mapOf(
        CostLine.ShipmentBuilderHolding to myBuilderHolding,
    )

    private inner class SourceObserver : ModelElementObserver() {
        override fun replicationEnded(modelElement: ModelElement) {
            var total = 0.0
            for (item in builder.trackedItemTypes) {
                val twr = builder.unitsOnHandResponse(item) ?: continue
                val avg = twr.withinReplicationStatistic.weightedAverage
                total += avg * item.unitCost * params.carryingRate
            }
            myBuilderHolding.value = total
        }
    }

    init { builder.attachModelElementObserver(SourceObserver()) }
}
