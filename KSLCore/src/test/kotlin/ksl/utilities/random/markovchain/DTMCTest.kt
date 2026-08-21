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

package ksl.utilities.random.markovchain

import org.junit.jupiter.api.DisplayName
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  `DTMC` computes exact properties of a transition matrix. Every expected value here is either
 *  a textbook closed form or was solved independently, so a test failing means the code is
 *  wrong rather than that the answer moved.
 *
 *  The fixtures are deliberately the ones the project already uses. `irreducible` is the chain
 *  of `MarkovChainExamples.mc1()`, whose stationary distribution that example prints as
 *  238/854, 350/854, 266/854 — solved by hand when it was written, and confirmed here against
 *  a direct solution. `periodic` is `mc2()`, which exists in the book to show a chain whose
 *  n-step matrix does not converge.
 */
class DTMCTest {

    private val tol = 1.0E-9

    /** `MarkovChainExamples.mc1()`: irreducible and aperiodic. */
    private val irreducible = DTMC(arrayOf(
        doubleArrayOf(0.3, 0.1, 0.6),
        doubleArrayOf(0.4, 0.4, 0.2),
        doubleArrayOf(0.1, 0.7, 0.2)
    ))

    /** `MarkovChainExamples.mc2()`: irreducible with period 2, states {1,3} alternating with {2,4}. */
    private val periodic = DTMC(arrayOf(
        doubleArrayOf(0.0, 0.5, 0.0, 0.5),
        doubleArrayOf(0.5, 0.0, 0.5, 0.0),
        doubleArrayOf(0.0, 0.5, 0.0, 0.5),
        doubleArrayOf(0.5, 0.0, 0.5, 0.0)
    ))

    /** State 1 absorbs; 2 and 3 communicate but can leave, so they are transient. */
    private val absorbingChain = DTMC(arrayOf(
        doubleArrayOf(1.0, 0.0, 0.0),
        doubleArrayOf(0.5, 0.0, 0.5),
        doubleArrayOf(0.0, 0.5, 0.5)
    ))

    /** A fair random walk on 1..5 with both ends absorbing. Every answer is a closed form. */
    private val gamblersRuin = DTMC(arrayOf(
        doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0),
        doubleArrayOf(0.5, 0.0, 0.5, 0.0, 0.0),
        doubleArrayOf(0.0, 0.5, 0.0, 0.5, 0.0),
        doubleArrayOf(0.0, 0.0, 0.5, 0.0, 0.5),
        doubleArrayOf(0.0, 0.0, 0.0, 0.0, 1.0)
    ))

    /** Two closed classes, no absorbing state: reducible, and no unique stationary distribution. */
    private val twoClosedClasses = DTMC(arrayOf(
        doubleArrayOf(0.5, 0.5, 0.0, 0.0),
        doubleArrayOf(0.5, 0.5, 0.0, 0.0),
        doubleArrayOf(0.0, 0.0, 0.5, 0.5),
        doubleArrayOf(0.0, 0.0, 0.5, 0.5)
    ))

    /** An absorbing state, but also a recurrent class that is not absorbing. */
    private val notAnAbsorbingChain = DTMC(arrayOf(
        doubleArrayOf(1.00, 0.00, 0.00, 0.00),
        doubleArrayOf(0.00, 0.50, 0.50, 0.00),
        doubleArrayOf(0.00, 0.50, 0.50, 0.00),
        doubleArrayOf(0.25, 0.50, 0.25, 0.00)
    ))

    private fun assertClose(expected: Double, actual: Double, eps: Double = 1.0E-6, what: String = "") {
        assertTrue(abs(expected - actual) < eps, "$what expected $expected but was $actual")
    }

    // ---- construction -------------------------------------------------------------------

    @Test
    @DisplayName("A row that does not sum to one is rejected, naming the row")
    fun aRowThatDoesNotSumToOneIsRejected() {
        val e = assertFailsWith<IllegalArgumentException> {
            DTMC(arrayOf(doubleArrayOf(0.5, 0.4), doubleArrayOf(0.5, 0.5)))
        }
        assertTrue(e.message!!.contains("Row 0"), "the message should say which row: ${e.message}")
    }

    @Test
    @DisplayName("A negative probability is rejected")
    fun aNegativeProbabilityIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            DTMC(arrayOf(doubleArrayOf(1.5, -0.5), doubleArrayOf(0.5, 0.5)))
        }
    }

    // ---- structure ----------------------------------------------------------------------

    @Test
    @DisplayName("An irreducible chain has one communicating class")
    fun anIrreducibleChainHasOneCommunicatingClass() {
        assertTrue(irreducible.isIrreducible)
        assertEquals(listOf(setOf(1, 2, 3)), irreducible.communicatingClasses)
        assertTrue(periodic.isIrreducible, "periodic does not mean reducible")
    }

    @Test
    @DisplayName("A reducible chain reports its classes separately")
    fun aReducibleChainReportsItsClassesSeparately() {
        assertFalse(twoClosedClasses.isIrreducible)
        assertEquals(listOf(setOf(1, 2), setOf(3, 4)), twoClosedClasses.communicatingClasses)
    }

    @Test
    @DisplayName("Reachability is transitive and reflexive")
    fun reachabilityIsTransitiveAndReflexive() {
        assertTrue(absorbingChain.isReachable(3, 1), "3 reaches 1 by way of 2")
        assertTrue(absorbingChain.isReachable(1, 1), "a state reaches itself in zero steps")
        assertFalse(absorbingChain.isReachable(1, 2), "state 1 absorbs, so it reaches nothing else")
        assertTrue(absorbingChain.canReturnTo(1), "an absorbing state returns to itself every step")
    }

    @Test
    @DisplayName("Absorbing, recurrent and transient states are told apart")
    fun statesAreClassifiedCorrectly() {
        assertEquals(listOf(1), absorbingChain.absorbingStates)
        assertEquals(listOf(1), absorbingChain.recurrentStates)
        assertEquals(listOf(2, 3), absorbingChain.transientStates)
        assertTrue(absorbingChain.isAbsorbingChain)

        // Every state of a chain with two closed classes is recurrent, and none absorbs.
        assertEquals(emptyList(), twoClosedClasses.absorbingStates)
        assertEquals(listOf(1, 2, 3, 4), twoClosedClasses.recurrentStates)
        assertFalse(twoClosedClasses.hasAbsorbingStates)

        // An absorbing state is not enough to make an absorbing chain.
        assertEquals(listOf(1), notAnAbsorbingChain.absorbingStates)
        assertEquals(listOf(1, 2, 3), notAnAbsorbingChain.recurrentStates)
        assertFalse(notAnAbsorbingChain.isAbsorbingChain)
    }

    @Test
    @DisplayName("Period distinguishes the two book example chains")
    fun periodDistinguishesTheTwoBookChains() {
        assertEquals(1, irreducible.period(1))
        assertTrue(irreducible.isAperiodic)

        // mc2 alternates between {1,3} and {2,4}: it can only return in an even number of steps.
        for (s in 1..4) assertEquals(2, periodic.period(s), "state $s")
        assertFalse(periodic.isAperiodic)

        assertEquals(1, absorbingChain.period(1), "an absorbing state returns every step")
    }

    @Test
    @DisplayName("A state the chain cannot return to has no period")
    fun aStateTheChainCannotReturnToHasNoPeriod() {
        // State 4 has no self-loop and nothing leads back to it, so it is never revisited.
        assertFailsWith<IllegalArgumentException> { notAnAbsorbingChain.period(4) }
    }

    // ---- exact results ------------------------------------------------------------------

    @Test
    @DisplayName("The stationary distribution matches the value the book prints")
    fun theStationaryDistributionMatchesTheBook() {
        val pi = irreducible.steadyStateDistribution
        assertClose(238.0 / 854.0, pi[0], tol, "pi[1]")
        assertClose(350.0 / 854.0, pi[1], tol, "pi[2]")
        assertClose(266.0 / 854.0, pi[2], tol, "pi[3]")
        assertClose(1.0, pi.sum(), tol, "total")
    }

    @Test
    @DisplayName("A periodic chain still has a stationary distribution")
    fun aPeriodicChainStillHasAStationaryDistribution() {
        // Stationary, and the long-run fraction of visits — but not the limit of P^n.
        val pi = periodic.steadyStateDistribution
        for (k in 0..3) assertClose(0.25, pi[k], tol, "pi[${k + 1}]")
    }

    @Test
    @DisplayName("A reducible chain has no unique stationary distribution and says so")
    fun aReducibleChainRefusesToGuess() {
        val e = assertFailsWith<IllegalStateException> { twoClosedClasses.steadyStateDistribution }
        assertTrue(e.message!!.contains("irreducible"), "the message should say why: ${e.message}")
    }

    @Test
    @DisplayName("Zero steps gives the identity and one step gives the matrix back")
    fun zeroStepsGivesTheIdentity() {
        val p0 = irreducible.nStepTransitionMatrix(0)
        for (i in 0..2) for (j in 0..2) assertClose(if (i == j) 1.0 else 0.0, p0[i][j], tol)
        val p1 = irreducible.nStepTransitionMatrix(1)
        val p = irreducible.transitionMatrix
        for (i in 0..2) for (j in 0..2) assertClose(p[i][j], p1[i][j], tol)
    }

    @Test
    @DisplayName("An aperiodic chain converges to its stationary distribution, a periodic one does not")
    fun convergenceOfTheNStepMatrix() {
        val pi = irreducible.steadyStateDistribution
        val p50 = irreducible.nStepTransitionMatrix(50)
        for (i in 0..2) for (j in 0..2) assertClose(pi[j], p50[i][j], 1.0E-8, "P^50[$i][$j]")

        // mc2 has period 2: from a state it can only be back on an even step, so the diagonal
        // alternates between 0 and 1/2 forever rather than settling at 1/4.
        assertClose(0.0, periodic.nStepTransitionMatrix(51)[0][0], tol, "P^51 diagonal")
        assertClose(0.5, periodic.nStepTransitionMatrix(50)[0][0], tol, "P^50 diagonal")
    }

    @Test
    @DisplayName("Expected absorption times match the closed form for a fair random walk")
    fun expectedAbsorptionTimesMatchTheClosedForm() {
        // Starting k steps from the bottom of a walk of width N, the expected time is k(N - k).
        val t = gamblersRuin.expectedAbsorptionTimes()
        assertClose(0.0, t[0], tol, "an absorbing state is already absorbed")
        assertClose(3.0, t[1], tol, "from state 2")
        assertClose(4.0, t[2], tol, "from state 3")
        assertClose(3.0, t[3], tol, "from state 4")
        assertClose(0.0, t[4], tol, "the other absorbing state")
    }

    @Test
    @DisplayName("Absorption probabilities match the closed form for a fair random walk")
    fun absorptionProbabilitiesMatchTheClosedForm() {
        // A fair walk reaches the top from state i with probability (i - 1)/4.
        val b = gamblersRuin.absorptionProbabilities()
        assertClose(0.25, b[1][4], tol, "from state 2 to the top")
        assertClose(0.50, b[2][4], tol, "from state 3 to the top")
        assertClose(0.75, b[3][4], tol, "from state 4 to the top")
        assertClose(0.75, b[1][0], tol, "from state 2 to the bottom")
        assertClose(1.00, b[0][0], tol, "an absorbing state is absorbed in itself")
        for (i in 0..4) assertClose(1.0, b[i].sum(), tol, "row $i must be a distribution")
    }

    @Test
    @DisplayName("An absorbing state is not enough: the guard names the recurrent class")
    fun theAbsorbingResultsRefuseANonAbsorbingChain() {
        val e = assertFailsWith<IllegalStateException> { notAnAbsorbingChain.expectedAbsorptionTimes() }
        assertTrue(e.message!!.contains("not an absorbing chain"), "message was: ${e.message}")
        assertFailsWith<IllegalStateException> { twoClosedClasses.fundamentalMatrix() }
    }

    @Test
    @DisplayName("Mean recurrence time is the reciprocal of the stationary probability")
    fun meanRecurrenceTimeIsTheReciprocalOfTheStationaryProbability() {
        val m = irreducible.meanFirstPassageTimes()
        val pi = irreducible.steadyStateDistribution
        for (k in 0..2) assertClose(1.0 / pi[k], m[k][k], 1.0E-8, "recurrence time of state ${k + 1}")
        // Solved independently: the expected number of steps to first reach state 1.
        assertClose(2.9411764706, m[1][0], 1.0E-6, "from state 2 to state 1")
        assertClose(3.8235294118, m[2][0], 1.0E-6, "from state 3 to state 1")
    }

    @Test
    @DisplayName("Mean first passage times require an irreducible chain")
    fun meanFirstPassageTimesRequireAnIrreducibleChain() {
        assertFailsWith<IllegalStateException> { absorbingChain.meanFirstPassageTimes() }
    }
}
