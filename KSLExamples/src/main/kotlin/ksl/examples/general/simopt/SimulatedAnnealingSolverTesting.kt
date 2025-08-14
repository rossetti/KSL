package ksl.examples.general.simopt

import ksl.simopt.solvers.Solver


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
