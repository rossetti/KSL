package ksl.examples.general.models.inventory

import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV

fun main(){
    val m = Model("Single Echelon RQ Inventory Model")
    val leadTime = ConstantRV(0.5)
    val timeBtwDemand = ExponentialRV(1.0 / 3.6, streamNum = 1)
    val demandAmount = ConstantRV(1.0)
    val reorderPoint = 1
    val reorderQty = 2
    val initialOnHand = 0
    val itemType = ItemType("Test Item")
    val rqModel = SingleEchelonRQInventory(
        m, itemType, leadTime,
        timeBtwDemand, demandAmount, reorderPoint, reorderQty, initialOnHand, "SingleEchelon"
    )

    m.lengthOfReplication = 110000.0
    m.lengthOfReplicationWarmUp = 10000.0
    m.numberOfReplications = 40
    m.simulate()
    m.print()
}