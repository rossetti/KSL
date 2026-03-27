package ksl.examples.general.models.inventory

/**
 *  An interface to promise the ability to receive filled demand
 *  and store it as replenished inventory. The implementor should
 *  use the supplied demand to replenish stored inventory by updating
 *  the on-hand inventory and processing any backlogged requests for units
 *  of inventory.
 */
fun interface InventoryReceiverIfc {

    fun receiveInventory(demand: DemandCreator.Demand)
}