package ksl.modeling.supplychain.inventory

import ksl.modeling.supplychain.*

/**
 * Replenishment-side rejection listener that retains a reference to the
 * [Inventory] whose replenishment was rejected. Default dispatch behavior
 * (inherited from [DemandRejectionListener]) throws; subclasses override
 * to react.
 *
 * @see sc.inventorylayer.InventoryReplenishmentRejectionListener
 */
open class InventoryReplenishmentRejectionListener(
    val inventory: Inventory,
) : DemandRejectionListener()
