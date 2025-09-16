package ksl.examples.book.chapter10

import ksl.simopt.cache.SimulationRunCacheIfc
import ksl.simopt.solvers.Solver
import ksl.simulation.ExperimentRunParametersIfc
import org.jetbrains.kotlinx.dataframe.api.print
import org.jetbrains.kotlinx.dataframe.api.schema

fun main() {
  runSimulatedAnnealingWithRestarts()
}

fun runSimulatedAnnealingWithRestarts(
    simulationRunCache: SimulationRunCacheIfc? = null,
    experimentRunParameters: ExperimentRunParametersIfc? = null,
    defaultKSLDatabaseObserverOption: Boolean = false
) {
    val problemDefinition = makeRQInventoryModelProblemDefinition()
    val modelBuilder = BuildRQModel
    val printer = ::printRQInventoryModel
    val initialTemperature = 1000.0
    val solver = Solver.simulatedAnnealingSolverWithRestarts(
        problemDefinition = problemDefinition,
        modelBuilder = modelBuilder,
        initialTemperature = initialTemperature,
        maxIterations = 100,
        replicationsPerEvaluation = 50,
        restartPrinter = printer,
        printer = null,
        simulationRunCache = simulationRunCache,
        experimentRunParameters = experimentRunParameters,
        defaultKSLDatabaseObserverOption = defaultKSLDatabaseObserverOption
    )
    solver.runAllIterations()
    println()
    println("Solver Results:")
    println(solver)
    println()
    println("Final Solution:")
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