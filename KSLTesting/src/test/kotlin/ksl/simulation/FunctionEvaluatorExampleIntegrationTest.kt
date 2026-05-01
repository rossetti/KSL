package ksl.simulation

import ksl.simopt.evaluator.DeterministicFunctionEvaluator
import ksl.simopt.evaluator.InputEquality
import ksl.simopt.evaluator.ModelInputs
import ksl.simopt.evaluator.ObjectiveFunctionIfc
import ksl.simopt.evaluator.ObservationFunctionIfc
import ksl.simopt.evaluator.SamplingFunctionEvaluator
import ksl.simopt.problem.InequalityType
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.algorithms.SimulatedAnnealing
import ksl.simopt.solvers.algorithms.StochasticHillClimber
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.NormalRV
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.exp

class FunctionEvaluatorExampleIntegrationTest {

    @Test
    fun deterministicQuadraticExampleRunsWithInputBasedStoppingCheck() {
        val problem = deterministicQuadraticProblem()
        val evaluator = DeterministicFunctionEvaluator.forObjective(problem, QuadraticObjective)

        val solver = StochasticHillClimber(
            problemDefinition = problem,
            evaluator = evaluator,
            maxIterations = 100,
            replicationsPerEvaluation = 1,
            streamNum = 1,
            name = "TestQuadraticFunctionHillClimber"
        )
        solver.solutionChecker.equalityChecker = InputEquality
        solver.startingPoint = problem.toInputMap(doubleArrayOf(8.0, 8.0))

        solver.runAllIterations()

        val best = solver.bestSolution
        assertTrue(best.isValid)
        assertTrue(best.isInputFeasible())
        assertTrue(best.estimatedObjFncValue.isFinite())
        assertTrue(best.estimatedObjFncValue <= QuadraticObjective.evaluate(doubleArrayOf(8.0, 8.0)))
        assertEquals(1.0, best.count, 0.0)
        assertTrue(best.variance.isNaN())
    }

    @Test
    fun twoVariableMonteCarloExampleRunsWithSimulatedAnnealing() {
        val problem = twoVariableMonteCarloProblem()
        val evaluator = SamplingFunctionEvaluator(
            problemDefinition = problem,
            observationFunction = TestTwoVariableMonteCarloObservation()
        )

        val solver = SimulatedAnnealing(
            problemDefinition = problem,
            evaluator = evaluator,
            maxIterations = 10,
            replicationsPerEvaluation = 30,
            streamNum = 3,
            name = "TestMonteCarloFunctionSimulatedAnnealing"
        )
        solver.startingPoint = problem.toInputMap(doubleArrayOf(4.0, 4.0))

        solver.runAllIterations()

        val best = solver.bestSolution
        val serviceLevel = best.responseEstimatesMap.getValue("ServiceLevel")

        assertTrue(best.isValid)
        assertTrue(best.isInputFeasible())
        assertTrue(best.estimatedObjFncValue.isFinite())
        assertEquals(30.0, best.count, 0.0)
        assertEquals(30.0, serviceLevel.count, 0.0)
        assertTrue(serviceLevel.average in 0.0..1.0)
        assertTrue(evaluator.totalOracleReplications >= 30)
    }

    private object QuadraticObjective : ObjectiveFunctionIfc {
        override fun evaluate(x: DoubleArray): Double {
            val dx = x[0] - 1.0
            val dy = x[1] + 2.0
            return dx * dx + dy * dy
        }
    }

    private class TestTwoVariableMonteCarloObservation(
        private val costNoise: NormalRV = NormalRV(mean = 0.0, variance = 1.0, streamNum = 11),
        private val serviceStream: RNStreamIfc = KSLRandom.rnStream(12)
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
            return 1.0 / (1.0 + exp(-(-1.5 + 0.6 * x + 0.4 * y)))
        }
    }

    private fun deterministicQuadraticProblem(): ProblemDefinition {
        return ProblemDefinition(
            problemName = "Deterministic Quadratic Function",
            modelIdentifier = "QuadraticFunction",
            objFnResponseName = "Objective",
            inputNames = listOf("x", "y")
        ).apply {
            inputVariable("x", -10.0, 10.0)
            inputVariable("y", -10.0, 10.0)
        }
    }

    private fun twoVariableMonteCarloProblem(): ProblemDefinition {
        return ProblemDefinition(
            problemName = "Two Variable Monte Carlo Function",
            modelIdentifier = "MonteCarloFunction",
            objFnResponseName = "Cost",
            inputNames = listOf("x", "y"),
            responseNames = listOf("ServiceLevel")
        ).apply {
            inputVariable("x", -2.0, 6.0)
            inputVariable("y", -2.0, 6.0)
            responseConstraint("ServiceLevel", 0.80, InequalityType.GREATER_THAN)
        }
    }
}
