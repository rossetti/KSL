package ksl.examples.general.simopt

import ksl.simopt.solvers.Solver

fun main() {

  //  val modelIdentifier = "RQInventoryModel"
    val modelIdentifier = "LKInventoryModel"
    val problemDefinition = makeProblemDefinition(modelIdentifier)
    val modelBuilder = selectBuilder(modelIdentifier)
    val printer = selectPrinter(modelIdentifier)
    val solver = Solver.rSPLINESolver(
        problemDefinition = problemDefinition,
        modelBuilder = modelBuilder,
        startingPoint = null,
        maxIterations = 100,
        printer = printer,
    )
    solver.runAllIterations()
    println()
    println("Solver Results:")
    println(solver)
    println()
    println("Final Solution:")
    println(solver.bestSolution.asString())
}

