/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.utilities.distributions.fitting.mixture

import ksl.utilities.distributions.ContinuousDistributionIfc
import ksl.utilities.distributions.Normal
import ksl.utilities.distributions.Uniform
import ksl.utilities.random.rvariable.RVParametersTypeIfc
import ksl.utilities.random.rvariable.RVType
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 *  The two halves of `RecoveryMeasures` that `RecoveryQuadratureTest` does not reach.
 *
 *  That test is about the quadrature underneath the density distances, and it exercises `compare`,
 *  `quadratureGrid` and `componentHellinger`. It never calls `familiesEquivalent` or
 *  `matchComponents` directly, so the equivalence table and the assignment search were carried
 *  into a published library with no test of their own. Both decide what "recovered" means, and a
 *  wrong answer from either changes every family-accuracy figure the package reports without
 *  changing anything that looks broken.
 */
class RecoveryMatchingTest {

    // -- The equivalence table -------------------------------------------------

    /**
     *  A family always recovers itself, whatever it is. The cheapest property, and the one that
     *  would break first if the table were consulted before the equality check.
     */
    @Test
    @DisplayName("Every family is equivalent to itself")
    fun everyFamilyRecoversItself() {
        for (type in listOf(
            RVType.Normal, RVType.Exponential, RVType.Gamma, RVType.Weibull, RVType.Uniform,
            RVType.Triangular, RVType.GeneralizedBeta, RVType.Lognormal, RVType.PearsonType5
        )) {
            assertTrue(RecoveryMeasures.familiesEquivalent(type, type)) { "$type does not recover itself" }
        }
    }

    /**
     *  The pairs the table exists for: families that coincide at particular parameter values, so
     *  no amount of data separates them. An exponential is a gamma of shape one and a Weibull of
     *  shape one; a uniform is a beta with both shapes one.
     */
    @Test
    @DisplayName("Families that coincide at some parameters recover one another")
    fun coincidingFamiliesAreEquivalent() {
        val equivalent = listOf(
            RVType.Exponential to RVType.Gamma,
            RVType.Exponential to RVType.Weibull,
            RVType.Gamma to RVType.Weibull,
            RVType.Uniform to RVType.GeneralizedBeta,
            RVType.Triangular to RVType.GeneralizedBeta
        )
        for ((a, b) in equivalent) {
            assertTrue(RecoveryMeasures.familiesEquivalent(a, b)) { "$a and $b should be equivalent" }
            assertTrue(RecoveryMeasures.familiesEquivalent(b, a)) { "equivalence must be symmetric: $b, $a" }
        }
    }

    /**
     *  **The table is a list of classes, not a union-find, and that distinction is load-bearing.**
     *
     *  `Uniform` and `Triangular` each share a class with `GeneralizedBeta`, but not with each
     *  other, and they must not become equivalent by transitivity — a uniform and a triangular are
     *  perfectly distinguishable from data. Anyone "simplifying" the table by merging overlapping
     *  sets would break exactly this, silently, and every family-accuracy number would rise.
     */
    @Test
    @DisplayName("Equivalence does not propagate through a shared family")
    fun equivalenceIsNotTransitive() {
        assertTrue(RecoveryMeasures.familiesEquivalent(RVType.Uniform, RVType.GeneralizedBeta))
        assertTrue(RecoveryMeasures.familiesEquivalent(RVType.Triangular, RVType.GeneralizedBeta))
        assertTrue(!RecoveryMeasures.familiesEquivalent(RVType.Uniform, RVType.Triangular)) {
            "uniform and triangular share only GeneralizedBeta; they are distinguishable from data " +
                "and must not be counted as recovering one another"
        }
    }

    /** Families with nothing in common are not equivalent, or the measure reports success always. */
    @Test
    @DisplayName("Unrelated families are not equivalent")
    fun unrelatedFamiliesAreNotEquivalent() {
        val unrelated = listOf(
            RVType.Normal to RVType.Gamma,
            RVType.Normal to RVType.Uniform,
            RVType.Lognormal to RVType.Weibull,
            RVType.PearsonType5 to RVType.Exponential
        )
        for ((a, b) in unrelated) {
            assertTrue(!RecoveryMeasures.familiesEquivalent(a, b)) { "$a and $b should not be equivalent" }
        }
    }

    /**
     *  The name-based overload must answer exactly as the type-based one does, for every pair.
     *
     *  Its whole reason to exist is that a caller reading family names back from a database should
     *  not restate the equivalence classes and drift from what the comparison actually scores. That
     *  promise is only kept if the two agree, and nothing but this test checks it — the string
     *  overload has no other caller in the library.
     */
    @Test
    @DisplayName("The name-based overload agrees with the type-based one on every pair")
    fun theTwoOverloadsAgree() {
        val types: List<RVParametersTypeIfc> = listOf(
            RVType.Normal, RVType.Exponential, RVType.Gamma, RVType.Weibull, RVType.Uniform,
            RVType.Triangular, RVType.GeneralizedBeta, RVType.Lognormal, RVType.PearsonType5
        )
        val disagreements = mutableListOf<String>()
        for (a in types) {
            for (b in types) {
                val byType = RecoveryMeasures.familiesEquivalent(a, b)
                val byName = RecoveryMeasures.familiesEquivalent(a.toString(), b.toString())
                if (byType != byName) disagreements += "$a vs $b: by type $byType, by name $byName"
            }
        }
        assertTrue(disagreements.isEmpty()) {
            "the two overloads must not drift apart:\n  " + disagreements.joinToString("\n  ")
        }
    }

    // -- The assignment search -------------------------------------------------

    private val far: List<ContinuousDistributionIfc> =
        listOf(Normal(5.0, 1.0), Normal(50.0, 1.0), Normal(500.0, 1.0))

    /** A permutation, so every fitted component is used exactly once. */
    private fun assertIsPermutation(assignment: IntArray, k: Int) {
        assertEquals(k, assignment.size)
        assertEquals(k, assignment.toSortedSet().size) { "not a permutation: ${assignment.toList()}" }
        assertTrue(assignment.all { it in 0 until k }) { "index out of range: ${assignment.toList()}" }
    }

    @Test
    @DisplayName("Matching a set of components against itself is the identity")
    fun matchingAgainstItselfIsTheIdentity() {
        val assignment = RecoveryMeasures.matchComponents(far, far)
        assertIsPermutation(assignment, far.size)
        assertArrayEquals(intArrayOf(0, 1, 2), assignment)
    }

    @Test
    @DisplayName("Matching recovers a permutation of the same components")
    fun matchingRecoversAPermutation() {
        val shuffled = listOf(far[2], far[0], far[1])
        // true[0] is fitted[1], true[1] is fitted[2], true[2] is fitted[0].
        assertArrayEquals(intArrayOf(1, 2, 0), RecoveryMeasures.matchComponents(far, shuffled))
    }

    /**
     *  **Matching is on density, not on the mean, and this is the case that tells them apart.**
     *
     *  A uniform on 0 to 20 and a normal with mean 10 have the same mean and nothing else in
     *  common. Matching on means could pair either with either — the cost would be zero both ways
     *  — while matching on densities has exactly one right answer. The KDoc claims this property
     *  because a mean is a poor summary of a skewed component; without a test the claim is a
     *  comment.
     */
    @Test
    @DisplayName("Components with identical means are still matched by their densities")
    fun matchingUsesDensitiesRatherThanMeans() {
        val truth: List<ContinuousDistributionIfc> = listOf(Uniform(0.0, 20.0), Normal(10.0, 0.25))
        assertEquals(truth[0].mean(), truth[1].mean(), 1.0e-12) {
            "this test is pointless unless the two components really do share a mean"
        }
        val fitted: List<ContinuousDistributionIfc> = listOf(Normal(10.0, 0.25), Uniform(0.0, 20.0))
        assertArrayEquals(intArrayOf(1, 0), RecoveryMeasures.matchComponents(truth, fitted))
    }

    /**
     *  Beyond `exactMatchingLimit` the search falls back to a greedy assignment. Exercised by
     *  lowering the limit rather than by building a nine-component problem, so the fallback is
     *  tested at a size where the right answer is still obvious.
     */
    @Test
    @DisplayName("The greedy fallback still returns a valid permutation")
    fun theGreedyFallbackIsStillAPermutation() {
        val original = RecoveryMeasures.exactMatchingLimit
        try {
            RecoveryMeasures.exactMatchingLimit = 1
            val shuffled = listOf(far[2], far[0], far[1])
            val assignment = RecoveryMeasures.matchComponents(far, shuffled)
            assertIsPermutation(assignment, far.size)
            // Well-separated components leave the greedy choice no room to go wrong.
            assertArrayEquals(intArrayOf(1, 2, 0), assignment)
        } finally {
            RecoveryMeasures.exactMatchingLimit = original
        }
    }

    /** Unequal component counts have no matching, and saying so beats returning a wrong one. */
    @Test
    @DisplayName("Matching refuses lists of different lengths")
    fun matchingRefusesUnequalLengths() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            RecoveryMeasures.matchComponents(far, far.take(2))
        }
        assertTrue(error.message!!.contains("equal number")) { "unexpected message: ${error.message}" }
    }
}
