package ksl.examples.general.simopt

import ksl.controls.experiments.SimulationRunner
import ksl.examples.book.chapter7.RQInventorySystem
import ksl.examples.general.models.LKInventoryModel
import ksl.simopt.cache.MemorySolutionCache
import ksl.simopt.evaluator.Evaluator
import ksl.simopt.evaluator.SimulationProvider
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.InequalityType
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.algorithms.StochasticHillClimber
import ksl.simulation.Model
import ksl.utilities.Interval
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV

fun main() {

//    simulationRunner2()
    //buildModel()
    val problemDefinition = makeProblemDefinition2()
    println(problemDefinition)

//   runSolverTest()

  //  testRunning(14, 33)


}

fun runSolverTest2() {
    val evaluator = setUpEvaluator2()
    val shc = StochasticHillClimber(evaluator, maxIterations = 1000, replicationsPerEvaluation = 50)
//    shc.emitter.attach { printSolution(it) }

    shc.runAllIterations()
    println(evaluator)
    println()
    println("Solver Results:")
    println(shc)
}

fun printSolution2(solution: Solution) {
    val q = solution.inputMap["RQInventoryModel:Item.initialReorderQty"]
    val rp = solution.inputMap["RQInventoryModel:Item.initialReorderPoint"]
    println("${solution.estimatedObjFncValue} \t $q \t $rp")
}

fun basicRunning2(){
    val m = buildModel2()
    m.simulate()
    m.print()
}

fun simulationRunner2(){
    //val m = buildModel()
    val sim = SimulationRunner(buildModel2())
    val sr = sim.simulate()
    val reporter = sr.statisticalReporter()
    reporter.printHalfWidthSummaryReport()
}

fun testRunning2(orderQuantity: Int, reorderPoint: Int){
    val model = buildModel2(orderQuantity, reorderPoint)
    model.simulate()
    model.print()
}

fun buildModel2(reorderQty: Int = 2, reorderPoint: Int = 1) : Model {
    val model = Model("InventoryModel")
    val rqModel = RQInventorySystem(model, reorderPoint, reorderQty, "RQInventoryModel")
    rqModel.initialOnHand = 0
    rqModel.demandGenerator.initialTimeBtwEvents = ExponentialRV(1.0 / 3.6)
    rqModel.leadTime.initialRandomSource = ConstantRV(0.5)

    model.lengthOfReplication = 110000.0
    model.lengthOfReplicationWarmUp = 10000.0
    model.numberOfReplications = 40

    val controls = model.controls()
    println("Model Controls:")
    controls.printControls()
    println()
    return model
}

fun makeProblemDefinition2() : ProblemDefinition {
    val problemDefinition = ProblemDefinition(
        problemName = "InventoryProblem",
        modelIdentifier = "RQInventoryModel",
        objFnResponseName = "RQInventoryModel:Item:OrderingAndHoldingCost",
        inputNames = listOf("RQInventoryModel:Item.initialReorderQty", "RQInventoryModel:Item.initialReorderPoint"),
        responseNames = listOf("RQInventoryModel:Item:FillRate")
    )
   problemDefinition.inputVariable(
       name = "RQInventoryModel:Item.initialReorderQty",
       interval = Interval(1.0, 100.0),
       granularity = 1.0
   )
    problemDefinition.inputVariable(
        name = "RQInventoryModel:Item.initialReorderPoint",
        interval = Interval(1.0, 100.0),
        granularity = 1.0
    )
    problemDefinition.responseConstraint(
        name = "RQInventoryModel:Item:FillRate",
        rhsValue = 0.90,
        inequalityType = InequalityType.GREATER_THAN
    )

    return problemDefinition
}

fun setUpEvaluator2() : Evaluator {
    val simulationProvider = SimulationProvider(buildModel2())
    val problemDefinition = makeProblemDefinition2()
    val cache = MemorySolutionCache()
    val evaluator = Evaluator(
        problemDefinition,
        simulationProvider,
        cache
    )
    return evaluator
}