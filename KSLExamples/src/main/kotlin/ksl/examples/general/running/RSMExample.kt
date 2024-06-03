package ksl.examples.general.running

import ksl.controls.experiments.DesignedExperiment
import ksl.controls.experiments.LinearModel
import ksl.controls.experiments.TwoLevelFactor
import ksl.controls.experiments.TwoLevelFactorialDesign
import ksl.examples.book.chapter7.RQInventorySystem
import ksl.simulation.Model
import ksl.utilities.io.addColumnsFor
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import org.jetbrains.kotlinx.dataframe.api.print

fun main() {
    val m = rQModel()
//    runRQModel(m)
//    printControlsAndRVs(m)
    rQExperiment(m)
}

fun printControlsAndRVs(m: Model) {
    val controls = m.controls()
    val rvp = m.rvParameterSetter
    println()
    println("Controls")
    println(controls)
    println()
    println("RV Parameters")
    println(rvp)
    println()
}

fun runRQModel(m: Model){
    m.simulate()
    m.print()
}

fun rQModel(): Model {
    val m = Model("ResponseSurfaceDemo")
    val rqModel = RQInventorySystem(m, name = "RQInventory")
    rqModel.costPerOrder = 0.15 //$ per order
    rqModel.unitHoldingCost = 0.25 //$ per unit per month
    rqModel.unitBackorderCost = 1.75 //$ per unit per month
    rqModel.initialReorderPoint = 2
    rqModel.initialReorderQty = 3
    rqModel.initialOnHand = rqModel.initialReorderPoint + rqModel.initialReorderQty
    rqModel.timeBetweenDemand.initialRandomSource = ExponentialRV(1.0 / 3.6)
    rqModel.leadTime.initialRandomSource = ConstantRV(0.5)

    m.lengthOfReplication = 72.0
    m.lengthOfReplicationWarmUp = 12.0
    m.numberOfReplications = 30
    return m
}

fun rQExperiment(m: Model) {
    val r = TwoLevelFactor("ReorderLevel", low = 1.0, high = 5.0)
    println(r)
    val q = TwoLevelFactor("ReorderQty", low = 1.0, high = 7.0)
    println(q)
    println()
    val design = TwoLevelFactorialDesign(setOf(r, q))
    println("Design points being simulated")
    val df = design.designPointsAsDataframe()
    df.print(rowsLimit = 36)
    val settings = mapOf(
        r to "RQInventory:Item.initialReorderPoint",
        q to "RQInventory:Item.initialReorderQty",
    )
    val de = DesignedExperiment("R-Q Inventory Experiment", m, settings, design)
    de.simulateAll(numRepsPerDesignPoint = 20)
    println("Simulation of the design is completed")
    println()
    val resultsDf = de.replicatedDesignPointsWithResponse("RQInventory:Item:TotalCost", coded = true)
    resultsDf.print(rowsLimit = 80)
    println()
    val lm = design.linearModel(type = LinearModel.Type.AllTerms)
    println(lm.asString())
    println()
    val lmDF = resultsDf.addColumnsFor(lm)
    lmDF.print(rowsLimit = 80)
    val regressionResults = de.regressionResults("RQInventory:Item:TotalCost", lm)
    println()
    println(regressionResults)
    regressionResults.showResultsInBrowser()
}