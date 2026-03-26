package ksl.examples.general.simopt

import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.trackers.ConsoleSolverStateTracker
import ksl.simopt.solvers.trackers.CsvSolverStateTracker


fun main() {

  //  val modelIdentifier = "RQInventoryModel"
    val modelIdentifier = "LKInventoryModel"
    val initialTemperature = 1000.0
    val problemDefinition = makeProblemDefinition(modelIdentifier)
    val modelBuilder = selectBuilder(modelIdentifier)
    val printer = selectPrinter(modelIdentifier)
    val solver = Solver.simulatedAnnealingSolver(
        problemDefinition = problemDefinition,
        modelBuilder = modelBuilder,
        startingPoint = null,
        initialTemperature = initialTemperature,
        maxIterations = 10,
        replicationsPerEvaluation = 50,
    )
    val tracker = ConsoleSolverStateTracker(solver)
    tracker.startTracking()
    val csvTracker = CsvSolverStateTracker(solver, "SA_${modelIdentifier}")
    csvTracker.startTracking()
    solver.runAllIterations()
    println()
    println("Solver Results:")
    println(solver)
    println()
    println("Final Solution:")
    println(solver.bestSolution.asString())

}
