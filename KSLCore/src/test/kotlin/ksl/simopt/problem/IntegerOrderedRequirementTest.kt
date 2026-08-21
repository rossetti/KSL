package ksl.simopt.problem

import ksl.simopt.evaluator.Evaluator
import ksl.simopt.evaluator.ResponseFunctionBuilderIfc
import ksl.simopt.evaluator.ResponseFunctionIfc
import ksl.simopt.evaluator.ResponseFunctionOracle
import ksl.simopt.solvers.algorithms.RSplineSolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * R-SPLINE, COMPASS and ISC all refuse a problem whose inputs are not unit-spaced, and they are
 * right to: each moves one unit along a coordinate at a time, in the variable's own units, so a
 * coarser grid would put every step between feasible values.
 *
 * What they used to say about it was "requires that the problem definition be integer ordered",
 * which on a seventeen-input problem leaves the reader to find which input is at fault and why.
 * It is also easy to read as the looser property from the literature, where integer-VALUED is
 * enough -- a variable ranging over 30, 35, ... 100 is integer-ordered in that sense and is
 * rejected here.
 */
class IntegerOrderedRequirementTest {

    private companion object {
        const val MODEL_ID = "granularityProbe"
        const val OBJ = "objFn"
    }

    /**
     * A two-input problem: one unit-spaced, one at the supplied granularity.
     */
    private fun makeProblem(secondGranularity: Double): ProblemDefinition {
        val pd = ProblemDefinition(
            problemName = "granularityProbe",
            modelIdentifier = MODEL_ID,
            objFnResponseName = OBJ,
            inputNames = listOf("unitSpaced", "coarse")
        )
        pd.inputVariable("unitSpaced", 0.0, 25.0, granularity = 1.0)
        pd.inputVariable("coarse", 30.0, 100.0, granularity = secondGranularity)
        return pd
    }

    @Test
    @DisplayName("A coarser grid of integers is not integer-ordered, and the offender is named")
    fun coarseGridIsReportedWithItsGranularity() {
        val pd = makeProblem(secondGranularity = 5.0)
        assertFalse(pd.isIntegerOrdered)

        val offenders = pd.nonIntegerOrderedInputs
        assertEquals(1, offenders.size)
        assertEquals("coarse", offenders.first().name)

        val message = pd.integerOrderedRequirementMessage("R-SPLINE")
        assertTrue(message.contains("R-SPLINE")) { message }
        assertTrue(message.contains("coarse")) { "the message did not name the input: $message" }
        assertTrue(message.contains("5.0")) { "the message did not give the granularity: $message" }
        // and it should not accuse the input that is fine
        assertFalse(message.contains("'unitSpaced'")) { "the message blamed a valid input: $message" }
    }

    @Test
    @DisplayName("A continuous input is reported the same way")
    fun continuousInputIsReported() {
        val pd = makeProblem(secondGranularity = 0.0)
        assertFalse(pd.isIntegerOrdered)
        val message = pd.integerOrderedRequirementMessage("COMPASS")
        assertTrue(message.contains("coarse")) { message }
        assertTrue(message.contains("0.0")) { message }
    }

    @Test
    @DisplayName("A unit-spaced problem is integer-ordered and has nothing to report")
    fun unitSpacedProblemPasses() {
        val pd = makeProblem(secondGranularity = 1.0)
        assertTrue(pd.isIntegerOrdered)
        assertTrue(pd.nonIntegerOrderedInputs.isEmpty())
    }

    @Test
    @DisplayName("R-SPLINE's refusal carries the diagnosis rather than only the rule")
    fun rSplineRefusalNamesTheInput() {
        val pd = makeProblem(secondGranularity = 5.0)
        val oracle = ResponseFunctionOracle(
            MODEL_ID, setOf(OBJ),
            ResponseFunctionBuilderIfc { _ ->
                ResponseFunctionIfc { inputs -> mapOf(OBJ to inputs.values.sum()) }
            }
        )
        val evaluator = Evaluator(pd, oracle)

        val exception = assertThrows(IllegalArgumentException::class.java) {
            RSplineSolver(pd, evaluator)
        }
        val message = exception.message!!
        assertTrue(message.contains("coarse")) { "R-SPLINE did not name the offending input: $message" }
        assertTrue(message.contains("5.0")) { "R-SPLINE did not give the granularity: $message" }
    }
}
