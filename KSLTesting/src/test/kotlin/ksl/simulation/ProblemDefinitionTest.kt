package ksl.simulation

import ksl.simopt.problem.InequalityType
import ksl.simopt.problem.InputDefinition
import ksl.simopt.problem.OptimizationType
import ksl.simopt.problem.ProblemDefinition
import ksl.utilities.Interval
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for ProblemDefinition, InputDefinition, and InputMap.
 * No simulation is required — these tests exercise the problem-definition
 * algebra independently of any simulation oracle.
 *
 * Reference problem: minimise E[TotalCost] over (x, y) ∈ [1, 10]² (integers)
 * subject to E[FillRate] ≥ 0.95.
 */
class ProblemDefinitionTest {

    // ── Shared fixture ────────────────────────────────────────────────────────

    private fun makeProblem(): ProblemDefinition {
        val pd = ProblemDefinition(
            problemName     = "TestProblem",
            modelIdentifier = "TestModel",
            objFnResponseName = "TotalCost",
            inputNames      = listOf("x", "y"),
            responseNames   = listOf("FillRate")
        )
        pd.inputVariable("x", 1.0, 10.0, 1.0)
        pd.inputVariable("y", 1.0, 10.0, 1.0)
        pd.responseConstraint("FillRate", 0.95, InequalityType.GREATER_THAN)
        return pd
    }

    // ── Group 1: ProblemDefinition structural properties ──────────────────────

    @Test
    fun modelIdentifierIsPreserved() {
        assertEquals("TestModel", makeProblem().modelIdentifier)
    }

    @Test
    fun optimizationTypeDefaultsToMinimize() {
        assertEquals(OptimizationType.MINIMIZE, makeProblem().optimizationType)
    }

    @Test
    fun objFncFactorIsOneForMinimize() {
        assertEquals(1.0, makeProblem().objFncFactor, 0.0)
    }

    @Test
    fun objFncFactorIsNegativeOneForMaximize() {
        val pd = ProblemDefinition(
            modelIdentifier   = "M",
            objFnResponseName = "Profit",
            inputNames        = listOf("q"),
            optimizationType  = OptimizationType.MAXIMIZE
        )
        pd.inputVariable("q", 1.0, 100.0, 1.0)
        assertEquals(-1.0, pd.objFncFactor, 0.0)
    }

    @Test
    fun inputNamesListMatchesSpecified() {
        val pd = makeProblem()
        assertTrue("x" in pd.inputNames)
        assertTrue("y" in pd.inputNames)
    }

    @Test
    fun responseNamesListMatchesSpecified() {
        assertTrue("FillRate" in makeProblem().responseNames)
    }

    @Test
    fun allResponseNamesStartsWithObjectiveFunction() {
        val pd = makeProblem()
        val all = pd.allResponseNames
        assertEquals("TotalCost", all.first(), "allResponseNames must lead with the objective function")
        assertTrue("FillRate" in all)
    }

    @Test
    fun inputSizeEqualsNumberOfDefinedVariables() {
        assertEquals(2, makeProblem().inputSize)
    }

    @Test
    fun isIntegerOrderedTrueWhenAllGranularityIsOne() {
        assertTrue(makeProblem().isIntegerOrdered)
    }

    @Test
    fun isIntegerOrderedFalseWhenAnyGranularityIsContinuous() {
        val pd = ProblemDefinition(
            modelIdentifier   = "M",
            objFnResponseName = "Cost",
            inputNames        = listOf("a", "b")
        )
        pd.inputVariable("a", 0.0, 1.0, 0.0)  // continuous
        pd.inputVariable("b", 0.0, 1.0, 1.0)  // integer
        assertFalse(pd.isIntegerOrdered)
    }

    // ── Group 2: InputDefinition properties ───────────────────────────────────

    @Test
    fun inputDefinitionBoundsAreCorrect() {
        val id = InputDefinition("x", 2.0, 8.0, 1.0)
        assertEquals(2.0, id.lowerBound, 0.0)
        assertEquals(8.0, id.upperBound, 0.0)
    }

    @Test
    fun inputDefinitionIsIntegerOrderedWhenGranularityIsOne() {
        assertTrue(InputDefinition("x", 0.0, 10.0, 1.0).isIntegerOrdered)
    }

    @Test
    fun inputDefinitionIsNotIntegerOrderedWhenGranularityIsZero() {
        assertFalse(InputDefinition("x", 0.0, 10.0, 0.0).isIntegerOrdered)
    }

    @Test
    fun inputDefinitionMidPointIsCorrect() {
        val id = InputDefinition("x", 2.0, 8.0)
        val (name, value) = id.midPoint
        assertEquals("x", name)
        assertEquals(5.0, value, 0.0)
    }

    @Test
    fun inputDefinitionGranularPointsEnumeratesAllIntegerLevels() {
        val id = InputDefinition("x", 1.0, 5.0, 1.0)
        val points = id.granularPoints()
        assertEquals(5, points.size, "Integer variable over [1,5] must have 5 granular points")
        assertTrue(1.0 in points)
        assertTrue(5.0 in points)
    }

    // ── Group 3: Feasibility checks ───────────────────────────────────────────

    @Test
    fun isInputFeasibleReturnsTrueForInRangeValues() {
        val pd = makeProblem()
        assertTrue(pd.isInputFeasible(mapOf("x" to 5.0, "y" to 5.0)))
    }

    @Test
    fun isInputFeasibleReturnsFalseWhenVariableExceedsUpperBound() {
        val pd = makeProblem()
        assertFalse(pd.isInputFeasible(mapOf("x" to 11.0, "y" to 5.0)))
    }

    @Test
    fun isInputFeasibleReturnsFalseWhenVariableBelowLowerBound() {
        val pd = makeProblem()
        assertFalse(pd.isInputFeasible(mapOf("x" to 0.0, "y" to 5.0)))
    }

    // ── Group 4: toInputMap and midPoints ─────────────────────────────────────

    @Test
    fun toInputMapFromArrayPreservesInRangeValues() {
        val pd = makeProblem()
        val im = pd.toInputMap(doubleArrayOf(3.0, 7.0))
        assertEquals(3.0, im["x"]!!, 0.0)
        assertEquals(7.0, im["y"]!!, 0.0)
    }

    @Test
    fun toInputMapClampsValuesBelowLowerBoundToLowerBound() {
        val pd = makeProblem()
        val im = pd.toInputMap(doubleArrayOf(0.0, 5.0))  // x=0 < lowerBound=1
        assertEquals(1.0, im["x"]!!, 0.0, "Value below lower bound must clamp to lower bound")
    }

    @Test
    fun toInputMapClampsValuesAboveUpperBoundToUpperBound() {
        val pd = makeProblem()
        val im = pd.toInputMap(doubleArrayOf(5.0, 15.0))  // y=15 > upperBound=10
        assertEquals(10.0, im["y"]!!, 0.0, "Value above upper bound must clamp to upper bound")
    }

    @Test
    fun toInputMapFromArrayIsFeasible() {
        val pd = makeProblem()
        val im = pd.toInputMap(doubleArrayOf(3.0, 7.0))
        assertTrue(im.isInputFeasible(), "InputMap from valid array must be input-feasible")
    }

    @Test
    fun midPointsInputMapIsFeasible() {
        val pd = makeProblem()
        val im = pd.midPoints()
        assertTrue(im.isInputFeasible(), "Midpoints InputMap must be input-feasible")
    }

    @Test
    fun toInputMapFromMutableMapPreservesValues() {
        val pd = makeProblem()
        val map = mutableMapOf("x" to 4.0, "y" to 6.0)
        val im = pd.toInputMap(map)
        assertEquals(4.0, im["x"]!!, 0.0)
        assertEquals(6.0, im["y"]!!, 0.0)
    }

    // ── Group 5: Response constraints ────────────────────────────────────────

    @Test
    fun hasResponseConstraintsTrueAfterAddingOne() {
        assertTrue(makeProblem().hasResponseConstraints)
    }

    @Test
    fun responseConstraintsSizeIsOne() {
        assertEquals(1, makeProblem().responseConstraints.size)
    }

    @Test
    fun responseConstraintRHSIsCorrect() {
        val rhs = makeProblem().responseConstraintsRHS()
        assertEquals(1, rhs.size)
        assertEquals(0.95, rhs[0], 0.0)
    }

    // ── Group 6: Linear constraints ───────────────────────────────────────────

    @Test
    fun hasLinearConstraintsTrueAfterAdding() {
        val pd = makeProblem()
        pd.linearConstraint(mapOf("x" to 1.0, "y" to 1.0), 15.0, InequalityType.LESS_THAN)
        assertTrue(pd.hasLinearConstraints)
        assertEquals(1, pd.linearConstraints.size)
    }

    @Test
    fun linearConstraintViolationIsZeroForFeasibleInput() {
        val pd = makeProblem()
        pd.linearConstraint(mapOf("x" to 1.0, "y" to 1.0), 15.0, InequalityType.LESS_THAN)
        val violation = pd.totalLinearConstrainViolations(mapOf("x" to 5.0, "y" to 5.0))
        assertEquals(0.0, violation, 0.0, "x+y=10 ≤ 15 must have zero violation")
    }

    @Test
    fun linearConstraintViolationIsPositiveForInfeasibleInput() {
        val pd = makeProblem()
        pd.linearConstraint(mapOf("x" to 1.0, "y" to 1.0), 15.0, InequalityType.LESS_THAN)
        val violation = pd.totalLinearConstrainViolations(mapOf("x" to 8.0, "y" to 9.0))
        assertTrue(violation > 0.0, "x+y=17 > 15 must have positive violation")
    }

    @Test
    fun isInputFeasibleFalseWhenLinearConstraintViolated() {
        val pd = makeProblem()
        pd.linearConstraint(mapOf("x" to 1.0, "y" to 1.0), 15.0, InequalityType.LESS_THAN)
        assertFalse(pd.isInputFeasible(mapOf("x" to 8.0, "y" to 9.0)),
            "x+y=17 > 15 must be infeasible")
    }

    // ── Group 7: Validation ───────────────────────────────────────────────────

    @Test
    fun constructorThrowsWhenObjectiveFunctionResponseNameIsBlank() {
        assertThrows(IllegalArgumentException::class.java) {
            ProblemDefinition(
                modelIdentifier   = "M",
                objFnResponseName = "   ",
                inputNames        = listOf("x")
            )
        }
    }

    @Test
    fun constructorThrowsWhenInputNamesIsEmpty() {
        assertThrows(IllegalArgumentException::class.java) {
            ProblemDefinition(
                modelIdentifier   = "M",
                objFnResponseName = "Cost",
                inputNames        = emptyList()
            )
        }
    }

    @Test
    fun constructorThrowsWhenResponseNamesContainsObjectiveFunctionName() {
        assertThrows(IllegalArgumentException::class.java) {
            ProblemDefinition(
                modelIdentifier   = "M",
                objFnResponseName = "Cost",
                inputNames        = listOf("x"),
                responseNames     = listOf("Cost")   // same as objFnResponseName
            )
        }
    }

    // ── Group 8: inputVariable with Interval overload ─────────────────────────

    @Test
    fun inputVariableFromIntervalAddsDefinition() {
        val pd = ProblemDefinition(
            modelIdentifier   = "M",
            objFnResponseName = "Cost",
            inputNames        = listOf("q")
        )
        pd.inputVariable("q", Interval(1.0, 50.0), granularity = 1.0)
        assertEquals(1, pd.inputSize)
        assertNotNull(pd.inputDefinitions["q"])
        assertTrue(pd.isIntegerOrdered)
    }
}
