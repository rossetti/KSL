package ksl.simulation

import ksl.simopt.evaluator.EstimatedResponse
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.AppreciateDepreciateSequence
import ksl.simopt.problem.DynamicPolynomialPenalty
import ksl.simopt.problem.InequalityType
import ksl.simopt.problem.ParkKimMemory
import ksl.simopt.problem.ParkKimPenalty
import ksl.simopt.problem.PenaltyFunction
import ksl.simopt.problem.PenaltyMemory
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.problem.ResponseConstraint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for the Park and Kim (2015) Penalty Function with Memory engine: the appreciation/
 * depreciation penalty sequence (Eq. 4), the standardized measure of violation (Eq. 2) and its
 * visit-mean S (Eq. 3), the penalized-objective term lambda*[S]+ (Eq. 4), the D4 graceful
 * degradation, and the ProblemDefinition fold/query integration.
 *
 * Reference problem: minimise E[TotalCost] over (x, y) in [1, 10]^2 subject to E[FillRate] >= 0.95.
 * For that greater-than constraint, difference(avg) = 0.95 - avg, so avg = 0.90 is infeasible
 * (difference = +0.05) and avg = 0.97 is feasible (difference = -0.02).
 */
class ParkKimPenaltyTest {

    private fun freshPd(): ProblemDefinition {
        val p = ProblemDefinition(
            problemName = "TestProblem",
            modelIdentifier = "TestModel",
            objFnResponseName = "TotalCost",
            inputNames = listOf("x", "y"),
            responseNames = listOf("FillRate")
        )
        p.inputVariable("x", 1.0, 10.0, 1.0)
        p.inputVariable("y", 1.0, 10.0, 1.0)
        p.responseConstraint("FillRate", 0.95, InequalityType.GREATER_THAN)
        return p
    }

    private val pd: ProblemDefinition by lazy { freshPd() }

    private fun fillRateConstraint(p: ProblemDefinition): ResponseConstraint =
        p.responseConstraints.first { it.responseName == "FillRate" }

    private fun solution(
        p: ProblemDefinition = pd,
        fillAvg: Double,
        fillCount: Double = 10.0,
        evalNum: Int = 5,
        memory: Map<String, PenaltyMemory> = emptyMap()
    ): Solution {
        val inputMap = p.toInputMap(doubleArrayOf(5.0, 5.0))
        val obj = EstimatedResponse("TotalCost", 10.0, 1.0, fillCount)
        val fill = EstimatedResponse("FillRate", fillAvg, 0.001, fillCount)
        return Solution(inputMap, obj, listOf(fill), evalNum, penaltyMemory = memory)
    }

    private fun boundParkKim(
        p: ProblemDefinition = pd,
        a: Double = 2.0,
        d: Double = 0.5,
        initialLambda: Double = 1.0
    ): PenaltyFunction =
        ParkKimPenalty(AppreciateDepreciateSequence(a, d, initialLambda)).boundTo(fillRateConstraint(p))

    // ── Group A: AppreciateDepreciateSequence (the lambda rule, Eq. 4) ─────────

    @Test
    fun appreciatesWhenMeasureIsPositive() {
        val seq = AppreciateDepreciateSequence(appreciationFactor = 2.0, depreciationFactor = 0.5, initialLambda = 1.0)
        assertEquals(2.0, seq.update(priorLambda = 1.0, standardizedMeasure = 0.15, visitCount = 1, iteration = 1), 1e-12)
    }

    @Test
    fun depreciatesWhenMeasureIsNonPositive() {
        val seq = AppreciateDepreciateSequence(2.0, 0.5, 1.0)
        assertEquals(0.5, seq.update(1.0, -0.06, 1, 1), 1e-12, "negative measure depreciates")
        assertEquals(0.5, seq.update(1.0, 0.0, 1, 1), 1e-12, "a zero measure is treated as feasible and depreciates")
    }

    @Test
    fun sequenceRejectsInvalidFactors() {
        assertThrows(IllegalArgumentException::class.java) { AppreciateDepreciateSequence(1.0, 0.5, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { AppreciateDepreciateSequence(2.0, 1.0, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { AppreciateDepreciateSequence(2.0, 0.0, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { AppreciateDepreciateSequence(2.0, 0.5, 0.0) }
    }

    // ── Group B: ParkKimMemory (the standardized visit-mean S, Eq. 3) ─────────

    @Test
    fun standardizedMeasureIsCumulativeOverVisits() {
        assertEquals(0.5, ParkKimMemory(visitCount = 4, cumulativeZeta = 2.0, lambda = 3.0).standardizedMeasure, 1e-12)
    }

    @Test
    fun standardizedMeasureIsZeroWithoutVisits() {
        assertEquals(0.0, ParkKimMemory(0, 0.0, 1.0).standardizedMeasure, 1e-12)
    }

    // ── Group C: foldVisit (the standardized measure zeta and accumulation) ───

    @Test
    fun foldVisitStandardizesAnInfeasibleBatch() {
        // zeta = sqrt(9) * (0.95 - 0.90) = 3 * 0.05 = 0.15; S = 0.15 (>0) so lambda appreciates: 1.0 * 2.0.
        val m = boundParkKim().foldVisit(EstimatedResponse("FillRate", 0.90, 0.001, 9.0), null, 1) as ParkKimMemory
        assertEquals(1, m.visitCount)
        assertEquals(0.15, m.cumulativeZeta, 1e-9)
        assertEquals(0.15, m.standardizedMeasure, 1e-9)
        assertEquals(2.0, m.lambda, 1e-9)
    }

    @Test
    fun foldVisitStandardizesAFeasibleBatchAsNegative() {
        // zeta = sqrt(9) * (0.95 - 0.97) = 3 * (-0.02) = -0.06; S <= 0 so lambda depreciates: 1.0 * 0.5.
        val m = boundParkKim().foldVisit(EstimatedResponse("FillRate", 0.97, 0.001, 9.0), null, 1) as ParkKimMemory
        assertEquals(-0.06, m.cumulativeZeta, 1e-9)
        assertEquals(0.5, m.lambda, 1e-9)
    }

    @Test
    fun foldVisitAccumulatesAcrossVisits() {
        val p = boundParkKim()
        val m1 = p.foldVisit(EstimatedResponse("FillRate", 0.90, 0.001, 9.0), null, 1) as ParkKimMemory
        // visit 2: zeta2 = sqrt(16) * 0.05 = 0.20; cumulative = 0.35 over 2 visits, S = 0.175 (>0);
        // lambda appreciates again: 2.0 * 2.0 = 4.0.
        val m2 = p.foldVisit(EstimatedResponse("FillRate", 0.90, 0.001, 16.0), m1, 2) as ParkKimMemory
        assertEquals(2, m2.visitCount)
        assertEquals(0.35, m2.cumulativeZeta, 1e-9)
        assertEquals(0.175, m2.standardizedMeasure, 1e-9)
        assertEquals(4.0, m2.lambda, 1e-9)
    }

    @Test
    fun largerNewBatchMagnifiesTheMeasure() {
        // Same infeasibility gap (0.05), more new observations => larger zeta (the deliberate sqrt(n) magnification).
        val p = boundParkKim()
        val small = p.foldVisit(EstimatedResponse("FillRate", 0.90, 0.001, 4.0), null, 1) as ParkKimMemory
        val large = p.foldVisit(EstimatedResponse("FillRate", 0.90, 0.001, 100.0), null, 1) as ParkKimMemory
        assertTrue(large.cumulativeZeta > small.cumulativeZeta,
            "more new observations at the same gap magnify the standardized measure")
    }

    // ── Group D: penalty() (the lambda*[S]+ term and D4 degradation, Eq. 4) ───

    @Test
    fun penaltyIsLambdaTimesMeasureWhenAccumulated() {
        // memory: 3 visits, S = 0.6/3 = 0.2 (>0), lambda = 4.0 => penalty = 4.0 * 0.2 = 0.8.
        val sol = solution(fillAvg = 0.90, memory = mapOf("FillRate" to ParkKimMemory(3, 0.6, 4.0)))
        assertEquals(0.8, boundParkKim().penalty(sol), 1e-9)
    }

    @Test
    fun penaltyIsZeroWhenMeasureLooksFeasible() {
        // memory: 3 visits, S = -0.3/3 = -0.1 (<=0) => [S]+ = 0 => no penalty.
        val sol = solution(fillAvg = 0.97, memory = mapOf("FillRate" to ParkKimMemory(3, -0.3, 4.0)))
        assertEquals(0.0, boundParkKim().penalty(sol), 1e-12)
    }

    @Test
    fun penaltyDegradesToFallbackWithoutMemory() {
        // No accumulated memory => the memoryless fallback (DynamicPolynomialPenalty) is used.
        val rc = fillRateConstraint(pd)
        val sol = solution(fillAvg = 0.90, evalNum = 5) // empty penaltyMemory
        val expected = DynamicPolynomialPenalty().boundTo(rc).penalty(sol) // 100 * 5^1 * 0.05^1 = 25.0
        assertEquals(25.0, expected, 1e-9)
        assertEquals(expected, boundParkKim().penalty(sol), 1e-9)
    }

    @Test
    fun penaltyDegradesToFallbackWithASingleVisit() {
        // One visit is too noisy to trust: still the fallback, not lambda*[S]+.
        val rc = fillRateConstraint(pd)
        val sol = solution(fillAvg = 0.90, evalNum = 5, memory = mapOf("FillRate" to ParkKimMemory(1, 0.15, 8.0)))
        val expected = DynamicPolynomialPenalty().boundTo(rc).penalty(sol)
        assertEquals(expected, boundParkKim().penalty(sol), 1e-9)
    }

    // ── Group E: usesMemory + boundTo ─────────────────────────────────────────

    @Test
    fun parkKimUsesMemoryButPolynomialDoesNot() {
        assertTrue(ParkKimPenalty(AppreciateDepreciateSequence(2.0, 0.5, 1.0)).usesMemory)
        assertFalse(DynamicPolynomialPenalty().usesMemory)
    }

    @Test
    fun boundToRebindsConstraintAndFallback() {
        val rc = fillRateConstraint(pd)
        val template = ParkKimPenalty(AppreciateDepreciateSequence(2.0, 0.5, 1.0))
        assertSame(null, template.constraint, "an unbound template has no constraint")
        val bound = template.boundTo(rc) as ParkKimPenalty
        assertSame(rc, bound.constraint, "boundTo binds the penalty to the constraint")
        assertSame(rc, bound.fallback.constraint, "boundTo also binds the fallback to the constraint")
    }

    // ── Group F: ProblemDefinition integration (fold + query + Z) ──────────────

    @Test
    fun hasMemoryfulPenaltyReflectsTheDefault() {
        val p = freshPd()
        assertFalse(p.hasMemoryfulPenalty(), "the polynomial default is memoryless")
        p.defaultResponsePenalty = ParkKimPenalty(AppreciateDepreciateSequence(2.0, 0.5, 1.0))
        assertTrue(p.hasMemoryfulPenalty(), "a Park-Kim default is memoryful")
    }

    @Test
    fun foldPenaltyMemoryLeavesPriorUnchangedForMemorylessDefault() {
        val p = freshPd() // polynomial (memoryless) default
        val prior = emptyMap<String, PenaltyMemory>()
        val rm = p.emptyResponseMap()
        rm.add(EstimatedResponse("TotalCost", 10.0, 1.0, 9.0))
        rm.add(EstimatedResponse("FillRate", 0.90, 0.001, 9.0))
        assertSame(prior, p.foldPenaltyMemory(rm, prior, 1), "memoryless evaluation does not fold memory")
    }

    @Test
    fun foldPenaltyMemoryUpdatesForMemoryfulDefault() {
        val p = freshPd()
        p.defaultResponsePenalty = ParkKimPenalty(AppreciateDepreciateSequence(2.0, 0.5, 1.0))
        val rm = p.emptyResponseMap()
        rm.add(EstimatedResponse("TotalCost", 10.0, 1.0, 9.0))
        rm.add(EstimatedResponse("FillRate", 0.90, 0.001, 9.0))
        val updated = p.foldPenaltyMemory(rm, emptyMap(), 1)
        val m = updated["FillRate"] as ParkKimMemory
        assertEquals(1, m.visitCount)
        assertEquals(0.15, m.cumulativeZeta, 1e-9)
    }

    @Test
    fun penaltyFncValueUsesParkKimWhenAccumulated() {
        val p = freshPd()
        p.defaultResponsePenalty = ParkKimPenalty(AppreciateDepreciateSequence(2.0, 0.5, 1.0))
        // 3 visits, S = 0.2 (>0), lambda = 4.0 => the response-constraint penalty is 4.0 * 0.2 = 0.8.
        val sol = solution(p = p, fillAvg = 0.90, memory = mapOf("FillRate" to ParkKimMemory(3, 0.6, 4.0)))
        assertEquals(0.8, p.penaltyFncValue(sol), 1e-9)
    }
}
