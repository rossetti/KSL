package ksl.examples.general.simopt

import ksl.controls.experiments.SimulationRunner
import ksl.examples.general.models.LKInventoryModel
import ksl.simopt.problem.ProblemDefinition
import ksl.simulation.Model

fun main() {

    //simulationRunner()
    buildModel()

}

fun basicRunning(){
    val m = buildModel()
    m.simulate()
    m.print()
}

fun simulationRunner(){
    //val m = buildModel()
    val sim = SimulationRunner(buildModel())
    val sr = sim.simulate()
    val reporter = sr.statisticalReporter()
    reporter.printHalfWidthSummaryReport()
}

fun buildModel() : Model {
    val model = Model("LKInventoryModel")
    val lkInventoryModel = LKInventoryModel(model, "InventoryModel")
    model.lengthOfReplication = 120.0
    model.numberOfReplications = 1000
    model.lengthOfReplicationWarmUp = 20.0
    val controls = model.controls()
//    val cMap = controls.controlsByModelElement()
//    println()
//    println(controls)
//    println()
//    println("Controls by Model Element")
//    for((key, value) in cMap) {
//        val names: List<String> = value.map { it.keyName }
//        println("$key: ${names.joinToString(", ")}")
//    }
//    println()
    controls.printControls()
    return model
}

fun makeProblemDefinition() {
    val problemDefinition = ProblemDefinition(
        problemName = "InventoryProblem",
        modelIdentifier = "LKInventoryModel",
        objFnResponseName = "TotalCost",
        inputNames = listOf("LKInventoryModel.orderUpToLevel", "LKInventoryModel.reorderPoint"),
    )
}