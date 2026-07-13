package ksl.examples.general.models.inventory

/**
 * Transports demand that an inventory has filled onward to its end customer (the demand's
 * filled-demand receiver), optionally after a shipping delay. This is the "how does filled stock
 * physically get to the customer" seam of the inventory models: an [Inventory] hands every filled
 * [DemandCreator.Demand] to its `demandCarrier`. Implementations range from the zero-delay
 * `Inventory.ImmediateDeliveryCarrier` to the scheduled [TimeBasedDemandCarrier].
 */
fun interface DemandCarrierIfc {
    fun transport(demand: DemandCreator.Demand)
}