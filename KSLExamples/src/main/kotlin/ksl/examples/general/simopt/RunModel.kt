package ksl.examples.general.simopt

import ksl.controls.experiments.SimulationRunner
import ksl.examples.general.models.LKInventoryModel
import ksl.simopt.problem.ProblemDefinition
import ksl.simulation.Model
import ksl.utilities.Interval

fun main() {

 //   simulationRunner()
    //buildModel()
    makeProblemDefinition()

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
    println("Model Controls:")
    controls.printControls()
    println()
    return model
}

fun makeProblemDefinition() {
    val problemDefinition = ProblemDefinition(
        problemName = "InventoryProblem",
        modelIdentifier = "LKInventoryModel",
        objFnResponseName = "TotalCost",
        inputNames = listOf("InventoryModel.orderQuantity", "InventoryModel.reorderPoint"),
    )
   problemDefinition.inputVariable(
       name = "InventoryModel.orderQuantity",
       interval = Interval(1.0, 100.0),
       granularity = 1.0
   )
    problemDefinition.inputVariable(
        name = "InventoryModel.reorderPoint",
        interval = Interval(1.0, 100.0),
        granularity = 1.0
    )

    println(problemDefinition)
}