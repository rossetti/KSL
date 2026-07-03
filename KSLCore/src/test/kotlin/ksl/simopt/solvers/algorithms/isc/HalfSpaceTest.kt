package ksl.simopt.solvers.algorithms.isc

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 *  Unit tests for [HalfSpace] arithmetic and the [HalfSpace.fromLinearConstraint] normalization.
 */
class HalfSpaceTest {

    @Test
    fun lhsAndSatisfactionAreCorrect() {
        val h = HalfSpace(doubleArrayOf(1.0, 2.0), 10.0)
        assertEquals(1.0 * 3.0 + 2.0 * 2.0, h.lhs(doubleArrayOf(3.0, 2.0)), 1e-12)
        assertTrue(h.isSatisfied(doubleArrayOf(2.0, 2.0)), "6 <= 10 must hold")
        assertFalse(h.isSatisfied(doubleArrayOf(5.0, 5.0)), "15 <= 10 must fail")
    }

    @Test
    fun boundaryPointSatisfiesWithinTolerance() {
        val h = HalfSpace(doubleArrayOf(1.0, 0.0), 4.0)
        assertTrue(h.isSatisfied(doubleArrayOf(4.0, 99.0)), "a point exactly on the boundary is satisfied")
    }

    @Test
    fun lessThanConstraintMapsDirectly() {
        val pd = IscTestSupport.boxProblem(dim = 2)
        // x1 + x2 <= 5
        val lc = pd.linearConstraint(mapOf("x1" to 1.0, "x2" to 1.0), rhsValue = 5.0, inequalityType = IscTestSupport.LE)
        val h = HalfSpace.fromLinearConstraint(lc, pd.inputNames)
        assertArrayEquals(doubleArrayOf(1.0, 1.0), h.a, 1e-12)
        assertEquals(5.0, h.b, 1e-12)
    }

    @Test
    fun greaterThanConstraintIsNegatedToLessThanOrEqual() {
        val pd = IscTestSupport.boxProblem(dim = 2)
        // x1 + 2 x2 >= 3   ==>   -x1 - 2 x2 <= -3
        val lc = pd.linearConstraint(mapOf("x1" to 1.0, "x2" to 2.0), rhsValue = 3.0, inequalityType = IscTestSupport.GE)
        val h = HalfSpace.fromLinearConstraint(lc, pd.inputNames)
        assertArrayEquals(doubleArrayOf(-1.0, -2.0), h.a, 1e-12)
        assertEquals(-3.0, h.b, 1e-12)
        // A point satisfying x1 + 2 x2 >= 3 must satisfy the normalized half-space.
        assertTrue(h.isSatisfied(doubleArrayOf(3.0, 1.0)), "3 + 2 = 5 >= 3 holds, so -5 <= -3 holds")
        assertFalse(h.isSatisfied(doubleArrayOf(0.0, 0.0)), "0 >= 3 fails, so 0 <= -3 fails")
    }
}
