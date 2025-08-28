package ksl.examples.general.simopt

import ksl.simopt.cache.MemorySimulationRunCache
import ksl.simopt.cache.SimulationRunCacheIfc
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.algorithms.StochasticSolver
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.ModelBuilderIfc
import ksl.utilities.io.KSL
import ksl.utilities.io.toTabularFile
import org.jetbrains.kotlinx.dataframe.api.describe
import org.jetbrains.kotlinx.dataframe.api.print
import org.jetbrains.kotlinx.dataframe.api.schema
import org.jetbrains.kotlinx.dataframe.api.toDataFrame
import org.jetbrains.kotlinx.dataframe.io.writeCsv
import org.jetbrains.kotlinx.dataframe.io.writeExcel

enum class SolverType {
    SHC, SA, CE, R_SPLINE, SHC_RS, SA_RS, CE_RS, R_SPLINE_RS
}



fun main() {
//    var simulationRunCache = MemorySimulationRunCache()
    //  val modelIdentifier = "RQInventoryModel"
    val modelIdentifier = "LKInventoryModel"
//    val solverType = SolverType.R_SPLINE
    val solverType = SolverType.CE
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
            Solver.stochasticHillClimbingSolver(
                problemDefinition = problemDefinition,
                modelBuilder = modelBuilder,
                startingPoint = null,
                maxIterations = 100,
                replicationsPerEvaluation = 50,
                simulationRunCache = simulationRunCache,
                printer = printer,
                experimentRunParameters = experimentRunParameters,
                defaultKSLDatabaseObserverOption = defaultKSLDatabaseObserverOption
            )
        }
        SolverType.SA -> {
            val initialTemperature = 1000.0
            Solver.simulatedAnnealingSolver(
                problemDefinition = problemDefinition,
                modelBuilder = modelBuilder,
                startingPoint = null,
                initialTemperature = initialTemperature,
                maxIterations = 100,
                replicationsPerEvaluation = 50,
                printer = printer,
                simulationRunCache = simulationRunCache,
                experimentRunParameters = experimentRunParameters,
                defaultKSLDatabaseObserverOption = defaultKSLDatabaseObserverOption
            )
        }
        SolverType.CE -> {
            Solver.crossEntropySolver(
                problemDefinition = problemDefinition,
                modelBuilder = modelBuilder,
                startingPoint = null,
                maxIterations = 100,
                replicationsPerEvaluation = 50,
                printer = printer,
                simulationRunCache = simulationRunCache,
                experimentRunParameters = experimentRunParameters,
                defaultKSLDatabaseObserverOption = defaultKSLDatabaseObserverOption
            )
        }
        SolverType.R_SPLINE -> {
            Solver.rSplineSolver(
                problemDefinition = problemDefinition,
                modelBuilder = modelBuilder,
                startingPoint = null,
                maxIterations = 100,
                printer = printer,
                simulationRunCache = simulationRunCache,
                experimentRunParameters = experimentRunParameters,
                defaultKSLDatabaseObserverOption = defaultKSLDatabaseObserverOption
            )
        }
        SolverType.SHC_RS -> {
            Solver.stochasticHillClimbingSolverWithRestarts(
                problemDefinition = problemDefinition,
                modelBuilder = modelBuilder,
                maxIterations = 100,
                replicationsPerEvaluation = 50,
                restartPrinter = printer,
                printer = null,
                simulationRunCache = simulationRunCache,
                experimentRunParameters = experimentRunParameters,
                defaultKSLDatabaseObserverOption = defaultKSLDatabaseObserverOption
            )
        }
        SolverType.SA_RS -> {
            val initialTemperature = 1000.0
            Solver.simulatedAnnealingSolverWithRestarts(
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
        }
        SolverType.CE_RS -> {
            Solver.crossEntropySolverWithRestarts(
                problemDefinition = problemDefinition,
                modelBuilder = modelBuilder,
                maxIterations = 100,
                replicationsPerEvaluation = 50,
                restartPrinter = printer,
                printer = null,
                simulationRunCache = simulationRunCache,
                experimentRunParameters = experimentRunParameters,
                defaultKSLDatabaseObserverOption = defaultKSLDatabaseObserverOption
            )
        }
        SolverType.R_SPLINE_RS -> {
            Solver.rSplineSolverWithRestarts(
                problemDefinition = problemDefinition,
                modelBuilder = modelBuilder,
                maxIterations = 100,
                restartPrinter = printer,
                printer = null,
                simulationRunCache = simulationRunCache,
                experimentRunParameters = experimentRunParameters,
                defaultKSLDatabaseObserverOption = defaultKSLDatabaseObserverOption
            )
        }
    }
}