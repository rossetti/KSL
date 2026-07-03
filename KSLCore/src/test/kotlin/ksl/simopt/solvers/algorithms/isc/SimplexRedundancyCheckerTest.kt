package ksl.simopt.solvers.algorithms.isc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 *  Unit tests for the LP-backed [SimplexRedundancyChecker], including parity with the
 *  Fourier–Motzkin [BruteForceRedundancyChecker] on shared cases and the unbounded/infeasible edges.
 */
class SimplexRedundancyCheckerTest {

    private val checker = SimplexRedundancyChecker()
    private val bruteForce = BruteForceRedundancyChecker()

    @Test
    fun dominatedConstraintIsRedundant() {
        val others = listOf(HalfSpace(doubleArrayOf(1.0), 5.0))
        val target = HalfSpace(doubleArrayOf(1.0), 10.0)
        assertTrue(checker.isRedundant(target, others), "x <= 10 is redundant given x <= 5")
    }

    @Test
    fun bindingConstraintIsNotRedundant() {
        val others = listOf(HalfSpace(doubleArrayOf(1.0), 10.0))
        val target = HalfSpace(doubleArrayOf(1.0), 5.0)
        assertFalse(checker.isRedundant(target, others), "x <= 5 binds given x <= 10")
    }

    @Test
    fun redundancyFromCombinationOfTwoConstraintsInTwoD() {
        val others = listOf(
            HalfSpace(doubleArrayOf(1.0, 0.0), 4.0),
            HalfSpace(doubleArrayOf(0.0, 1.0), 4.0)
        )
        val target = HalfSpace(doubleArrayOf(1.0, 1.0), 20.0)
        assertTrue(checker.isRedundant(target, others), "x1+x2<=20 is implied by x1<=4, x2<=4")
    }

    @Test
    fun nonRedundantDiagonalConstraintInTwoD() {
        val others = listOf(
            HalfSpace(doubleArrayOf(1.0, 0.0), 4.0),
            HalfSpace(doubleArrayOf(0.0, 1.0), 4.0)
        )
        val target = HalfSpace(doubleArrayOf(1.0, 1.0), 5.0)
        assertFalse(checker.isRedundant(target, others), "x1+x2<=5 cuts off (4,4)")
    }

    @Test
    fun unboundedRegionMakesTheTargetNotRedundant() {
        // No constraints: a . x is unbounded above, so the target can be violated arbitrarily.
        val target = HalfSpace(doubleArrayOf(1.0), 10.0)
        assertFalse(checker.isRedundant(target, emptyList()), "an unbounded region cannot make x <= 10 redundant")
    }

    @Test
    fun infeasibleOthersMakeTheTargetVacuouslyRedundant() {
        // x <= 1 and x >= 5 is an empty region; every (non-existent) feasible point satisfies anything.
        val others = listOf(
            HalfSpace(doubleArrayOf(1.0), 1.0),
            HalfSpace(doubleArrayOf(-1.0), -5.0)
        )
        val target = HalfSpace(doubleArrayOf(1.0), 10.0)
        assertTrue(checker.isRedundant(target, others), "an empty feasible region makes any target vacuously redundant")
    }

    @Test
    fun agreesWithBruteForceAcrossSharedCases() {
        val cases: List<Pair<HalfSpace, List<HalfSpace>>> = listOf(
            HalfSpace(doubleArrayOf(1.0), 10.0) to listOf(HalfSpace(doubleArrayOf(1.0), 5.0)),
            HalfSpace(doubleArrayOf(1.0), 5.0) to listOf(HalfSpace(doubleArrayOf(1.0), 10.0)),
            HalfSpace(doubleArrayOf(1.0, 1.0), 20.0) to listOf(
                HalfSpace(doubleArrayOf(1.0, 0.0), 4.0), HalfSpace(doubleArrayOf(0.0, 1.0), 4.0)
            ),
            HalfSpace(doubleArrayOf(1.0, 1.0), 5.0) to listOf(
                HalfSpace(doubleArrayOf(1.0, 0.0), 4.0), HalfSpace(doubleArrayOf(0.0, 1.0), 4.0)
            ),
            HalfSpace(doubleArrayOf(2.0, 1.0), 30.0) to listOf(
                HalfSpace(doubleArrayOf(1.0, 0.0), 6.0),
                HalfSpace(doubleArrayOf(0.0, 1.0), 6.0),
                HalfSpace(doubleArrayOf(-1.0, 0.0), 0.0),
                HalfSpace(doubleArrayOf(0.0, -1.0), 0.0)
            )
        )
        for ((target, others) in cases) {
            assertEquals(
                bruteForce.isRedundant(target, others),
                checker.isRedundant(target, others),
                "the LP and Fourier-Motzkin checkers must agree on $target given $others"
            )
        }
    }
}
