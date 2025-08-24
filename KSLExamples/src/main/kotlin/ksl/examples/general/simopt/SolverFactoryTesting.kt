package ksl.examples.general.simopt

import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.algorithms.StochasticSolver
import ksl.simulation.ModelBuilderIfc
import org.jetbrains.kotlinx.dataframe.api.describe
import org.jetbrains.kotlinx.dataframe.api.print
import org.jetbrains.kotlinx.dataframe.api.schema
import org.jetbrains.kotlinx.dataframe.api.toDataFrame

enum class SolverType {
    SHC, SA, CE, R_SPLINE, SHC_RS, SA_RS, CE_RS, R_SPLINE_RS
}

fun main() {
    //  val modelIdentifier = "RQInventoryModel"
    val modelIdentifier = "LKInventoryModel"
//    val solverType = SolverType.R_SPLINE
//    val solverType = SolverType.CE
    val solverType = SolverType.SHC
//    val solverType = SolverType.R_SPLINE_RS
//    val solverType = SolverType.SA_RS
//        val solverType = SolverType.SHC_RS
    runSolver(modelIdentifier, solverType)
}

fun runSolver(modelIdentifier: String, solverType: SolverType) {
    val problemDefinition = makeProblemDefinition(modelIdentifier)
    val modelBuilder = selectBuilder(modelIdentifier)
    val printer = selectPrinter(modelIdentifier)
    val solver = solverFactory(solverType, problemDefinition, modelBuilder, printer)
//   solver.useRandomlyBestStartingPoint()
//   solver.advanceToNextSubStream()
    solver.runAllIterations()
    println()
    println("Solver Results:")
    println(solver)
    println()
    println("Final Solution:")
    println(solver.bestSolution.asString())
    println("Approximate screening:")
    val solutions = solver.bestSolutions.possiblyBest()
    println(solutions)
//    val df = solver.bestSolutions.toDataFrame()
//    df.schema().print()
//    df.print()
}

fun solverFactory(
    solverType: SolverType,
    problemDefinition: ProblemDefinition,
    modelBuilder: ModelBuilderIfc,
    printer: (Solver) -> Unit
): StochasticSolver {
    return when(solverType){
        SolverType.SHC -> {
            Solver.stochasticHillClimbingSolver(
                problemDefinition = problemDefinition,
                modelBuilder = modelBuilder,
                startingPoint = null,
                maxIterations = 100,
                replicationsPerEvaluation = 50,
                printer = printer,
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
            )
        }
        SolverType.R_SPLINE -> {
            Solver.rSplineSolver(
                problemDefinition = problemDefinition,
                modelBuilder = modelBuilder,
                startingPoint = null,
                maxIterations = 100,
                printer = printer,
            )
        }
        SolverType.SHC_RS -> {
            Solver.stochasticHillClimbingSolverWithRestarts(
                problemDefinition = problemDefinition,
                modelBuilder = modelBuilder,
                maxIterations = 100,
                replicationsPerEvaluation = 50,
                restartPrinter = printer,
                printer = null
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
                printer = null
            )
        }
        SolverType.CE_RS -> {
            Solver.crossEntropySolverWithRestarts(
                problemDefinition = problemDefinition,
                modelBuilder = modelBuilder,
                maxIterations = 100,
                replicationsPerEvaluation = 50,
                restartPrinter = printer,
                printer = null
            )
        }
        SolverType.R_SPLINE_RS -> {
            Solver.rSplineSolverWithRestarts(
                problemDefinition = problemDefinition,
                modelBuilder = modelBuilder,
                maxIterations = 100,
                restartPrinter = printer,
                printer = null
            )
        }
    }
}