package ksl.modeling.supplychain.cost

import ksl.modeling.supplychain.inventory.Inventory
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.observers.ModelElementObserver
import ksl.simulation.ModelElement

/**
 * Per-(IHP, item) cost calculator: observes one [Inventory] and
 * populates six line-item Responses at the inventory's
 * `REPLICATION_ENDED` notification.  Source-owned data drives every
 * line; the calculator performs pure multiplication on stable
 * within-replication statistics with no framework-side time-unit
 * conversion (see `docs/supply-chain-cost-redesign.md` §2 "Note on
 * warmup handling").
 *
 * Line items produced:
 *
 * | [CostLine] | Formula |
 * |---|---|
 * | [CostLine.Holding] | `avgOnHand × unitCost × carryingRate` |
 * | [CostLine.InTransit] | `avgOnOrder × unitCost × carryingRate` |
 * | [CostLine.Ordering] | `orderCount × orderingCost` |
 * | [CostLine.Stockout] | `stockoutCount × stockoutCost` |
 * | [CostLine.LostSale] | `lostSaleCount × lostSaleCost` |
 * | [CostLine.UnitShortage] | `totalUnitsShort × unitShortageCost` |
 *
 * `avgOnHand` and `avgOnOrder` are sourced from the inventory's
 * `withinReplicationStatistic.weightedAverage`; all four counter
 * fields are read directly.  KSL's
 * Counter/Response/TWResponse warmup hooks reset these accumulators
 * at the warmup event, so the values reflect the post-warmup
 * observation window automatically.
 *
 * @param parent the [ModelElement] parent (typically the
 *        owning [CostFormulation])
 * @param inv the [Inventory] this calculator observes — the
 *        calculator reads `inv.itemType` directly; no separate
 *        `item` constructor argument is needed
 * @param params cost-rate parameters
 */
class InventoryCostCalculator @JvmOverloads constructor(
    parent: ModelElement,
    private val inv: Inventory,
    private val params: CostParams,
    name: String? = null,
) : ModelElement(parent, name ?: "${inv.name}:CostCalc"),
    CostCalculator {

    override val source: ModelElement get() = inv
    override val tier: NodeTier get() = NodeTier.IHP

    private val myHolding      = Response(this, "${this.name}:Holding")
    private val myInTransit    = Response(this, "${this.name}:InTransit")
    private val myOrdering     = Response(this, "${this.name}:Ordering")
    private val myStockout     = Response(this, "${this.name}:Stockout")
    private val myLostSale     = Response(this, "${this.name}:LostSale")
    private val myUnitShortage = Response(this, "${this.name}:UnitShortage")

    override val lineResponses: Map<CostLine, ResponseCIfc> = mapOf(
        CostLine.Holding      to myHolding,
        CostLine.InTransit    to myInTransit,
        CostLine.Ordering     to myOrdering,
        CostLine.Stockout     to myStockout,
        CostLine.LostSale     to myLostSale,
        CostLine.UnitShortage to myUnitShortage,
    )

    private inner class SourceObserver : ModelElementObserver() {
        override fun replicationEnded(modelElement: ModelElement) {
            val u = inv.itemType.unitCost

            myHolding.value =
                inv.onHandResponse.withinReplicationStatistic.weightedAverage *
                u * params.carryingRate

            myInTransit.value =
                inv.onOrderResponse.withinReplicationStatistic.weightedAverage *
                u * params.carryingRate

            myOrdering.value =
                inv.orderCounterCounter.value * params.orderingCost

            myStockout.value =
                inv.stockoutCounter.value * params.stockoutCost

            myLostSale.value =
                inv.lostSaleCounter.value * params.lostSaleCost

            myUnitShortage.value =
                inv.totalUnitsShort.value * params.unitShortageCost
        }
    }

    init { inv.attachModelElementObserver(SourceObserver()) }
}
