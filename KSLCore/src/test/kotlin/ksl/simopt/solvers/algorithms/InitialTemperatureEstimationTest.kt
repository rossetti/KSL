package ksl.simopt.solvers.algorithms

import ksl.examples.general.simopt.BuildLKModel
import ksl.examples.general.simopt.makeLKInventoryModelProblemDefinition
import ksl.simopt.evaluator.Evaluator
import ksl.simopt.evaluator.EstimatedResponse
import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.ParallelEvaluationOptions
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.InputMap
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.FixedReplicationsPerEvaluation
import ksl.simopt.solvers.ReplicationPerEvaluationIfc
import ksl.utilities.random.rng.RNStreamProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.ln

/**
 * Tests for the batched initial-temperature estimation machinery: the pure helper
 * functions in InitialTemperatureEstimator, the static
 * SimulatedAnnealing.estimateInitialTemperature, and the in-solver auto-calibration.
 *
 * The load-bearing assertions are the equivalence tests: the batched implementation must
 * produce the same estimate whether the evaluator is backed by a sequential or a parallel
 * simulation oracle, and must reproduce the historical sequential step-by-step walk.
 */
class InitialTemperatureEstimationTest {

    private companion object {
        const val SAMPLE_SIZE = 30
        const val TARGET_PROB = 0.8
        const val OBJ_FN = "TotalCost"
        const val Q_NAME = "Inventory.orderQuantity"
        const val R_NAME = "Inventory.reorderPoint"
    }

    private fun makeEvaluator(
        problemDefinition: ProblemDefinition,
        parallel: Boolean = false,
        numWorkers: Int? = null
    ): Evaluator = Evaluator.createProblemEvaluator(
        problemDefinition = problemDefinition,
        modelBuilder = BuildLKModel,
        parallelOptions = ParallelEvaluationOptions(enabled = parallel, numWorkers = numWorkers)
    )

    // ── Equivalence: sequential vs parallel oracle ────────────────────────────

    @Test
    @DisplayName("Static estimate is identical for sequential and parallel oracles")
    fun staticEstimateMatchesAcrossOracles() {
        val pdSequential = makeLKInventoryModelProblemDefinition()
        val sequentialTemp = SimulatedAnnealing.estimateInitialTemperature(
            problemDefinition = pdSequential,
            evaluator = makeEvaluator(pdSequential, parallel = false),
            targetAcceptanceProbability = TARGET_PROB,
            sampleSize = SAMPLE_SIZE
        )
        val pdParallel = makeLKInventoryModelProblemDefinition()
        val parallelTemp = SimulatedAnnealing.estimateInitialTemperature(
            problemDefinition = pdParallel,
            evaluator = makeEvaluator(pdParallel, parallel = true, numWorkers = 4),
            targetAcceptanceProbability = TARGET_PROB,
            sampleSize = SAMPLE_SIZE
        )
        assertTrue(sequentialTemp.isFinite() && sequentialTemp > 0.0) {
            "Expected a positive finite temperature, got $sequentialTemp"
        }
        assertEquals(sequentialTemp, parallelTemp, 0.0,
            "Sequential and parallel oracles must produce identical estimates")
    }

    // ── Equivalence: batched implementation vs the historical sequential walk ─

    /**
     * The pre-change implementation of estimateInitialTemperature, reproduced verbatim:
     * a RandomWalkSolver stepped manually, one single-point evaluation per step. Used as
     * the regression baseline for the batched implementation.
     */
    private fun legacyEstimate(
        problemDefinition: ProblemDefinition,
        evaluator: EvaluatorIfc,
        targetAcceptanceProbability: Double,
        sampleSize: Int,
        replicationsPerEvaluation: ReplicationPerEvaluationIfc
    ): Double {
        val randomWalk = RandomWalkSolver(
            problemDefinition = problemDefinition,
            evaluator = evaluator,
            maximumIterations = sampleSize,
            replicationsPerEvaluation = replicationsPerEvaluation,
            name = "TempEstimationRandomWalk"
        )
        randomWalk.initialize()
        var totalWorseningCost = 0.0
        var worseningMovesCount = 0
        var previousSolution = randomWalk.currentSolution
        while (randomWalk.hasNextIteration()) {
            randomWalk.runNextIteration()
            val newSolution = randomWalk.currentSolution
            val costDiff = newSolution.penalizedObjFncValue - previousSolution.penalizedObjFncValue
            if (costDiff > 0.0) {
                totalWorseningCost += costDiff
                worseningMovesCount++
            }
            previousSolution = newSolution
        }
        if (worseningMovesCount == 0) {
            return SimulatedAnnealing.defaultInitialTemperature
        }
        val averageWorseningCost = totalWorseningCost / worseningMovesCount
        return -averageWorseningCost / ln(targetAcceptanceProbability)
    }

    @Test
    @DisplayName("Batched static estimate reproduces the historical sequential walk")
    fun staticEstimateMatchesLegacyWalk() {
        val pdLegacy = makeLKInventoryModelProblemDefinition()
        val legacyTemp = legacyEstimate(
            problemDefinition = pdLegacy,
            evaluator = makeEvaluator(pdLegacy),
            targetAcceptanceProbability = TARGET_PROB,
            sampleSize = SAMPLE_SIZE,
            replicationsPerEvaluation = FixedReplicationsPerEvaluation(1)
        )
        val pdBatched = makeLKInventoryModelProblemDefinition()
        val batchedTemp = SimulatedAnnealing.estimateInitialTemperature(
            problemDefinition = pdBatched,
            evaluator = makeEvaluator(pdBatched),
            targetAcceptanceProbability = TARGET_PROB,
            sampleSize = SAMPLE_SIZE,
            replicationsPerEvaluation = FixedReplicationsPerEvaluation(1)
        )
        assertEquals(legacyTemp, batchedTemp, 1e-12,
            "Batched implementation must reproduce the legacy sequential estimate")
    }

    // ── In-solver auto-calibration determinism across worker counts ──────────

    private fun calibratedTemperature(numWorkers: Int): Double {
        val pd = makeLKInventoryModelProblemDefinition()
        val solver = SimulatedAnnealing(
            problemDefinition = pd,
            evaluator = makeEvaluator(pd, parallel = true, numWorkers = numWorkers),
            temperatureConfiguration = TemperatureConfiguration.AutoCalibrate(
                targetProbability = TARGET_PROB,
                sampleSize = 20
            ),
            replicationsPerEvaluation = 2,
            streamNum = 1
        )
        solver.initialize()
        return solver.initialTemperature
    }

    @Test
    @DisplayName("Auto-calibration is deterministic across worker counts")
    fun autoCalibrationDeterministicAcrossWorkerCounts() {
        val oneWorker = calibratedTemperature(numWorkers = 1)
        val fourWorkers = calibratedTemperature(numWorkers = 4)
        assertTrue(oneWorker.isFinite() && oneWorker > 0.0) {
            "Expected a positive finite calibrated temperature, got $oneWorker"
        }
        assertEquals(oneWorker, fourWorkers, 0.0,
            "Calibrated temperature must not depend on the worker count")
    }

    // ── Pure helper: walk-path generation ─────────────────────────────────────

    @Test
    @DisplayName("Walk path generation is deterministic and correctly shaped")
    fun walkPathDeterministicAndShaped() {
        fun makeChain(): List<InputMap> {
            val pd = makeLKInventoryModelProblemDefinition()
            val stream = RNStreamProvider().rnStream(1)
            val start = pd.startingPoint(stream)
            return InitialTemperatureEstimator.generateWalkPath(start, 25, stream) { point, s ->
                point.randomizeInputVariable(s)
            }
        }

        val first = makeChain()
        val second = makeChain()
        assertEquals(26, first.size, "Chain must contain the start plus one point per step")
        assertEquals(first, second, "Same stream state must reproduce the same walk path")
    }

    // ── Pure helper: estimate-from-chain arithmetic and hardening ─────────────

    private val pd = makeLKInventoryModelProblemDefinition()

    private fun point(q: Double, r: Double): InputMap =
        pd.toInputMap(mutableMapOf(Q_NAME to q, R_NAME to r))

    private fun solution(inputMap: InputMap, value: Double): Solution =
        Solution(inputMap, EstimatedResponse(OBJ_FN, value, 0.0, 2.0), emptyList(), 0)

    @Test
    @DisplayName("Estimate averages the worsening cost differences along the chain")
    fun estimateFromChainArithmetic() {
        val a = point(10.0, 10.0)
        val b = point(11.0, 10.0)
        val c = point(12.0, 10.0)
        val d = point(13.0, 10.0)
        val chain = listOf(a, b, c, d)
        val solutions = mapOf(
            a to solution(a, 1.0),
            b to solution(b, 3.0),
            c to solution(c, 2.0),
            d to solution(d, 5.0)
        )
        // Expected worsening diffs computed via the same penalized accessor the estimator uses.
        val values = chain.map { solutions.getValue(it).penalizedObjFncValue }
        val diffs = values.zipWithNext { p, n -> n - p }.filter { it > 0.0 }
        val expected = -(diffs.sum() / diffs.size) / ln(TARGET_PROB)

        val estimate = InitialTemperatureEstimator.estimateFromChain(chain, solutions, TARGET_PROB)
        assertEquals(expected, estimate!!, 1e-12)
    }

    @Test
    @DisplayName("A walk with no worsening moves yields null (caller applies fallback)")
    fun estimateFromChainNoWorseningMoves() {
        val a = point(10.0, 10.0)
        val b = point(11.0, 10.0)
        val c = point(12.0, 10.0)
        val chain = listOf(a, b, c)
        val solutions = mapOf(
            a to solution(a, 5.0),
            b to solution(b, 3.0),
            c to solution(c, 1.0)
        )
        assertNull(InitialTemperatureEstimator.estimateFromChain(chain, solutions, TARGET_PROB))
    }

    @Test
    @DisplayName("Non-finite and missing solutions are skipped; the chain restarts after gaps")
    fun estimateFromChainSkipsUnusablePoints() {
        val a = point(10.0, 10.0)
        val b = point(11.0, 10.0)  // bad solution: infinite penalized objective
        val c = point(12.0, 10.0)
        val d = point(13.0, 10.0)
        val e = point(14.0, 10.0)  // missing entirely
        val chain = listOf(a, b, c, d, e)
        val solutions = mapOf(
            a to solution(a, 1.0),
            b to pd.badSolution(),
            c to solution(c, 2.0),
            d to solution(d, 5.0)
        )
        // Usable segments: (a) alone before the gap, then (c, d): only diff is 5 - 2 = 3.
        val cd = solutions.getValue(d).penalizedObjFncValue - solutions.getValue(c).penalizedObjFncValue
        val expected = -cd / ln(TARGET_PROB)
        val estimate = InitialTemperatureEstimator.estimateFromChain(chain, solutions, TARGET_PROB)
        assertEquals(expected, estimate!!, 1e-12)
    }

    @Test
    @DisplayName("All-unusable chain yields null (caller applies fallback)")
    fun estimateFromChainAllUnusable() {
        val a = point(10.0, 10.0)
        val b = point(11.0, 10.0)
        val chain = listOf(a, b)
        val solutions = mapOf(
            a to pd.badSolution(),
            b to pd.badSolution()
        )
        assertNull(InitialTemperatureEstimator.estimateFromChain(chain, solutions, TARGET_PROB))
    }
}
