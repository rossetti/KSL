package ksl.examples.book.chapter10

import ksl.simopt.cache.SimulationRunCacheIfc
import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.trackers.ConsoleSolverStateTracker
import ksl.simopt.solvers.trackers.NestedConsoleSolverStateTracker
import ksl.simopt.solvers.trackers.NestedCsvSolverStateTracker
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
    val initialTemperature = 100.0
    val solver = Solver.simulatedAnnealingSolverWithRestarts(
        problemDefinition = problemDefinition,
        modelBuilder = modelBuilder,
        initialTemperature = initialTemperature,
        maxIterations = 10,
        replicationsPerEvaluation = 50,
        simulationRunCache = simulationRunCache,
        experimentRunParameters = experimentRunParameters,
        defaultKSLDatabaseObserverOption = defaultKSLDatabaseObserverOption
    )
    val tracker = NestedConsoleSolverStateTracker(solver, solver.restartingSolver)
    tracker.startTracking()
    val csvTracker =
        NestedCsvSolverStateTracker(solver, solver.restartingSolver, "SA_Restart_${problemDefinition.modelIdentifier}")
    csvTracker.startTracking()
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