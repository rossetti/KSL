package ksl.examples.general.simopt

import ksl.simopt.cache.SimulationRunCacheIfc
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.algorithms.StochasticSolver
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.ModelBuilderIfc
import org.jetbrains.kotlinx.dataframe.api.print
import org.jetbrains.kotlinx.dataframe.api.schema

enum class SolverType {
    SHC, SA, CE, R_SPLINE, SHC_RS, SA_RS, CE_RS, R_SPLINE_RS
}



fun main() {
//    var simulationRunCache = MemorySimulationRunCache()
      val modelIdentifier = "RQInventoryModel"
//    val modelIdentifier = "LKInventoryModel"
//    val solverType = SolverType.R_SPLINE
    val solverType = SolverType.SA
//    val solverType = SolverType.CE
//    val solverType = SolverType.SHC
//    val solverType = SolverType.R_SPLINE_RS
//    val solverType = SolverType.SA_RS
//        val solverType = SolverType.SHC_RS
    runSolver(modelIdentifier, solverType, defaultKSLDatabaseObserverOption = false)

//    if (solverType == SolverType.SHC) {
//
//        println("Number of simulation runs in the cache = ${simulationRunCache.size}")
//        val mapDF = simulationRunCache.toDataFramesGroupedByModelInputNames()
//        println("Number of dataframes: ${mapDF.size}")
//        var i = 1
//        for((key, df) in mapDF) {
//            println(key.joinToString())
//            println("-------------")
//           // println(df.print(rowsLimit = 100))
//            println()
//            df.writeCsv(KSL.csvDir.resolve("simulationRuns${i}.csv"))
//            df.toTabularFile("simulationRuns${i}")
//            i++
//        }
//    }

}

fun runSolver(
    modelIdentifier: String,
    solverType: SolverType,
    simulationRunCache: SimulationRunCacheIfc? = null,
    experimentRunParameters: ExperimentRunParametersIfc? = null,
    defaultKSLDatabaseObserverOption: Boolean = false
) {
    val problemDefinition = makeProblemDefinition(modelIdentifier)
    val modelBuilder = selectBuilder(modelIdentifier)
    val printer = selectPrinter(modelIdentifier)
    val solver = solverFactory(solverType, problemDefinition, modelBuilder,
        printer, simulationRunCache, experimentRunParameters, defaultKSLDatabaseObserverOption)
//   solver.useRandomlyBestStartingPoint()
//   solver.advanceToNextSubStream()
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

fun solverFactory(
    solverType: SolverType,
    problemDefinition: ProblemDefinition,
    modelBuilder: ModelBuilderIfc,
    printer: (Solver) -> Unit,
    simulationRunCache: SimulationRunCacheIfc? = null,
    experimentRunParameters: ExperimentRunParametersIfc? = null,
    defaultKSLDatabaseObserverOption: Boolean = false
): StochasticSolver {
    return when(solverType){
        SolverType.SHC -> {
            Solver.createStochasticHillClimbingSolver(
                problemDefinition = problemDefinition,
                modelBuilder = modelBuilder,
                startingPoint = null,
                maxIterations = 100,
                replicationsPerEvaluation = 50,
                simulationRunCache = simulationRunCache,
                experimentRunParameters = experimentRunParameters,
                defaultKSLDatabaseObserverOption = defaultKSLDatabaseObserverOption
            )
        }
        SolverType.SA -> {
            Solver.createSimulatedAnnealingSolver(
                problemDefinition = problemDefinition,
                modelBuilder = modelBuilder,
                startingPoint = null,
                maxIterations = 100,
                replicationsPerEvaluation = 50,
                simulationRunCache = simulationRunCache,
                experimentRunParameters = experimentRunParameters,
                defaultKSLDatabaseObserverOption = defaultKSLDatabaseObserverOption
            )
        }
        SolverType.CE -> {
            Solver.createCrossEntropySolver(
                problemDefinition = problemDefinition,
                modelBuilder = modelBuilder,
                startingPoint = null,
                maxIterations = 100,
                replicationsPerEvaluation = 50,
                simulationRunCache = simulationRunCache,
                experimentRunParameters = experimentRunParameters,
                defaultKSLDatabaseObserverOption = defaultKSLDatabaseObserverOption
            )
        }
        SolverType.R_SPLINE -> {
            Solver.createRsplineSolver(
                problemDefinition = problemDefinition,
                modelBuilder = modelBuilder,
                startingPoint = null,
                maxIterations = 100,
                simulationRunCache = simulationRunCache,
                experimentRunParameters = experimentRunParameters,
                defaultKSLDatabaseObserverOption = defaultKSLDatabaseObserverOption
            )
        }
        SolverType.SHC_RS -> {
            Solver.createRandomRestartStochasticHillClimbingSolver(
                problemDefinition = problemDefinition,
                modelBuilder = modelBuilder,
                maxIterations = 100,
                replicationsPerEvaluation = 50,
                simulationRunCache = simulationRunCache,
                experimentRunParameters = experimentRunParameters,
                defaultKSLDatabaseObserverOption = defaultKSLDatabaseObserverOption
            )
        }
        SolverType.SA_RS -> {
            Solver.createRandomRestartSimulatedAnnealingSolver(
                problemDefinition = problemDefinition,
                modelBuilder = modelBuilder,
                maxIterations = 100,
                replicationsPerEvaluation = 50,
                simulationRunCache = simulationRunCache,
                experimentRunParameters = experimentRunParameters,
                defaultKSLDatabaseObserverOption = defaultKSLDatabaseObserverOption
            )
        }
        SolverType.CE_RS -> {
            Solver.createRandomRestartCrossEntropySolver(
                problemDefinition = problemDefinition,
                modelBuilder = modelBuilder,
                maxIterations = 100,
                replicationsPerEvaluation = 50,
                simulationRunCache = simulationRunCache,
                experimentRunParameters = experimentRunParameters,
                defaultKSLDatabaseObserverOption = defaultKSLDatabaseObserverOption
            )
        }
        SolverType.R_SPLINE_RS -> {
            Solver.createRandomRestartRsplineSolver(
                problemDefinition = problemDefinition,
                modelBuilder = modelBuilder,
                maxIterations = 100,
                simulationRunCache = simulationRunCache,
                experimentRunParameters = experimentRunParameters,
                defaultKSLDatabaseObserverOption = defaultKSLDatabaseObserverOption
            )
        }
    }
}