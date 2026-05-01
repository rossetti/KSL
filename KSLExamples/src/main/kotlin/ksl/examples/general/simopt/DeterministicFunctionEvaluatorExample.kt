package ksl.examples.general.simopt

import ksl.simopt.evaluator.DeterministicFunctionEvaluator
import ksl.simopt.evaluator.InputEquality
import ksl.simopt.evaluator.ObjectiveFunctionIfc
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.algorithms.StochasticHillClimber

fun main() {
    val problem = ProblemDefinition(
        problemName = "Deterministic Quadratic Function",
        modelIdentifier = "QuadraticFunction",
        objFnResponseName = "Objective",
        inputNames = listOf("x", "y")
    )

    problem.inputVariable("x", -10.0, 10.0)
    problem.inputVariable("y", -10.0, 10.0)

    val evaluator = DeterministicFunctionEvaluator.forObjective(
        problemDefinition = problem,
        objective = ObjectiveFunctionIfc { x ->
            val dx = x[0] - 1.0
            val dy = x[1] + 2.0
            dx * dx + dy * dy
        }
    )

    val solver = StochasticHillClimber(
        problemDefinition = problem,
        evaluator = evaluator,
        maxIterations = 100,
        replicationsPerEvaluation = 1,
        name = "QuadraticFunctionHillClimber"
    )
    //TODO this is needed for deterministic case
    solver.solutionChecker.equalityChecker = InputEquality

    solver.startingPoint = problem.toInputMap(doubleArrayOf(8.0, 8.0))
    solver.runAllIterations()

    println("Best solution:")
    println(solver.bestSolution)
    println()
    println("Solver summary:")
    solver.printResults()
}
