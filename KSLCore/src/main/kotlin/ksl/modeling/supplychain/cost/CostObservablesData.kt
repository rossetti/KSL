package ksl.modeling.supplychain.cost

import ksl.modeling.supplychain.DemandSenderIfc
import ksl.modeling.supplychain.ItemType
import ksl.modeling.supplychain.inventory.Inventory
import ksl.modeling.supplychain.inventory.NetworkNodeIfc
import ksl.modeling.supplychain.transport.DemandLoadBuilder

/**
 * Concrete snapshot of an `Inventory`'s cost observables at the
 * moment of construction.  Snapshot semantics: every field is read
 * eagerly so the object is immutable and safe to retain after the
 * replication ends.
 */
data class InventoryCostObservablesData(
    override val item: ItemType,
    override val avgOnHand: Double,
    override val avgOnOrder: Double,
    override val avgBacklog: Double,
    override val orderCount: Double,
    override val totalUnitsOrdered: Double,
    override val stockoutCount: Double,
    override val lostSaleCount: Double,
    override val totalUnitsShort: Double,
) : InventoryCostObservables

data class EdgeCostObservablesData(
    override val supplier: NetworkNodeIfc?,
    override val customer: DemandSenderIfc,
    override val shipmentCount: Double,
    override val totalLoadWeight: Double,
    override val totalLoadCube: Double,
) : EdgeCostObservables

data class ESCostObservablesData(
    override val totalShipmentCount: Double,
) : ESCostObservables

/**
 * Concrete builder-observables snapshot.  [perItemUnitsOnHand]
 * captures the per-item TW averages for every item the builder is
 * tracking; [unitsOnHandAvg] looks up by item (returning null for
 * untracked items).
 */
data class BuilderCostObservablesData(
    override val builder: DemandLoadBuilder,
    val perItemUnitsOnHand: Map<ItemType, Double>,
    override val totalLoadsShipped: Double,
) : BuilderCostObservables {
    override fun unitsOnHandAvg(item: ItemType): Double? =
        perItemUnitsOnHand[item]
}

// ----------------------------------------------------------------- factories

/**
 * Capture an [InventoryCostObservables] snapshot of this inventory's
 * current within-replication state.  Intended to be called from a
 * cost calculator's `ModelElementObserver.replicationEnded(this)`
 * callback, when KSL guarantees the within-replication statistics
 * are stable for the post-warmup window.
 */
fun Inventory.snapshotCostObservables(): InventoryCostObservables =
    InventoryCostObservablesData(
        item = itemType,
        avgOnHand = onHandResponse.withinReplicationStatistic.weightedAverage,
        avgOnOrder = onOrderResponse.withinReplicationStatistic.weightedAverage,
        avgBacklog = backLogPolicy?.avgBacklogInQ ?: 0.0,
        orderCount = orderCounterCounter.value,
        totalUnitsOrdered = totalUnitsOrdered.value,
        stockoutCount = stockoutCounter.value,
        lostSaleCount = lostSaleCounter.value,
        totalUnitsShort = totalUnitsShort.value,
    )

/**
 * Capture a [BuilderCostObservables] snapshot from this builder.
 * Loops over [DemandLoadBuilder.trackedItemTypes] and reads each
 * item's TW-average units-on-hand; returns an empty per-item map
 * when the builder was constructed without an `itemTypes` list.
 */
fun DemandLoadBuilder.snapshotCostObservables(): BuilderCostObservables {
    val perItem = LinkedHashMap<ItemType, Double>()
    for (item in trackedItemTypes) {
        val r = unitsOnHandResponse(item) ?: continue
        perItem[item] = r.withinReplicationStatistic.weightedAverage
    }
    return BuilderCostObservablesData(
        builder = this,
        perItemUnitsOnHand = perItem,
        totalLoadsShipped = loadsShippedAccumulator.value,
    )
}
