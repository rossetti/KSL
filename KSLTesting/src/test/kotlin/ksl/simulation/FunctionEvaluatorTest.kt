package ksl.simulation

import ksl.simopt.cache.MemorySolutionCache
import ksl.simopt.evaluator.DeterministicFunctionEvaluation
import ksl.simopt.evaluator.DeterministicFunctionEvaluator
import ksl.simopt.evaluator.DeterministicFunctionIfc
import ksl.simopt.evaluator.EvaluationRequest
import ksl.simopt.evaluator.ModelInputs
import ksl.simopt.evaluator.ObservationFunctionIfc
import ksl.simopt.evaluator.ObjectiveFunctionIfc
import ksl.simopt.evaluator.ResponseMap
import ksl.simopt.evaluator.SamplingFunctionEvaluator
import ksl.simopt.problem.InequalityType
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.algorithms.StochasticHillClimber
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class FunctionEvaluatorTest {

    @Test
    fun deterministicObjectiveWithOneRequestedReplicationHasUndefinedVariance() {
        val problem = objectiveOnlyProblem()
        val evaluator = DeterministicFunctionEvaluator.forObjective(
            problem,
            ObjectiveFunctionIfc { x -> x[0] * x[0] + x[1] * x[1] }
        )

        val request = evaluationRequest(problem, x = 2.0, y = 3.0, numReplications = 1)
        val solution = evaluator.evaluate(request).values.single()

        assertEquals(13.0, solution.estimatedObjFncValue, 1.0e-12)
        assertEquals(1.0, solution.count, 0.0)
        assertTrue(solution.variance.isNaN())
    }

    @Test
    fun deterministicObjectiveWithMultipleRequestedReplicationsHasZeroVariance() {
        val problem = objectiveOnlyProblem()
        val evaluator = DeterministicFunctionEvaluator.forObjective(
            problem,
            ObjectiveFunctionIfc { x -> x[0] + 2.0 * x[1] }
        )

        val request = evaluationRequest(problem, x = 2.0, y = 3.0, numReplications = 5)
        val solution = evaluator.evaluate(request).values.single()

        assertEquals(8.0, solution.estimatedObjFncValue, 1.0e-12)
        assertEquals(5.0, solution.count, 0.0)
        assertEquals(0.0, solution.variance, 0.0)
        assertEquals(5, evaluator.totalOracleReplications)
    }

    @Test
    fun deterministicEvaluatorCarriesNamedResponseValuesIntoSolution() {
        val problem = constrainedProblem()
        val evaluator = DeterministicFunctionEvaluator(
            problem,
            DeterministicFunctionIfc { x ->
                DeterministicFunctionEvaluation(
                    objective = x[0] + x[1],
                    responses = mapOf("FillRate" to 0.97)
                )
            }
        )

        val request = evaluationRequest(problem, x = 2.0, y = 3.0, numReplications = 4)
        val solution = evaluator.evaluate(request).values.single()
        val fillRate = solution.responseEstimatesMap.getValue("FillRate")

        assertEquals(5.0, solution.estimatedObjFncValue, 1.0e-12)
        assertEquals(0.97, fillRate.average, 1.0e-12)
        assertEquals(4.0, fillRate.count, 0.0)
        assertTrue(solution.isResponseConstraintFeasible())
    }

    @Test
    fun scalarDeterministicFactoryRejectsResponseConstrainedProblems() {
        val problem = constrainedProblem()

        assertThrows(IllegalArgumentException::class.java) {
            DeterministicFunctionEvaluator.forObjective(
                problem,
                ObjectiveFunctionIfc { x -> x[0] + x[1] }
            )
        }
    }

    @Test
    fun missingDeterministicResponseProducesInvalidSolution() {
        val problem = constrainedProblem()
        val evaluator = DeterministicFunctionEvaluator(
            problem,
            DeterministicFunctionIfc { x ->
                DeterministicFunctionEvaluation(objective = x[0] + x[1])
            }
        )

        val request = evaluationRequest(problem, x = 2.0, y = 3.0, numReplications = 2)
        val solution = evaluator.evaluate(request).values.single()

        assertFalse(solution.isValid)
    }

    @Test
    fun samplingFunctionEvaluatorRepeatsObservationsAndSummarizesResponses() {
        val problem = objectiveOnlyProblem()
        var observedInputs = emptyMap<String, Double>()
        var observedResponseNames = emptySet<String>()
        val observationCalls = AtomicInteger(0)
        val evaluator = SamplingFunctionEvaluator(
            problem,
            ObservationFunctionIfc { modelInputs ->
                observedInputs = modelInputs.inputs
                observedResponseNames = modelInputs.responseNames
                mapOf("Cost" to observationCalls.incrementAndGet().toDouble())
            }
        )

        val request = EvaluationRequest(
            problem.modelIdentifier,
            ModelInputs(
                modelIdentifier = problem.modelIdentifier,
                numReplications = 3,
                inputs = linkedMapOf("y" to 4.0, "x" to 2.0),
                responseNames = problem.allResponseNames.toSet()
            )
        )
        val solution = evaluator.evaluate(request).values.single()

        assertEquals(mapOf("y" to 4.0, "x" to 2.0), observedInputs)
        assertEquals(setOf("Cost"), observedResponseNames)
        assertEquals(3, observationCalls.get())
        assertEquals(2.0, solution.estimatedObjFncValue, 1.0e-12)
        assertEquals(1.0, solution.variance, 1.0e-12)
        assertEquals(3.0, solution.count, 0.0)
    }

    @Test
    fun samplingFunctionEvaluatorObjectiveFactoryBuildsObjectiveObservations() {
        val problem = objectiveOnlyProblem()
        val observationCalls = AtomicInteger(0)
        val evaluator = SamplingFunctionEvaluator.forObjective(
            problem,
            { modelInputs ->
                observationCalls.incrementAndGet()
                modelInputs.inputs.getValue("x") + modelInputs.inputs.getValue("y")
            }
        )

        val request = EvaluationRequest(
            problem.modelIdentifier,
            ModelInputs(
                modelIdentifier = problem.modelIdentifier,
                numReplications = 3,
                inputs = linkedMapOf("y" to 4.0, "x" to 2.0),
                responseNames = problem.allResponseNames.toSet()
            )
        )
        val solution = evaluator.evaluate(request).values.single()

        assertEquals(3, observationCalls.get())
        assertEquals(6.0, solution.estimatedObjFncValue, 1.0e-12)
        assertEquals(0.0, solution.variance, 1.0e-12)
        assertEquals(3.0, solution.count, 0.0)
    }

    @Test
    fun responseMapFactoriesBuildCompleteMapsFromObservationsAndEstimates() {
        val problem = constrainedProblem()

        val responseMap = ResponseMap.fromObservations(
            problemDefinition = problem,
            objectiveObservations = doubleArrayOf(1.0, 2.0, 3.0),
            responseObservations = mapOf("FillRate" to doubleArrayOf(0.0, 1.0, 1.0))
        )

        assertTrue(responseMap.hasAllResponses())
        assertEquals(2.0, responseMap.getValue("Cost").average, 1.0e-12)
        assertEquals(1.0, responseMap.getValue("Cost").variance, 1.0e-12)
        assertEquals(2.0 / 3.0, responseMap.getValue("FillRate").average, 1.0e-12)

        val copiedResponseMap = ResponseMap.fromEstimates(
            problemDefinition = problem,
            objectiveEstimate = responseMap.getValue("Cost"),
            responseEstimates = mapOf("FillRate" to responseMap.getValue("FillRate"))
        )

        assertTrue(copiedResponseMap.hasAllResponses())
        assertEquals(responseMap.getValue("Cost").average, copiedResponseMap.getValue("Cost").average, 1.0e-12)
        assertEquals(
            responseMap.getValue("FillRate").average,
            copiedResponseMap.getValue("FillRate").average,
            1.0e-12
        )
    }

    @Test
    fun deterministicEvaluatorUsesCacheForFullHit() {
        val problem = objectiveOnlyProblem()
        val calls = AtomicInteger(0)
        val evaluator = DeterministicFunctionEvaluator.forObjective(
            problem,
            ObjectiveFunctionIfc { x ->
                calls.incrementAndGet()
                x[0] + x[1]
            },
            MemorySolutionCache(capacity = 10)
        )

        val request = evaluationRequest(problem, x = 2.0, y = 3.0, numReplications = 3)

        evaluator.evaluate(request)
        val cachedSolution = evaluator.evaluate(request).values.single()

        assertEquals(1, calls.get())
        assertEquals(2, evaluator.totalEvaluatorCalls)
        assertEquals(3, evaluator.totalOracleReplications)
        assertEquals(3, evaluator.totalCachedReplications)
        assertEquals(3.0, cachedSolution.count, 0.0)
    }

    @Test
    fun deterministicEvaluatorMergesPartialCacheHit() {
        val problem = objectiveOnlyProblem()
        val calls = AtomicInteger(0)
        val evaluator = DeterministicFunctionEvaluator.forObjective(
            problem,
            ObjectiveFunctionIfc { x ->
                calls.incrementAndGet()
                x[0] + x[1]
            },
            MemorySolutionCache(capacity = 10)
        )

        val firstRequest = evaluationRequest(problem, x = 2.0, y = 3.0, numReplications = 2)
        val secondRequest = evaluationRequest(problem, x = 2.0, y = 3.0, numReplications = 5)

        evaluator.evaluate(firstRequest)
        val mergedSolution = evaluator.evaluate(secondRequest).values.single()

        assertEquals(2, calls.get())
        assertEquals(5, evaluator.totalOracleReplications)
        assertEquals(2, evaluator.totalCachedReplications)
        assertEquals(5.0, mergedSolution.count, 0.0)
        assertEquals(0.0, mergedSolution.variance, 0.0)
    }

    @Test
    fun stochasticHillClimberCanUseDeterministicFunctionEvaluator() {
        val problem = objectiveOnlyProblem()
        val evaluator = DeterministicFunctionEvaluator.forObjective(
            problem,
            ObjectiveFunctionIfc { x -> x[0] * x[0] + x[1] * x[1] }
        )
        val solver = StochasticHillClimber(
            problemDefinition = problem,
            evaluator = evaluator,
            maxIterations = 5,
            replicationsPerEvaluation = 1
        )
        solver.startingPoint = problem.toInputMap(doubleArrayOf(3.0, 4.0))

        solver.runAllIterations()

        assertTrue(solver.bestSolution.isValid)
        assertTrue(solver.bestSolution.estimatedObjFncValue.isFinite())
    }

    private fun objectiveOnlyProblem(): ProblemDefinition {
        val problem = ProblemDefinition(
            problemName = "FunctionProblem",
            modelIdentifier = "FunctionModel",
            objFnResponseName = "Cost",
            inputNames = listOf("x", "y")
        )
        problem.inputVariable("x", -10.0, 10.0)
        problem.inputVariable("y", -10.0, 10.0)
        return problem
    }

    private fun constrainedProblem(): ProblemDefinition {
        val problem = ProblemDefinition(
            problemName = "ConstrainedFunctionProblem",
            modelIdentifier = "ConstrainedFunctionModel",
            objFnResponseName = "Cost",
            inputNames = listOf("x", "y"),
            responseNames = listOf("FillRate")
        )
        problem.inputVariable("x", -10.0, 10.0)
        problem.inputVariable("y", -10.0, 10.0)
        problem.responseConstraint("FillRate", 0.95, InequalityType.GREATER_THAN)
        return problem
    }

    private fun evaluationRequest(
        problem: ProblemDefinition,
        x: Double,
        y: Double,
        numReplications: Int
    ): EvaluationRequest {
        val inputMap = problem.toInputMap(doubleArrayOf(x, y))
        val modelInputs = ModelInputs(
            modelIdentifier = problem.modelIdentifier,
            numReplications = numReplications,
            inputs = inputMap,
            responseNames = problem.allResponseNames.toSet()
        )
        return EvaluationRequest(problem.modelIdentifier, modelInputs)
    }
}
