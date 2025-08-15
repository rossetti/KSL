package ksl.examples.general.simopt

import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.Solver
import ksl.simulation.ModelBuilderIfc

enum class SolverType {
    SHC, SA, CE, R_SPLINE, SHC_RS, SA_RS, CE_RS, R_SPLINE_RS
}

fun main() {
    //  val modelIdentifier = "RQInventoryModel"
    val modelIdentifier = "LKInventoryModel"
//    val solverType = SolverType.SHC_RS
//    val solverType = SolverType.R_SPLINE_RS
    val solverType = SolverType.SA_RS
    runSolver(modelIdentifier, solverType)
}

fun runSolver(modelIdentifier: String, solverType: SolverType) {
    val problemDefinition = makeProblemDefinition(modelIdentifier)
    val modelBuilder = selectBuilder(modelIdentifier)
    val printer = selectPrinter(modelIdentifier)
    val solver = solverFactory(solverType, problemDefinition, modelBuilder, printer)
    solver.runAllIterations()
    println()
    println("Solver Results:")
    println(solver)
    println()
    println("Final Solution:")
    println(solver.bestSolution.asString())
}

fun solverFactory(
    solverType: SolverType,
    problemDefinition: ProblemDefinition,
    modelBuilder: ModelBuilderIfc,
    printer: (Solver) -> Unit
): Solver {
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
                printer = printer
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