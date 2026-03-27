package ksl.examples.general.models.inventory

import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.RVariableIfc


fun main() {
   val m = BuildTwoEchelonModel.build(
       null,
       null,
       defaultKSLDatabaseObserverOption = false)
    printControlsAndResponses(m)
//    m.simulate()
//    m.print()

}

fun printControlsAndResponses(model : Model){
    val controls = model.controls()
    for(c in controls.controlKeys()) {
        println(c)
    }
    for( r in model.responses){
        println(r.name)
    }
}

fun test1() {
    val m = Model("Two Echelon RQ Inventory Model")
    val itemType = ItemType("Test Item")
    val supplierLeadTimeToDC: RVariableIfc = ConstantRV(5.0)
    val timeBtwDemandDC: RVariableIfc = ExponentialRV(1.0 / 3.6, streamNum = 1)
    val demandAmountDC: RVariableIfc = ConstantRV(1.0)
    val reorderPointDC: Int = 10
    val reorderQtyDC: Int = 1
    val initialOnHandDC: Int = reorderPointDC + reorderQtyDC
    val shippingTimeDCToBase: RVariableIfc = ExponentialRV(3.5, streamNum = 3)
    val timeBtwDemandBase: RVariableIfc = ExponentialRV(1.0 / 3.6, streamNum = 2)
    val demandAmountBase: RVariableIfc = ConstantRV(1.0)
    val reorderPointBase: Int = 1
    val reorderQtyBase: Int = 1
    val initialOnHandBase: Int = reorderPointBase + reorderQtyBase
    val twoEchelonModel = TwoEchelonModel(
        m, itemType, supplierLeadTimeToDC,
        timeBtwDemandDC, demandAmountDC, reorderPointDC, reorderQtyDC, initialOnHandDC,
        shippingTimeDCToBase, timeBtwDemandBase, demandAmountBase, reorderPointBase, reorderQtyBase, initialOnHandBase,
        "TwoEchelon"
    )
    //TODO need to configure cost parameters too!
    m.lengthOfReplication = 110000.0
    m.lengthOfReplicationWarmUp = 10000.0
    m.numberOfReplications = 40
    m.simulate()
    m.print()
}

