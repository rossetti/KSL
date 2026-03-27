package ksl.examples.general.simopt

import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.trackers.ConsoleSolverStateTracker
import ksl.simopt.solvers.trackers.CsvSolverStateTracker


fun main() {

    val modelIdentifier = "RQInventoryModel"
 //   val modelIdentifier = "LKInventoryModel"
    val problemDefinition = makeProblemDefinition(modelIdentifier)
    val modelBuilder = selectBuilder(modelIdentifier)
    val solver = Solver.createSimulatedAnnealingSolver(
        problemDefinition = problemDefinition,
        modelBuilder = modelBuilder,
        startingPoint = null,
        maxIterations = 100,
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
    println("Final (Best) Solution Found:")
    println(solver.bestSolution.toString())

}
