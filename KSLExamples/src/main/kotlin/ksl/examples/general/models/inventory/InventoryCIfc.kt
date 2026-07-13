package ksl.examples.general.models.inventory

import ksl.modeling.queue.QueueCIfc
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TWResponseCIfc

/**
 * Read-only (controlled) view of an [Inventory]: its item type, current on-hand / on-order /
 * amount-backordered levels and inventory position, the backorder queue, and the time-weighted
 * responses and counters the inventory collects (on-hand, ready rate, ordering frequency, fill rate,
 * number of replenishment orders). Exposed via the CIfc pattern so callers can observe inventory
 * state without mutating what only the [Inventory] element should control.
 */
interface InventoryCIfc {
    val itemType: ItemType

    val costPerUnit: Double

    val onHand: Int

    val onHandResponse: TWResponseCIfc

    val probOfStockOnHandResponse: TWResponseCIfc

    var initialOnHand: Int

    val amountBackOrdered: Int

    val amountBackOrderedResponse: TWResponseCIfc

    val onOrder: Int

    val numReplenishmentOrders: CounterCIfc

    val orderingFrequency: ResponseCIfc

    val onOrderedResponse: TWResponseCIfc

    val inventoryPosition: Int

    val backOrderQ: QueueCIfc<DemandCreator.Demand>

    val fillRate: ResponseCIfc
}