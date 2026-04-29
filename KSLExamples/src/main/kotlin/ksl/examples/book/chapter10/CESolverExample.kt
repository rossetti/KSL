package ksl.examples.book.chapter10

import ksl.examples.book.chapter7.RQInventorySystem
import ksl.simopt.cache.SimulationRunCacheIfc
import ksl.simopt.problem.InequalityType
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.algorithms.RandomRestartSolver
import ksl.simopt.solvers.trackers.ConsoleSolverStateTracker
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.utilities.Interval
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import org.jetbrains.kotlinx.dataframe.api.print
import org.jetbrains.kotlinx.dataframe.api.schema


fun main(){
    runCESolver()
}

fun runCESolver(
    simulationRunCache: SimulationRunCacheIfc? = null,
    experimentRunParameters: ExperimentRunParametersIfc? = null
) {
    val problemDefinition = makeRQInventoryModelProblemDefinition()
    val modelBuilder = BuildRQModel

    val solver = Solver.createCrossEntropySolver(
        problemDefinition = problemDefinition,
        modelBuilder = modelBuilder,
        startingPoint = null,
        maxIterations = 100,
        replicationsPerEvaluation = 50,
        simulationRunCache = simulationRunCache,
        experimentRunParameters = experimentRunParameters
    )
    val tracker = ConsoleSolverStateTracker(solver)
    tracker.startTracking()
    solver.runAllIterations()
    println()
    println(solver)
    println()
    println("Solver Results Summary:")
    solver.printResults()
    println()
    println("Final (Best) Solution:")
    println(solver.bestSolution.asString())
    println()
    println("Approximate screening:")
    val solutions = solver.bestSolutions.possiblyBest()
    println(solutions)
    println("Dataframe")
    val df = solver.bestSolutions.toDataFrame()
    df.schema().print()
    df.print()
}

fun makeRQInventoryModelProblemDefinition(): ProblemDefinition {
    val problemDefinition = ProblemDefinition(
        problemName = "InventoryProblem",
        modelIdentifier = "RQInventoryModel",
        objFnResponseName = "Inventory:Item:OrderingAndHoldingCost",
        inputNames = listOf("Inventory:Item.initialReorderQty", "Inventory:Item.initialReorderPoint"),
        responseNames = listOf("Inventory:Item:FillRate")
    )
    problemDefinition.inputVariable(
        name = "Inventory:Item.initialReorderQty",
        interval = Interval(1.0, 100.0),
        granularity = 1.0
    )
    problemDefinition.inputVariable(
        name = "Inventory:Item.initialReorderPoint",
        interval = Interval(1.0, 100.0),
        granularity = 1.0
    )
    problemDefinition.responseConstraint(
        name = "Inventory:Item:FillRate",
        rhsValue = 0.95,
        inequalityType = InequalityType.GREATER_THAN
    )
    return problemDefinition
}

object BuildRQModel : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        val reorderQty: Int = 2
        val reorderPoint: Int = 1
        val model = Model("RQInventoryModel")
        val rqModel = RQInventorySystem(model, reorderPoint, reorderQty, "Inventory")
        rqModel.initialOnHand = 0
        rqModel.demandGenerator.initialTimeBtwEvents = ExponentialRV(1.0 / 3.6)
        rqModel.leadTime.initialRandomSource = ConstantRV(0.5)
        model.lengthOfReplication = 20000.0
        model.lengthOfReplicationWarmUp = 10000.0
        model.numberOfReplications = 40
        return model
    }
}