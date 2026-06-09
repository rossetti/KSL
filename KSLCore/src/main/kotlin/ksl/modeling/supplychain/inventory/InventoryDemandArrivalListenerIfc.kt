package ksl.modeling.supplychain.inventory

import ksl.modeling.supplychain.*

/**
 * Notified after [Inventory] processes a demand arrival — either an
 * incoming customer demand (via [Inventory.demandArrivalListener]) or
 * a replenishment demand arriving from a supplier (via
 * [Inventory.replenishmentArrivalListener]).
 *
 * @see sc.inventorylayer.InventoryDemandArrivalListenerIfc
 */
fun interface InventoryDemandArrivalListenerIfc {
    fun demandArrived(inventory: Inventory, demand: SupplyChainModel.Demand)
}
