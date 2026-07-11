package ksl.simulation

import ksl.simopt.evaluator.EstimatedResponse
import ksl.simopt.evaluator.FeasibilityFirstComparator
import ksl.simopt.evaluator.SearchStateSnapshot
import ksl.simopt.evaluator.Solution
import ksl.simopt.evaluator.Solutions
import ksl.simopt.problem.InequalityType
import ksl.simopt.problem.PenaltyMemory
import ksl.simopt.problem.ProblemDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.math.sqrt
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for EstimatedResponse and Solution.
 * No simulation oracle is required — these tests exercise the statistical
 * estimate and solution data structures directly.
 *
 * Reference problem: minimise E[TotalCost] over (x, y) ∈ [1, 10]² (integers)
 * subject to E[FillRate] ≥ 0.95.
 */
class EvaluatorSolutionTest {

    // ── Shared fixture ────────────────────────────────────────────────────────

    private val pd: ProblemDefinition by lazy {
        val p = ProblemDefinition(
            problemName       = "TestProblem",
            modelIdentifier   = "TestModel",
            objFnResponseName = "TotalCost",
            inputNames        = listOf("x", "y"),
            responseNames     = listOf("FillRate")
        )
        p.inputVariable("x", 1.0, 10.0, 1.0)
        p.inputVariable("y", 1.0, 10.0, 1.0)
        p.responseConstraint("FillRate", 0.95, InequalityType.GREATER_THAN)
        p
    }

    private fun makeSolution(
        xVal: Double,
        yVal: Double,
        objAvg: Double,
        objVar: Double,
        objCount: Double,
        fillAvg: Double,
        fillVar: Double,
        fillCount: Double,
        evalNum: Int = 1
    ): Solution {
        val inputMap = pd.toInputMap(doubleArrayOf(xVal, yVal))
        val objFnc   = EstimatedResponse("TotalCost", objAvg,  objVar,  objCount)
        val fillRate = EstimatedResponse("FillRate",  fillAvg, fillVar, fillCount)
        return Solution(inputMap, objFnc, listOf(fillRate), evaluationNumber = evalNum)
    }

    // ── Group 1: EstimatedResponse — constructor and basic statistics ─────────

    @Test
    fun estimatedResponsePreservesNameAndAverage() {
        val er = EstimatedResponse("Cost", 10.5, 2.0, 5.0)
        assertEquals("Cost", er.name)
        assertEquals(10.5, er.average, 0.0)
    }

    @Test
    fun estimatedResponsePreservesVarianceAndCount() {
        val er = EstimatedResponse("Cost", 10.5, 2.0, 5.0)
        assertEquals(2.0, er.variance,  0.0)
        assertEquals(5.0, er.count,     0.0)
    }

    @Test
    fun standardDeviationIsSquareRootOfVariance() {
        val er = EstimatedResponse("Cost", 10.0, 4.0, 10.0)
        assertEquals(sqrt(4.0), er.standardDeviation, 1e-12)
    }

    @Test
    fun standardErrorIsStandardDeviationOverSqrtCount() {
        val er = EstimatedResponse("Cost", 10.0, 4.0, 16.0)
        assertEquals(sqrt(4.0) / sqrt(16.0), er.standardError, 1e-12)
    }

    @Test
    fun halfWidthIsPositiveForMultipleObservations() {
        val er = EstimatedResponse("Cost", 10.0, 4.0, 10.0)
        val hw = er.halfWidth(0.95)
        assertTrue(hw > 0.0, "Half-width must be positive for count > 1")
    }

    @Test
    fun confidenceIntervalContainsAverage() {
        val er = EstimatedResponse("Cost", 10.0, 4.0, 10.0)
        val ci = er.confidenceInterval(0.95)
        assertTrue(ci.contains(er.average), "95% CI must contain the sample average")
    }

    @Test
    fun countOfOneMakesHalfWidthNaN() {
        val er = EstimatedResponse("Cost", 10.0, Double.NaN, 1.0)
        assertTrue(er.halfWidth().isNaN(), "Half-width must be NaN when count == 1")
    }

    @Test
    fun constructorFromDoubleArrayComputesCorrectAverage() {
        val data = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val er = EstimatedResponse("Cost", data)
        assertEquals(3.0, er.average, 1e-12)
        assertEquals(5.0, er.count,   0.0)
    }

    @Test
    fun mergeOfTwoEstimatesProducesCorrectCombinedAverage() {
        val er1 = EstimatedResponse("Cost", 10.0, 2.0, 4.0)
        val er2 = EstimatedResponse("Cost", 20.0, 2.0, 4.0)
        val merged = er1.merge(er2)
        assertEquals(15.0, merged.average, 1e-10, "Merged average of equal-weight samples must be 15")
        assertEquals(8.0,  merged.count,   0.0,   "Merged count must be sum of both counts")
    }

    @Test
    fun constructorRequiresCountGeOne() {
        assertThrows(IllegalArgumentException::class.java) {
            EstimatedResponse("Cost", 5.0, 1.0, 0.0)
        }
    }

    @Test
    fun constructorRequiresVarianceNaNWhenCountIsOne() {
        assertThrows(IllegalArgumentException::class.java) {
            EstimatedResponse("Cost", 5.0, 1.0, 1.0)  // variance non-NaN with count=1
        }
    }

    @Test
    fun constructorRequiresAverageIsFinite() {
        assertThrows(IllegalArgumentException::class.java) {
            EstimatedResponse("Cost", Double.POSITIVE_INFINITY, Double.NaN, 1.0)
        }
    }

    // ── Group 2: Solution — basic properties ──────────────────────────────────

    @Test
    fun solutionAverageDelegatesToEstimatedObjFnc() {
        val sol = makeSolution(5.0, 5.0, objAvg = 42.0, objVar = 1.0, objCount = 5.0,
            fillAvg = 0.97, fillVar = 0.001, fillCount = 5.0)
        assertEquals(42.0, sol.average, 0.0)
    }

    @Test
    fun estimatedObjFncValueEqualsAverageForMinimization() {
        val sol = makeSolution(5.0, 5.0, objAvg = 42.0, objVar = 1.0, objCount = 5.0,
            fillAvg = 0.97, fillVar = 0.001, fillCount = 5.0)
        assertEquals(sol.average, sol.estimatedObjFncValue, 0.0,
            "For MINIMIZE, estimatedObjFncValue must equal average (objFncFactor=1.0)")
    }

    @Test
    fun responseEstimatesMapLookupByName() {
        val sol = makeSolution(5.0, 5.0, objAvg = 42.0, objVar = 1.0, objCount = 5.0,
            fillAvg = 0.97, fillVar = 0.001, fillCount = 5.0)
        val fillEst = sol.responseEstimatesMap["FillRate"]
        assertEquals(0.97, fillEst!!.average, 1e-12)
    }

    @Test
    fun responseAveragesMapContainsFillRate() {
        val sol = makeSolution(5.0, 5.0, objAvg = 42.0, objVar = 1.0, objCount = 5.0,
            fillAvg = 0.97, fillVar = 0.001, fillCount = 5.0)
        assertEquals(0.97, sol.responseAverages["FillRate"]!!, 1e-12)
    }

    @Test
    fun problemDefinitionReferencedFromSolutionMatchesPd() {
        val sol = makeSolution(5.0, 5.0, objAvg = 42.0, objVar = 1.0, objCount = 5.0,
            fillAvg = 0.97, fillVar = 0.001, fillCount = 5.0)
        assertEquals(pd, sol.problemDefinition)
    }

    @Test
    fun solutionEvaluationNumberIsPreserved() {
        val sol = makeSolution(5.0, 5.0, objAvg = 42.0, objVar = 1.0, objCount = 5.0,
            fillAvg = 0.97, fillVar = 0.001, fillCount = 5.0, evalNum = 7)
        assertEquals(7, sol.evaluationNumber)
    }

    @Test
    fun atEvaluationReStampsClockPreservingEstimatesAndId() {
        val original = makeSolution(5.0, 5.0, objAvg = 42.0, objVar = 1.0, objCount = 5.0,
            fillAvg = 0.90, fillVar = 0.001, fillCount = 5.0, evalNum = 3)
        val reStamped = original.atEvaluation(9)
        assertEquals(9, reStamped.evaluationNumber, "re-stamp advances the penalty clock")
        assertEquals(original.id, reStamped.id, "re-stamp preserves id (design-point provenance)")
        assertEquals(original.estimatedObjFncValue, reStamped.estimatedObjFncValue, 0.0,
            "re-stamp preserves the objective estimate")
        assertEquals(original.responseConstraintViolationPenalty,
            reStamped.responseConstraintViolationPenalty, 0.0,
            "re-stamp preserves the (raw, clock-independent) violation")
    }

    @Test
    fun atEvaluationReturnsSameInstanceWhenClockUnchanged() {
        val sol = makeSolution(5.0, 5.0, objAvg = 42.0, objVar = 1.0, objCount = 5.0,
            fillAvg = 0.90, fillVar = 0.001, fillCount = 5.0, evalNum = 4)
        assertTrue(sol === sol.atEvaluation(4), "a no-op re-stamp returns the same instance")
    }

    @Test
    fun atEvaluationAdvancesThePenaltyClock() {
        // Infeasible (FillRate 0.90 < 0.95): the growing multiplier makes the penalty larger at a
        // later iteration, so the re-stamped solution has a larger penalized objective.
        val early = makeSolution(5.0, 5.0, objAvg = 10.0, objVar = 1.0, objCount = 5.0,
            fillAvg = 0.90, fillVar = 0.001, fillCount = 5.0, evalNum = 2)
        val late = early.atEvaluation(20)
        assertTrue(late.penalizedObjFncValue > early.penalizedObjFncValue,
            "a later clock yields a larger penalty for an infeasible solution")
    }

    @Test
    fun solutionEqualityExcludesId() {
        val a = makeSolution(5.0, 5.0, objAvg = 10.0, objVar = 1.0, objCount = 5.0,
            fillAvg = 0.97, fillVar = 0.001, fillCount = 5.0, evalNum = 1)
        val b = a.copy(id = a.id + 1) // same data, different id
        assertTrue(a.id != b.id, "the copy carries a different id")
        assertEquals(a, b, "value-equality excludes id")
        assertEquals(a.hashCode(), b.hashCode(), "hashCode excludes id")
    }

    @Test
    fun solutionCarriesEmptyPenaltyMemoryByDefault() {
        val sol = makeSolution(5.0, 5.0, objAvg = 10.0, objVar = 1.0, objCount = 5.0,
            fillAvg = 0.97, fillVar = 0.001, fillCount = 5.0, evalNum = 1)
        assertTrue(sol.penaltyMemory.isEmpty(), "a memoryless solution carries no penalty memory")
        assertNull(sol.searchState, "search state is null until a self-scaling penalty populates it")
    }

    @Test
    fun penaltyMemoryAndSearchStateAreExcludedFromEquality() {
        // Memory is derived state, not identity: excluding it keeps the solution cache's value-equality
        // (and the archive's dedup) from splitting one design point into two just because their
        // accumulated penalty histories differ.
        val base = makeSolution(5.0, 5.0, objAvg = 10.0, objVar = 1.0, objCount = 5.0,
            fillAvg = 0.97, fillVar = 0.001, fillCount = 5.0, evalNum = 1)
        val withMemory = base.copy(
            penaltyMemory = mapOf("FillRate" to object : PenaltyMemory {}),
            searchState = SearchStateSnapshot(bestFeasibleObjective = 1.0)
        )
        assertEquals(base, withMemory, "value-equality excludes penalty memory and search state")
        assertEquals(base.hashCode(), withMemory.hashCode(),
            "hashCode excludes penalty memory and search state")
    }

    @Test
    fun atEvaluationCarriesPenaltyMemoryAndSearchStateForward() {
        // A cache re-stamp advances only the penalty clock; the accumulated memory must ride along.
        val memory = mapOf("FillRate" to object : PenaltyMemory {})
        val snapshot = SearchStateSnapshot(bestFeasibleObjective = 2.0)
        val original = makeSolution(5.0, 5.0, objAvg = 10.0, objVar = 1.0, objCount = 5.0,
            fillAvg = 0.90, fillVar = 0.001, fillCount = 5.0, evalNum = 3)
            .copy(penaltyMemory = memory, searchState = snapshot)
        val reStamped = original.atEvaluation(9)
        assertEquals(9, reStamped.evaluationNumber, "re-stamp advances the clock")
        assertTrue(reStamped.penaltyMemory === memory, "re-stamp carries the penalty memory forward")
        assertTrue(reStamped.searchState === snapshot, "re-stamp carries the search-state snapshot forward")
    }

    // ── Group 3: Response constraint feasibility ──────────────────────────────

    @Test
    fun feasibleSolutionHasZeroResponseConstraintViolationPenalty() {
        // FillRate = 0.98 >= 0.95 → constraint satisfied → no penalty
        val sol = makeSolution(5.0, 5.0, objAvg = 10.0, objVar = 1.0, objCount = 5.0,
            fillAvg = 0.98, fillVar = 0.001, fillCount = 5.0)
        assertEquals(0.0, sol.responseConstraintViolationPenalty, 0.0,
            "FillRate=0.98 satisfies >= 0.95; violation penalty must be zero")
    }

    @Test
    fun infeasibleSolutionHasPositiveResponseConstraintViolationPenalty() {
        // FillRate = 0.90 < 0.95 → constraint violated → non-zero violation
        val sol = makeSolution(5.0, 5.0, objAvg = 10.0, objVar = 1.0, objCount = 5.0,
            fillAvg = 0.90, fillVar = 0.001, fillCount = 5.0)
        assertTrue(sol.responseConstraintViolationPenalty > 0.0,
            "FillRate=0.90 violates >= 0.95; violation penalty must be positive")
    }

    @Test
    fun penalizedObjFncValueEqualsCostForFeasibleSolution() {
        // Feasible solution: FillRate >= 0.95, no constraints on linear/functional
        val sol = makeSolution(5.0, 5.0, objAvg = 15.0, objVar = 1.0, objCount = 5.0,
            fillAvg = 0.98, fillVar = 0.001, fillCount = 5.0)
        assertEquals(sol.estimatedObjFncValue, sol.penalizedObjFncValue, 1e-12,
            "For feasible solution, penalizedObjFncValue must equal estimatedObjFncValue")
    }

    @Test
    fun penalizedObjFncValueExceedsCostForInfeasibleSolution() {
        // Infeasible solution: FillRate < 0.95 → penalty added
        val sol = makeSolution(5.0, 5.0, objAvg = 5.0, objVar = 1.0, objCount = 5.0,
            fillAvg = 0.80, fillVar = 0.001, fillCount = 5.0)
        assertTrue(sol.penalizedObjFncValue > sol.estimatedObjFncValue,
            "For infeasible solution, penalizedObjFncValue must exceed estimatedObjFncValue")
    }

    // ── Group 4: Solution ordering ────────────────────────────────────────────

    @Test
    fun solutionWithLowerObjectiveComparesLessThanHigherObjective() {
        // Both feasible; lower cost is "better" for MINIMIZE
        val solLow  = makeSolution(3.0, 3.0, objAvg =  5.0, objVar = 1.0, objCount = 5.0,
            fillAvg = 0.98, fillVar = 0.001, fillCount = 5.0)
        val solHigh = makeSolution(7.0, 7.0, objAvg = 20.0, objVar = 1.0, objCount = 5.0,
            fillAvg = 0.98, fillVar = 0.001, fillCount = 5.0)
        assertTrue(solLow.compareTo(solHigh) < 0,
            "Solution with lower cost must compare less than solution with higher cost")
    }

    @Test
    fun solutionWithSamePenalizedObjectiveComparesToZero() {
        val solA = makeSolution(3.0, 3.0, objAvg = 10.0, objVar = 1.0, objCount = 5.0,
            fillAvg = 0.98, fillVar = 0.001, fillCount = 5.0)
        val solB = makeSolution(4.0, 2.0, objAvg = 10.0, objVar = 1.0, objCount = 5.0,
            fillAvg = 0.98, fillVar = 0.001, fillCount = 5.0)
        assertEquals(0, solA.compareTo(solB),
            "Solutions with equal penalized objective must compare to zero")
    }

    @Test
    fun feasibleSolutionComparesLessThanInfeasibleWithSameObjectiveAverage() {
        // feasible: fill=0.98 ≥ 0.95 → penalty=0, total = 10.0
        val solFeasible = makeSolution(5.0, 5.0, objAvg = 10.0, objVar = 1.0, objCount = 5.0,
            fillAvg = 0.98, fillVar = 0.001, fillCount = 5.0)
        // infeasible: fill=0.80 < 0.95 → penalty > 0, total > 10.0
        val solInfeasible = makeSolution(5.0, 5.0, objAvg = 10.0, objVar = 1.0, objCount = 5.0,
            fillAvg = 0.80, fillVar = 0.001, fillCount = 5.0)
        assertTrue(solFeasible.compareTo(solInfeasible) < 0,
            "Feasible solution must rank better (less) than infeasible at same raw cost")
    }

    // ── Group 5: Feasibility-first selection (recommendation) ──────────────────

    @Test
    fun feasibilityFirstRanksFeasibleAheadOfCheaperInfeasible() {
        val feasible = makeSolution(4.0, 3.0, objAvg = 5.0, objVar = 0.0001, objCount = 50.0,
            fillAvg = 0.97, fillVar = 0.0001, fillCount = 50.0)
        val infeasibleCheaper = makeSolution(7.0, 1.0, objAvg = 3.0, objVar = 0.0001, objCount = 50.0,
            fillAvg = 0.86, fillVar = 0.0001, fillCount = 50.0)
        assertTrue(FeasibilityFirstComparator().compare(feasible, infeasibleCheaper) < 0,
            "a confidently-feasible solution ranks ahead of a cheaper infeasible one")
    }

    @Test
    fun feasibilityFirstRanksFeasiblesByRawObjective() {
        val cheaper = makeSolution(4.0, 3.0, objAvg = 4.6, objVar = 0.0001, objCount = 50.0,
            fillAvg = 0.96, fillVar = 0.0001, fillCount = 50.0)
        val dearer = makeSolution(6.0, 3.0, objAvg = 5.3, objVar = 0.0001, objCount = 50.0,
            fillAvg = 0.98, fillVar = 0.0001, fillCount = 50.0)
        assertTrue(FeasibilityFirstComparator().compare(cheaper, dearer) < 0,
            "among feasibles, the smaller raw objective ranks first")
    }

    @Test
    fun feasibilityFirstRanksInfeasiblesByViolation() {
        val lessInfeasible = makeSolution(6.0, 2.0, objAvg = 4.0, objVar = 0.0001, objCount = 50.0,
            fillAvg = 0.93, fillVar = 0.0001, fillCount = 50.0)
        val moreInfeasible = makeSolution(4.0, 1.0, objAvg = 2.7, objVar = 0.0001, objCount = 50.0,
            fillAvg = 0.76, fillVar = 0.0001, fillCount = 50.0)
        assertTrue(FeasibilityFirstComparator().compare(lessInfeasible, moreInfeasible) < 0,
            "among infeasibles, the smaller violation ranks first")
    }

    @Test
    fun feasibilityFirstPrefersFeasibleWhenPenalizedFavorsInfeasible() {
        // A cheap, barely-infeasible, early-clock point has a SMALLER penalized objective than a
        // feasible one, so penalized ranking prefers it. FF prefers the confidently-feasible one.
        val feasible = makeSolution(4.0, 3.0, objAvg = 5.0, objVar = 0.0001, objCount = 50.0,
            fillAvg = 0.97, fillVar = 0.0001, fillCount = 50.0, evalNum = 1)
        val infeasibleCheap = makeSolution(7.0, 1.0, objAvg = 3.0, objVar = 0.0001, objCount = 50.0,
            fillAvg = 0.945, fillVar = 0.0001, fillCount = 50.0, evalNum = 1)
        assertTrue(infeasibleCheap.penalizedObjFncValue < feasible.penalizedObjFncValue,
            "penalized ranking prefers the cheap barely-infeasible point")
        assertTrue(FeasibilityFirstComparator().compare(feasible, infeasibleCheap) < 0,
            "FF prefers the confidently-feasible solution")
    }

    @Test
    fun orderedResponseFeasibleSolutionsReturnsTheFeasibleOnes() {
        val solutions = Solutions()
        val feasible = makeSolution(4.0, 3.0, objAvg = 5.0, objVar = 0.0001, objCount = 50.0,
            fillAvg = 0.97, fillVar = 0.0001, fillCount = 50.0)
        val infeasible = makeSolution(7.0, 1.0, objAvg = 3.0, objVar = 0.0001, objCount = 50.0,
            fillAvg = 0.86, fillVar = 0.0001, fillCount = 50.0)
        solutions.add(feasible)
        solutions.add(infeasible)
        val feas = solutions.orderedResponseFeasibleSolutions()
        assertEquals(1, feas.size, "only the confidently response-feasible solution is returned")
        assertEquals(feasible.inputMap, feas.first().inputMap)
    }

    @Test
    fun evictionRetainsFeasibleOverCheaperInfeasible() {
        val solutions = Solutions(capacity = 1)
        val feasible = makeSolution(4.0, 3.0, objAvg = 5.0, objVar = 0.0001, objCount = 50.0,
            fillAvg = 0.97, fillVar = 0.0001, fillCount = 50.0)
        val infeasibleCheap = makeSolution(7.0, 1.0, objAvg = 3.0, objVar = 0.0001, objCount = 50.0,
            fillAvg = 0.945, fillVar = 0.0001, fillCount = 50.0)
        solutions.add(feasible)
        solutions.add(infeasibleCheap) // cheaper on penalized, but infeasible -> must not evict the feasible
        assertEquals(1, solutions.size)
        assertEquals(feasible.inputMap, solutions.orderedSolutions.first().inputMap,
            "the feasible solution is retained over a cheaper infeasible one")
    }
}
