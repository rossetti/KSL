package ksl.simopt.solvers.algorithms.isc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 *  Unit tests for [MostPromisingArea] geometry: halfway-hyperplane construction, membership, and
 *  active-constraint pruning.
 */
class MostPromisingAreaTest {

    @Test
    fun centerLiesInItsOwnMostPromisingArea() {
        val pd = IscTestSupport.boxProblem(dim = 2, lb = 0.0, ub = 10.0)
        val center = doubleArrayOf(5.0, 5.0)
        val mpa = MostPromisingArea(pd, center, listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(10.0, 10.0)))
        assertTrue(mpa.contains(center), "the center is always at least as close to itself as to any visited point")
    }

    @Test
    fun halfwayHyperplaneSeparatesCenterFromVisitedPoint() {
        val pd = IscTestSupport.boxProblem(dim = 1, lb = 0.0, ub = 10.0)
        val center = doubleArrayOf(2.0)
        val visited = doubleArrayOf(8.0)
        val mpa = MostPromisingArea(pd, center, listOf(visited))
        assertEquals(1, mpa.halfwayHalfSpaces.size, "one visited point yields one halfway hyperplane")
        // Midpoint between 2 and 8 is 5: points <= 5 are closer to the center.
        assertTrue(mpa.contains(doubleArrayOf(4.0)), "4 is closer to center 2 than to visited 8")
        assertTrue(mpa.contains(doubleArrayOf(5.0)), "the midpoint 5 lies on the boundary and is included")
        assertFalse(mpa.contains(doubleArrayOf(6.0)), "6 is closer to visited 8 and is excluded")
    }

    @Test
    fun visitedPointEqualToCenterContributesNoConstraint() {
        val pd = IscTestSupport.boxProblem(dim = 2)
        val center = doubleArrayOf(3.0, 3.0)
        val mpa = MostPromisingArea(pd, center, listOf(doubleArrayOf(3.0, 3.0), doubleArrayOf(9.0, 9.0)))
        assertEquals(1, mpa.halfwayHalfSpaces.size, "the visited point equal to the center is ignored")
    }

    @Test
    fun originalLinearConstraintsAreCarriedAsHalfSpaces() {
        val pd = IscTestSupport.boxProblem(dim = 2)
        pd.linearConstraint(mapOf("x1" to 1.0, "x2" to 1.0), rhsValue = 8.0, inequalityType = IscTestSupport.LE)
        val mpa = MostPromisingArea(pd, doubleArrayOf(2.0, 2.0), emptyList())
        assertEquals(1, mpa.originalHalfSpaces.size, "the single linear constraint becomes one half-space")
        assertFalse(mpa.contains(doubleArrayOf(5.0, 5.0)), "5+5=10 > 8 violates the original linear constraint")
        assertTrue(mpa.contains(doubleArrayOf(3.0, 3.0)), "3+3=6 <= 8 satisfies the original linear constraint")
    }

    @Test
    fun pruningDropsRedundantHalfwayHyperplanes() {
        // Two visited points along the same ray from the center: the farther one's halfway plane is
        // implied by the nearer one's, so it should be pruned.
        val pd = IscTestSupport.boxProblem(dim = 1, lb = 0.0, ub = 100.0)
        val center = doubleArrayOf(0.0)
        val mpa = MostPromisingArea(pd, center, listOf(doubleArrayOf(10.0), doubleArrayOf(40.0)))
        assertEquals(2, mpa.halfwayHalfSpaces.size)
        val active = mpa.activeHalfwayHalfSpaces(BruteForceRedundancyChecker())
        assertEquals(1, active.size, "the farther point's halfway plane (x <= 20) is redundant given x <= 5")
    }
}
