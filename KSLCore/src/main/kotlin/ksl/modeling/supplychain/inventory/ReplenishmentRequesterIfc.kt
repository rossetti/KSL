package ksl.modeling.supplychain.inventory

import ksl.modeling.supplychain.*

/**
 * Strategy hook for routing an [Inventory]'s replenishment demand. The
 * implementer is responsible for ensuring that the replenishment demand
 * is eventually filled by a [DemandFillerIfc].
 *
 * @see sc.inventorylayer.ReplenishmentRequesterIfc
 */
fun interface ReplenishmentRequesterIfc {
    fun requestReplenishment(inventory: Inventory, demand: SupplyChainModel.Demand)
}
