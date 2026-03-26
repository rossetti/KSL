package ksl.examples.general.simopt

import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.trackers.ConsoleSolverStateTracker

fun main() {

  //  val modelIdentifier = "RQInventoryModel"
    val modelIdentifier = "LKInventoryModel"
    val problemDefinition = makeProblemDefinition(modelIdentifier)
    val modelBuilder = selectBuilder(modelIdentifier)
    val printer = selectPrinter(modelIdentifier)
    val solver = Solver.rSplineSolver(
        problemDefinition = problemDefinition,
        modelBuilder = modelBuilder,
        startingPoint = null,
        maxIterations = 100,
    )
    val tracker = ConsoleSolverStateTracker(solver)
    tracker.startTracking()
    solver.runAllIterations()
    println()
    println("Solver Results:")
    println(solver)
    println()
    println("Final Solution:")
    println(solver.bestSolution.asString())
}


