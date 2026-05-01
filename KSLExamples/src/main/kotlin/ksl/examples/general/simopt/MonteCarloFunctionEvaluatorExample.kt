package ksl.examples.general.simopt

import ksl.simopt.evaluator.MonteCarloContextFunctionIfc
import ksl.simopt.evaluator.MonteCarloEvaluationContext
import ksl.simopt.evaluator.MonteCarloFunctionEvaluator
import ksl.simopt.evaluator.ResponseMap
import ksl.simopt.problem.InequalityType
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.algorithms.SimulatedAnnealing
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.NormalRV
import kotlin.math.exp

fun main() {
    val problem = twoVariableMonteCarloProblem()
    val evaluator = MonteCarloFunctionEvaluator(
        problemDefinition = problem,
        function = TwoVariableMonteCarloFunction()
    )

    val solver = SimulatedAnnealing(
        problemDefinition = problem,
        evaluator = evaluator,
        maxIterations = 100,
        replicationsPerEvaluation = 30,
        name = "MonteCarloFunctionSimulatedAnnealing"
    )

    solver.startingPoint = problem.toInputMap(doubleArrayOf(4.0, 4.0))
    solver.runAllIterations()

    println("Best solution:")
    println(solver.bestSolution)
    println()
    println("Solver summary:")
    solver.printResults()
}

fun twoVariableMonteCarloProblem(): ProblemDefinition {
    val problem = ProblemDefinition(
        problemName = "Two Variable Monte Carlo Function",
        modelIdentifier = "MonteCarloFunction",
        objFnResponseName = "Cost",
        inputNames = listOf("x", "y"),
        responseNames = listOf("ServiceLevel")
    )

    problem.inputVariable("x", -2.0, 6.0)
    problem.inputVariable("y", -2.0, 6.0)

    problem.responseConstraint(
        name = "ServiceLevel",
        rhsValue = 0.80,
        inequalityType = InequalityType.GREATER_THAN
    )

    return problem
}

/**
 * A small two-variable Monte Carlo response surface.
 *
 * The cost response is a noisy quadratic centered near (2, 3). The service-level
 * response is a Bernoulli observation whose success probability increases with
 * both decision variables, creating a simple cost/service tradeoff.
 */
class TwoVariableMonteCarloFunction(
    private val costNoise: NormalRV = NormalRV(mean = 0.0, variance = 1.0, streamNum = 1),
    private val serviceStream: RNStreamIfc = KSLRandom.rnStream(2)
) : MonteCarloContextFunctionIfc {

    override fun evaluate(context: MonteCarloEvaluationContext): ResponseMap {
        val costObservations = DoubleArray(context.numReplications)
        val serviceObservations = DoubleArray(context.numReplications)

        val x = context.x
        val deterministicCost = costFunction(x)

        /*
         * This probability creates a tradeoff:
         * low x/y values tend to be cheaper but may violate the service-level constraint.
         */
        val serviceProbability = serviceProbability(x)

        for (i in costObservations.indices) {
            costObservations[i] = deterministicCost + costNoise.value
            serviceObservations[i] = KSLRandom.rBernoulli(serviceProbability, serviceStream)
        }

        return context.responseMapFromObservations(
            objectiveObservations = costObservations,
            responseObservations = mapOf("ServiceLevel" to serviceObservations)
        )
    }

    private fun costFunction(x: DoubleArray): Double {
        return (x[0] - 2.0) * (x[0] - 2.0) + (x[1] - 3.0) * (x[1] - 3.0)
    }

    private fun serviceProbability(x: DoubleArray): Double {
        return logistic(-1.5 + 0.6 * x[0] + 0.4 * x[1])
    }

    private fun logistic(z: Double): Double {
        return 1.0 / (1.0 + exp(-z))
    }
}
