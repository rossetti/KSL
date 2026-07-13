package ksl.examples.general.models.inventory

/**
 * Functional interface for a component that accepts an incoming [DemandCreator.Demand] to process —
 * the "demand arrives here" seam. Contrast [InventoryReceiverIfc] (which receives *filled*
 * replenishment back into stock) and [DemandSenderIfc] (the sending side).
 */
fun interface DemandReceiverIfc {

    /**
     * Represents an arrival of demand to be processed by the receiver
     *
     * @param demand
     */
    fun receive(demand: DemandCreator.Demand)

}