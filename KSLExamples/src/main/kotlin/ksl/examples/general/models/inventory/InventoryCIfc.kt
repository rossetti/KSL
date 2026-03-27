package ksl.examples.general.models.inventory

import ksl.modeling.queue.QueueCIfc
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TWResponseCIfc

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