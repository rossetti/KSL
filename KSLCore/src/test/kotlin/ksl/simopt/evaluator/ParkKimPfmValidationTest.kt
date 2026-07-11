package ksl.simopt.evaluator

import ksl.simopt.cache.MemorySolutionCache
import ksl.simopt.problem.AppreciateDepreciateSequence
import ksl.simopt.problem.DynamicPolynomialPenalty
import ksl.simopt.problem.InequalityType
import ksl.simopt.problem.ParkKimMemory
import ksl.simopt.problem.ParkKimPenalty
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.problem.ResponseConstraint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

/**
 * Phase 3 validation: exercises the Park and Kim (2015) PFM engine end-to-end through the REAL
 * evaluator machinery (deterministic oracle -> cache -> solution merging), not by calling foldVisit
 * directly. It shows PFM engages under a re-sampling schedule (memory accumulates across visits, the
 * penalty of a persistently-infeasible boundary point grows with the accumulated evidence, a feasible
 * point's penalty stays zero) and degrades gracefully to the memoryless fallback when the regime does
 * not re-sample (fixed replications, or no cache).
 *
 * A deterministic (noiseless) response function makes every quantity exactly assertable. FillRate is
 * 0.90 + 0.01*x, so x = 3 gives 0.93 (infeasible against FillRate >= 0.95, gap 0.02) and x = 7 gives
 * 0.97 (feasible). Each re-sampling step adds a 10-observation batch, so every visit's standardized
 * measure is zeta = sqrt(10) * 0.02, and with appreciation factor a = 2 the penalty of the infeasible
 * point doubles each visit once memory has accumulated.
 */
class ParkKimPfmValidationTest {

    private val modelId = "pfmValidationModel"
    private val objName = "TotalCost"
    private val fillName = "FillRate"
    private val zetaPerVisit = sqrt(10.0) * 0.02 // standardized measure of one 10-observation batch at gap 0.02

    private fun oracle(): ResponseFunctionOracle =
        ResponseFunctionOracle(
            modelId, setOf(objName, fillName),
            ResponseFunctionBuilderIfc { _ ->
                // Deterministic: acquires no streams, pure function of the inputs.
                ResponseFunctionIfc { inputs ->
                    val x = inputs.getValue("x")
                    mapOf(objName to 5.0, fillName to 0.90 + 0.01 * x)
                }
            }
        )

    private fun problem(memoryful: Boolean): ProblemDefinition {
        val pd = ProblemDefinition(
            problemName = "pfmValidation",
            modelIdentifier = modelId,
            objFnResponseName = objName,
            inputNames = listOf("x", "y"),
            responseNames = listOf(fillName)
        )
        pd.inputVariable("x", 1.0, 10.0, 1.0)
        pd.inputVariable("y", 1.0, 10.0, 1.0)
        pd.responseConstraint(fillName, 0.95, InequalityType.GREATER_THAN)
        if (memoryful) {
            pd.defaultResponsePenalty = ParkKimPenalty(AppreciateDepreciateSequence(2.0, 0.5, 1.0))
        }
        return pd
    }

    private fun evaluatorWithCache(memoryful: Boolean = true): Evaluator =
        Evaluator(problem(memoryful), oracle(), MemorySolutionCache())

    private fun evaluatorWithoutCache(memoryful: Boolean = true): Evaluator =
        Evaluator(problem(memoryful), oracle())

    /** Evaluate design point x at [reps] replications through the evaluator (caching allowed). */
    private fun evaluate(evaluator: Evaluator, x: Double, reps: Int): Solution {
        val pd = evaluator.problemDefinition
        val inputs = ModelInputs(
            modelIdentifier = modelId,
            numReplications = reps,
            inputs = pd.toInputMap(mutableMapOf("x" to x, "y" to 5.0)),
            responseNames = pd.allResponseNames.toSet()
        )
        return evaluator.evaluate(EvaluationRequest(modelId, listOf(inputs))).values.single()
    }

    private fun memory(sol: Solution): ParkKimMemory = sol.penaltyMemory[fillName] as ParkKimMemory

    private fun fillConstraint(pd: ProblemDefinition): ResponseConstraint =
        pd.responseConstraints.first { it.responseName == fillName }

    @Test
    @DisplayName("PFM memory accumulates across a re-sampling schedule through the real evaluator")
    fun pfmMemoryAccumulatesAcrossReSampling() {
        val evaluator = evaluatorWithCache()
        val v1 = evaluate(evaluator, x = 3.0, reps = 10) // fresh: visit 1
        val v2 = evaluate(evaluator, x = 3.0, reps = 20) // partial hit -> merge: visit 2
        val v3 = evaluate(evaluator, x = 3.0, reps = 30) // partial hit -> merge: visit 3
        assertEquals(1, memory(v1).visitCount)
        assertEquals(2, memory(v2).visitCount)
        assertEquals(3, memory(v3).visitCount)
        // Replication counts (total observations) grow with the requests, distinct from visit counts.
        assertEquals(10.0, v1.count, 1e-9)
        assertEquals(20.0, v2.count, 1e-9)
        assertEquals(30.0, v3.count, 1e-9)
    }

    @Test
    @DisplayName("The penalty of a persistently-infeasible boundary point grows with accumulated evidence")
    fun pfmPenaltyGrowsWithAccumulatedInfeasibilityEvidence() {
        val evaluator = evaluatorWithCache()
        val rc = fillConstraint(evaluator.problemDefinition)
        val v1 = evaluate(evaluator, x = 3.0, reps = 10)
        val v2 = evaluate(evaluator, x = 3.0, reps = 20)
        val v3 = evaluate(evaluator, x = 3.0, reps = 30)
        // Visit 1: not enough memory yet, so the memoryless fallback is used.
        assertEquals(DynamicPolynomialPenalty().boundTo(rc).penalty(v1), v1.penaltyFncValue, 1e-9)
        // Visits 2 and 3: PFM engages, lambda * [S]+ with S = zetaPerVisit and lambda doubling each visit.
        assertEquals(4.0 * zetaPerVisit, v2.penaltyFncValue, 1e-6)
        assertEquals(8.0 * zetaPerVisit, v3.penaltyFncValue, 1e-6)
        assertTrue(v3.penaltyFncValue > v2.penaltyFncValue,
            "a persistently-infeasible point is penalized more as evidence accumulates")
    }

    @Test
    @DisplayName("A re-sampled feasible point carries zero penalty and a depreciating penalty sequence")
    fun pfmPenaltyStaysZeroForAReSampledFeasiblePoint() {
        val evaluator = evaluatorWithCache()
        evaluate(evaluator, x = 7.0, reps = 10)
        evaluate(evaluator, x = 7.0, reps = 20)
        val v3 = evaluate(evaluator, x = 7.0, reps = 30)
        val m = memory(v3)
        assertEquals(3, m.visitCount)
        assertTrue(m.cumulativeZeta < 0.0, "a feasible point accumulates a negative standardized measure")
        assertTrue(m.lambda < 1.0, "the penalty sequence depreciates for a feasible point (was 0.5^3 = 0.125)")
        assertEquals(0.0, v3.penaltyFncValue, 1e-12, "no penalty when the measure looks feasible ([S]+ = 0)")
    }

    @Test
    @DisplayName("PFM prefers a feasible point over a re-sampled infeasible one")
    fun pfmSeparatesInfeasibleFromFeasibleUnderReSampling() {
        val evaluator = evaluatorWithCache()
        // Drive both points to three visits.
        evaluate(evaluator, x = 3.0, reps = 10); evaluate(evaluator, x = 3.0, reps = 20)
        val infeasible = evaluate(evaluator, x = 3.0, reps = 30)
        evaluate(evaluator, x = 7.0, reps = 10); evaluate(evaluator, x = 7.0, reps = 20)
        val feasible = evaluate(evaluator, x = 7.0, reps = 30)
        // Same objective (5.0); the accumulated PFM penalty makes the infeasible point strictly worse.
        assertEquals(5.0, feasible.penalizedObjFncValue, 1e-9)
        assertTrue(infeasible.penalizedObjFncValue > feasible.penalizedObjFncValue,
            "the infeasible point's penalized objective exceeds the feasible point's")
    }

    @Test
    @DisplayName("Fixed replications (no re-sampling) degrade PFM to the memoryless fallback")
    fun pfmDegradesToFallbackUnderFixedReps() {
        val evaluator = evaluatorWithCache()
        val rc = fillConstraint(evaluator.problemDefinition)
        evaluate(evaluator, x = 3.0, reps = 10)          // visit 1
        val again = evaluate(evaluator, x = 3.0, reps = 10) // full cache hit: no new observations, no visit
        assertEquals(1, memory(again).visitCount, "a full cache hit is not a visit")
        assertEquals(DynamicPolynomialPenalty().boundTo(rc).penalty(again), again.penaltyFncValue, 1e-9,
            "with a single visit the penalty is the memoryless fallback, not lambda * [S]+")
    }

    @Test
    @DisplayName("Without a cache PFM cannot accumulate memory and stays at the fallback")
    fun pfmDegradesToFallbackWithoutCache() {
        // No cache: every evaluation is a fresh simulation, so memory never exceeds one visit.
        val evaluator = evaluatorWithoutCache()
        val rc = fillConstraint(evaluator.problemDefinition)
        val a = evaluate(evaluator, x = 3.0, reps = 10)
        val b = evaluate(evaluator, x = 3.0, reps = 20)
        assertEquals(1, memory(a).visitCount)
        assertEquals(1, memory(b).visitCount, "no cache means no re-sampling and no accumulation")
        assertEquals(DynamicPolynomialPenalty().boundTo(rc).penalty(b), b.penaltyFncValue, 1e-9)
    }
}
