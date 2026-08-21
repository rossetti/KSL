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

import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.statistic.IntegerFrequency
import ksl.utilities.statistic.Statistic
import org.junit.jupiter.api.DisplayName
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 *  `DMarkovChain` had no tests. It is used by the book's Chapter 9 examples and by the
 *  Metropolis-Hastings sampler, and it validates its own transition matrix at construction, so
 *  the untested surface included every guard a caller relies on for a clear failure.
 *
 *  Three properties are worth pinning down beyond construction. Generation must be reproducible
 *  from a stream, because every use of the class in teaching depends on being able to repeat a
 *  run. Sampling a first passage time must leave the chain's own configuration alone, since the
 *  chain is a long-lived object and a sampling call is not a reconfiguration. And the transition
 *  limit must be visibly a sentinel rather than a count, because the value it returns is large
 *  enough to destroy any average it reaches.
 *
 *  Every chain here uses a private stream provider, so the values do not depend on how much of a
 *  shared stream the rest of the suite has already consumed.
 */
class DMarkovChainTest {

    /** Alternates deterministically: 1, 2, 1, 2, ... regardless of the stream. */
    private val alternating = arrayOf(
        doubleArrayOf(0.0, 1.0),
        doubleArrayOf(1.0, 0.0)
    )

    /** State 2 absorbs; from state 1 the number of steps to reach it is geometric with p = 0.25. */
    private val absorbing = arrayOf(
        doubleArrayOf(0.75, 0.25),
        doubleArrayOf(0.00, 1.00)
    )

    /**
     *  The chain of `MarkovChainExamples.mc1()`. Its stationary distribution is
     *  238/854, 350/854, 266/854 — the values that example prints as the truth.
     */
    private val irreducible = arrayOf(
        doubleArrayOf(0.3, 0.1, 0.6),
        doubleArrayOf(0.4, 0.4, 0.2),
        doubleArrayOf(0.1, 0.7, 0.2)
    )

    private fun chain(matrix: Array<DoubleArray>, initialState: Int = 1) =
        DMarkovChain(initialState, matrix, streamNumber = 1, streamProvider = RNStreamProvider())

    // ---- construction -------------------------------------------------------------------

    @Test
    @DisplayName("A non-square transition matrix is rejected")
    fun aNonSquareMatrixIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            chain(arrayOf(doubleArrayOf(0.5, 0.5), doubleArrayOf(0.2, 0.3, 0.5)))
        }
    }

    @Test
    @DisplayName("A row that does not sum to one is rejected")
    fun aRowThatDoesNotSumToOneIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            chain(arrayOf(doubleArrayOf(0.5, 0.4), doubleArrayOf(0.5, 0.5)))
        }
    }

    @Test
    @DisplayName("An initial state outside the state space is rejected")
    fun anInvalidInitialStateIsRejected() {
        assertFailsWith<IllegalArgumentException> { chain(alternating, initialState = 3) }
        assertFailsWith<IllegalArgumentException> { chain(alternating, initialState = 0) }
    }

    @Test
    @DisplayName("States are numbered from one")
    fun statesAreNumberedFromOne() {
        assertContentEquals(intArrayOf(1, 2, 3), chain(irreducible).states)
    }

    @Test
    @DisplayName("The transition matrix is copied, not shared with the caller")
    fun theTransitionMatrixIsCopiedNotShared() {
        // Array<DoubleArray>.copyOf() from the standard library would be a shallow copy, which
        // would let either of these mutations reach the chain's own rows.
        val supplied = arrayOf(doubleArrayOf(0.75, 0.25), doubleArrayOf(0.0, 1.0))
        val mc = chain(supplied)

        mc.transitionMatrix[0][0] = 99.0
        supplied[0][1] = 99.0

        assertEquals(0.75, mc.transitionMatrix[0][0])
        assertEquals(0.25, mc.transitionMatrix[0][1])
    }

    // ---- generation ---------------------------------------------------------------------

    @Test
    @DisplayName("A deterministic chain follows its only path")
    fun aDeterministicChainFollowsItsOnlyPath() {
        val mc = chain(alternating)
        assertContentEquals(intArrayOf(2, 1, 2, 1, 2), IntArray(5) { mc.nextState() })
    }

    @Test
    @DisplayName("Resetting returns the chain to its initial state")
    fun resettingReturnsTheChainToItsInitialState() {
        val mc = chain(alternating)
        mc.nextState()
        assertEquals(2, mc.state)
        mc.reset()
        assertEquals(1, mc.state)
    }

    @Test
    @DisplayName("Rewinding the stream reproduces the same sequence of states")
    fun rewindingTheStreamReproducesTheSameSequence() {
        val mc = chain(irreducible)
        val first = IntArray(50) { mc.nextState() }
        mc.resetStartStream()
        mc.reset()
        val second = IntArray(50) { mc.nextState() }
        assertContentEquals(first, second)
    }

    // ---- first passage ------------------------------------------------------------------

    @Test
    @DisplayName("Sampling a first passage time leaves the initial state alone")
    fun samplingAFirstPassageTimeLeavesTheInitialStateAlone() {
        // countTransitionsUntil has to move the chain's starting point to take its observation.
        // Before this was fixed it left it moved, so a later reset() went somewhere the caller
        // never asked for.
        val mc = chain(absorbing, initialState = 2)
        assertEquals(2, mc.initialState)

        mc.countTransitionsUntil(startState = 1, desiredState = 2)

        assertEquals(2, mc.initialState)
        mc.reset()
        assertEquals(2, mc.state)
    }

    @Test
    @DisplayName("An unreachable desired state is rejected before it is simulated")
    fun anInvalidDesiredStateIsRejected() {
        val mc = chain(absorbing)
        assertFailsWith<IllegalArgumentException> {
            mc.countTransitionsUntil(startState = 1, desiredState = 5)
        }
    }

    @Test
    @DisplayName("Reaching the transition limit returns the sentinel, not a count")
    fun reachingTheTransitionLimitReturnsTheSentinel() {
        // State 2 absorbs, so state 1 is unreachable from it. The call must terminate on the
        // limit rather than run forever, and the value it returns must be recognisable.
        val mc = chain(absorbing)
        val steps = mc.countTransitionsUntil(startState = 2, desiredState = 1, transitionLimit = 50)
        assertEquals(Int.MAX_VALUE, steps)
    }

    @Test
    @DisplayName("First passage times to an absorbing state have the geometric mean")
    fun firstPassageTimesHaveTheGeometricMean() {
        // From state 1 the chain leaves for the absorbing state with probability 0.25 on each
        // transition, so the number of transitions is geometric with mean 1/0.25 = 4.
        val mc = chain(absorbing)
        val s = Statistic("first passage")
        repeat(5000) { s.collect(mc.countTransitionsUntil(1, 2).toDouble()) }
        assertTrue(abs(s.average - 4.0) < 0.25, "expected about 4.0, got ${s.average}")
    }

    @Test
    @DisplayName("The first passage frequency collects the sample size it was asked for")
    fun theFirstPassageFrequencyCollectsTheRequestedSampleSize() {
        val mc = chain(absorbing)
        val f = mc.firstPassageFrequency(sampleSize = 500, startState = 1, desiredState = 2)
        assertEquals(500.0, f.totalCount)
        assertTrue(f.values.min() >= 1, "a first passage takes at least one transition")
    }

    // ---- long run -----------------------------------------------------------------------

    @Test
    @DisplayName("The long run distribution matches the stationary one")
    fun theLongRunDistributionMatchesTheStationaryOne() {
        // The values MarkovChainExamples.mc1() prints as the truth, confirmed against a direct
        // solution of pi P = pi.
        val stationary = doubleArrayOf(238.0 / 854.0, 350.0 / 854.0, 266.0 / 854.0)
        val mc = chain(irreducible)
        val f = IntegerFrequency()
        repeat(200_000) { f.collect(mc.nextState()) }
        for (i in stationary.indices) {
            val observed = f.proportion(i + 1)
            assertTrue(
                abs(observed - stationary[i]) < 0.01,
                "state ${i + 1}: observed $observed, stationary ${stationary[i]}"
            )
        }
    }

    // ---- generating and tabulating a path -----------------------------------------------

    @Test
    @DisplayName("Generating a path returns the states visited, in order")
    fun generatingAPathReturnsTheStatesVisited() {
        val mc = chain(alternating)
        assertContentEquals(intArrayOf(2, 1, 2, 1), mc.generateStates(4))
        assertFailsWith<IllegalArgumentException> { mc.generateStates(0) }
    }

    @Test
    @DisplayName("A tabulated path recovers the transition matrix it was generated from")
    fun aTabulatedPathRecoversTheTransitionMatrix() {
        // StateFrequency counts transitions as well as visits, so the path can be used to
        // estimate the matrix that produced it.
        val mc = chain(irreducible)
        val f = mc.stateFrequency(200_000)
        assertEquals(200_000.0, f.totalCount)
        val estimated = f.transitionProportions
        for (i in 0..2) {
            for (j in 0..2) {
                assertTrue(
                    abs(estimated[i][j] - irreducible[i][j]) < 0.01,
                    "row ${i + 1} column ${j + 1}: estimated ${estimated[i][j]}, actual ${irreducible[i][j]}"
                )
            }
        }
    }

    @Test
    @DisplayName("A path can start from a distribution instead of a fixed state")
    fun aPathCanStartFromADistribution() {
        val mc = chain(irreducible, initialState = 1)
        // A degenerate distribution has only one possible answer, so this is checkable.
        assertEquals(3, mc.sampleStateFrom(doubleArrayOf(0.0, 0.0, 1.0)))
        assertEquals(3, mc.state)
        assertEquals(1, mc.initialState, "drawing a starting state must not redefine reset()")
        assertFailsWith<IllegalArgumentException> { mc.sampleStateFrom(doubleArrayOf(0.5, 0.5)) }
    }

    @Test
    @DisplayName("The chain exposes its own exact properties without simulating them")
    fun theChainExposesItsOwnExactProperties() {
        val mc = chain(irreducible)
        val pi = mc.dtmc.steadyStateDistribution
        assertTrue(abs(pi[0] - 238.0 / 854.0) < 1.0E-9)
        assertTrue(mc.dtmc.isIrreducible)
    }
}
