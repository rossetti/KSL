package ksl.simulation

import ksl.simopt.evaluator.EstimatedResponse
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.InequalityType
import ksl.simopt.problem.ProblemDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.math.sqrt
import kotlin.test.assertFalse
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
}
