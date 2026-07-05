package ksl.simopt.solvers.algorithms.isc

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 *  Unit tests for [BruteForceRedundancyChecker]'s Fourier–Motzkin redundancy test.
 */
class RedundantConstraintCheckerTest {

    private val checker = BruteForceRedundancyChecker()

    @Test
    fun dominatedConstraintIsRedundant() {
        // others: x <= 5. target: x <= 10 is implied (redundant).
        val others = listOf(HalfSpace(doubleArrayOf(1.0), 5.0))
        val target = HalfSpace(doubleArrayOf(1.0), 10.0)
        assertTrue(checker.isRedundant(target, others), "x <= 10 is redundant given x <= 5")
    }

    @Test
    fun bindingConstraintIsNotRedundant() {
        // others: x <= 10. target: x <= 5 actually tightens the region (not redundant).
        val others = listOf(HalfSpace(doubleArrayOf(1.0), 10.0))
        val target = HalfSpace(doubleArrayOf(1.0), 5.0)
        assertFalse(checker.isRedundant(target, others), "x <= 5 binds and is not redundant given x <= 10")
    }

    @Test
    fun redundancyFromCombinationOfTwoConstraintsInTwoD() {
        // others: x1 <= 4 and x2 <= 4. target: x1 + x2 <= 20 is implied (4 + 4 = 8 <= 20).
        val others = listOf(
            HalfSpace(doubleArrayOf(1.0, 0.0), 4.0),
            HalfSpace(doubleArrayOf(0.0, 1.0), 4.0)
        )
        val target = HalfSpace(doubleArrayOf(1.0, 1.0), 20.0)
        assertTrue(checker.isRedundant(target, others), "x1+x2<=20 is implied by x1<=4, x2<=4")
    }

    @Test
    fun nonRedundantDiagonalConstraintInTwoD() {
        // others: x1 <= 4, x2 <= 4 (and implicit lower bounds absent). target: x1 + x2 <= 5 binds:
        // the point (4,4) satisfies others but violates the target, so it is NOT redundant.
        val others = listOf(
            HalfSpace(doubleArrayOf(1.0, 0.0), 4.0),
            HalfSpace(doubleArrayOf(0.0, 1.0), 4.0)
        )
        val target = HalfSpace(doubleArrayOf(1.0, 1.0), 5.0)
        assertFalse(checker.isRedundant(target, others), "x1+x2<=5 cuts off (4,4) and is not redundant")
    }

    @Test
    fun failsOpenWhenRowCapExceeded() {
        // A tiny maxRows forces the elimination to bail; it must conservatively report NOT redundant.
        val tinyCap = BruteForceRedundancyChecker(maxRows = 1)
        val others = listOf(
            HalfSpace(doubleArrayOf(1.0, 0.0), 4.0),
            HalfSpace(doubleArrayOf(-1.0, 0.0), 0.0),
            HalfSpace(doubleArrayOf(0.0, 1.0), 4.0),
            HalfSpace(doubleArrayOf(0.0, -1.0), 0.0)
        )
        val target = HalfSpace(doubleArrayOf(1.0, 1.0), 20.0)
        assertFalse(tinyCap.isRedundant(target, others), "exceeding maxRows must fail open (keep the constraint)")
    }

    @Test
    @Timeout(20)
    @DisplayName("A single-step pairwise product past Int range fails open without an oversized allocation")
    fun hugePairwiseProductFailsOpenWithoutOversizedAllocation() {
        // Regression for an OutOfMemoryError found by the Study-1 smoke: on dimension >= 3 problems
        // ISC's niche identifier drove the elimination to a single step whose positive x negative
        // product was enormous, and the pre-sized ArrayList(zero + positive*negative) exhausted the
        // heap — and, past Int.MAX_VALUE, overflowed to a negative "illegal capacity" — before the
        // per-row maxRows guard could fire. Here n*n exceeds Int.MAX_VALUE, so the pre-fix eager
        // allocation threw/OOMed; the projected-size guard must fail open (return NOT redundant)
        // first, and quickly.
        val n = 47_000 // n*n = 2.209e9 > Int.MAX_VALUE (2.147e9)
        val others = ArrayList<HalfSpace>(2 * n)
        for (i in 0 until n) {
            others.add(HalfSpace(doubleArrayOf(1.0, (i % 7).toDouble()), 1000.0 + i))
            others.add(HalfSpace(doubleArrayOf(-1.0, (i % 5).toDouble()), 1000.0 + i))
        }
        val target = HalfSpace(doubleArrayOf(1.0, 1.0), 20.0)
        assertFalse(
            checker.isRedundant(target, others),
            "a pairwise product exceeding the row cap must fail open without an oversized allocation"
        )
    }
}
