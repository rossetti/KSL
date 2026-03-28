package ksl.examples.general.simopt

import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.algorithms.RandomRestartSolver
import ksl.simopt.solvers.trackers.ConsoleSolverStateTracker

fun main() {
    val modelIdentifier = "RQInventoryModel"
 //   val modelIdentifier = "LKInventoryModel"
    val problemDefinition = makeProblemDefinition(modelIdentifier)
    val modelBuilder = selectBuilder(modelIdentifier)
    val solver = Solver.createStochasticHillClimbingSolver(
        problemDefinition = problemDefinition,
        modelBuilder = modelBuilder,
        startingPoint = null,
        maxIterations = 100,
        replicationsPerEvaluation = 50,
    )
    val tracker = ConsoleSolverStateTracker(solver)
    tracker.startTracking()
    solver.runAllIterations()
    println()
    println("Solver Results:")
    println(solver)
    println()
    solver.printResults()
    println()
    println("Final (Best) Solution Found:")
    println(solver.bestSolution.toString())
}

