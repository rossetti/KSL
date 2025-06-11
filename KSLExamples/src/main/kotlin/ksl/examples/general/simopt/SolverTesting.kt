package ksl.examples.general.simopt

import ksl.controls.experiments.SimulationRunner
import ksl.examples.general.models.LKInventoryModel
import ksl.simopt.cache.MemorySimulationRunCache
import ksl.simopt.cache.MemorySolutionCache
import ksl.simopt.evaluator.Evaluator
import ksl.simopt.evaluator.SimulationProvider
import ksl.simopt.evaluator.SimulationService
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.algorithms.StochasticHillClimber
import ksl.simulation.MapModelProvider
import ksl.simulation.Model
import ksl.utilities.Interval

fun main() {

 //   simulationRunner()
    //buildModel()
   // makeProblemDefinition()

   runSolverTest()

  //  testRunning(14, 33)


}

fun runSolverTest() {
    val evaluator = setUpEvaluator()
    val shc = StochasticHillClimber(evaluator, maxIterations = 1000, replicationsPerEvaluation = 50)
//    shc.emitter.attach { printSolution(it) }

    shc.runAllIterations()
    println(evaluator)
    println()
    println("Solver Results:")
    println(shc)
}

fun printSolution(solution: Solution) {
    val q = solution.inputMap["InventoryModel.orderQuantity"]
    val rp = solution.inputMap["InventoryModel.reorderPoint"]
    println("${solution.estimatedObjFncValue} \t $q \t $rp")
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

fun testRunning(orderQuantity: Int, reorderPoint: Int){
    val model = buildModel(orderQuantity, reorderPoint)
    model.simulate()
    model.print()
}

fun buildModel(orderQuantity: Int = 20, reorderPoint: Int = 20) : Model {
    val model = Model("LKInventoryModel")
    val lkInventoryModel = LKInventoryModel(model, "InventoryModel")
    model.lengthOfReplication = 120.0
    model.numberOfReplications = 1000
    model.lengthOfReplicationWarmUp = 20.0
    lkInventoryModel.orderQuantity = orderQuantity
    lkInventoryModel.reorderPoint = reorderPoint
//    val controls = model.controls()
//    println("Model Controls:")
//    controls.printControls()
//    println()
    return model
}

fun makeProblemDefinition() : ProblemDefinition {
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

//    println(problemDefinition)

//    println("Random Starting Points:")
//    for (i in 1..5) {
//        val point = problemDefinition.startingPoint()
//        println(point)
//    }
    return problemDefinition
}

fun setUpEvaluator() : Evaluator {
    // val simulationProvider = SimulationProvider(buildModel2())
    val simulationProvider = setUpSimulationService()
    val problemDefinition = makeProblemDefinition()
    val cache = MemorySolutionCache()
    val evaluator = Evaluator(
        problemDefinition,
        simulationProvider,
        cache
    )
    return evaluator
}

fun setUpSimulationService() : SimulationService {

    val mapModelProvider = MapModelProvider()
    mapModelProvider.addModelCreator("RQInventoryModel", { buildModel2() })
    val simulationService = SimulationService(
        modelProvider = mapModelProvider,
        simulationRunCache = MemorySimulationRunCache(),
        useCachedSimulationRuns = true
    )

    return simulationService
}