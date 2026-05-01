package ksl.examples.general.simopt

import ksl.simopt.evaluator.ModelInputs
import ksl.simopt.evaluator.ObservationFunctionIfc
import ksl.simopt.evaluator.SamplingFunctionEvaluator
import ksl.simopt.problem.InequalityType
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.algorithms.SimulatedAnnealing
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.NormalRV
import kotlin.math.exp

fun main() {
    val problem = twoVariableMonteCarloProblem()
    val evaluator = SamplingFunctionEvaluator(
        problemDefinition = problem,
        observationFunction = TwoVariableMonteCarloObservation()
    )

    val solver = SimulatedAnnealing(
        problemDefinition = problem,
        evaluator = evaluator,
        maxIterations = 100,
        replicationsPerEvaluation = 30,
        name = "SamplingFunctionSimulatedAnnealing"
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
 * Each call produces one observation. The evaluator owns the replication loop
 * and statistical summarization.
 */
class TwoVariableMonteCarloObservation(
    private val costNoise: NormalRV = NormalRV(mean = 0.0, variance = 1.0, streamNum = 1),
    private val serviceStream: RNStreamIfc = KSLRandom.rnStream(2)
) : ObservationFunctionIfc {

    override fun observe(modelInputs: ModelInputs): Map<String, Double> {
        val x = modelInputs.inputs.getValue("x")
        val y = modelInputs.inputs.getValue("y")
        val cost = costFunction(x, y) + costNoise.value
        val service = KSLRandom.rBernoulli(serviceProbability(x, y), serviceStream)

        return mapOf(
            "Cost" to cost,
            "ServiceLevel" to service
        )
    }

    private fun costFunction(x: Double, y: Double): Double {
        return (x - 2.0) * (x - 2.0) + (y - 3.0) * (y - 3.0)
    }

    private fun serviceProbability(x: Double, y: Double): Double {
        return logistic(-1.5 + 0.6 * x + 0.4 * y)
    }

    private fun logistic(z: Double): Double {
        return 1.0 / (1.0 + exp(-z))
    }
}
