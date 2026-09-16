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

import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rvariable.NormalRV
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  Gate 1 of the hypothesise-and-test plan.
 *
 *  The reproducibility pair is deliberate. A test that only asserts two runs agree can pass while
 *  the stream is never repositioned — that is exactly how the `ModalityAnalyzer` stream defect
 *  survived review, because its statistic saturated and every run agreed for the wrong reason. The
 *  companion assertion, that *different* streams produce *different* nulls, is the half a broken
 *  repositioning cannot fake.
 */
class ComponentCountTestTest {

    /** Two clearly separated components. */
    private fun twoSeparated(n: Int, stream: Int): DoubleArray =
        NormalRV(0.0, 1.0, streamNum = stream).sample(n / 2) +
                NormalRV(12.0, 1.0, streamNum = stream + 1).sample(n - n / 2)

    private fun modeler(data: DoubleArray) = MixtureModeler(data)

    // ------------------------------------------------------------------ (a) and (b)

    @Test
    fun theSameStreamNumberGivesTheSameNullToTheDigit() {
        val data = twoSeparated(160, 11)
        val first = modeler(data).testComponentCount(
            hypothesisedNumComponents = 2, numReplicates = 12,
            streamNum = 3, streamProvider = RNStreamProvider(name = "a")
        )
        val second = modeler(data).testComponentCount(
            hypothesisedNumComponents = 2, numReplicates = 12,
            streamNum = 3, streamProvider = RNStreamProvider(name = "b")
        )
        assertNotNull(first)
        assertNotNull(second)
        assertContentEquals(first.nullStatistics, second.nullStatistics)
        assertEquals(first.pValue, second.pValue)
    }

    @Test
    fun aDifferentStreamNumberGivesADifferentNull() {
        // The half that a stream which is never repositioned cannot pass.
        val data = twoSeparated(160, 11)
        val first = modeler(data).testComponentCount(
            hypothesisedNumComponents = 2, numReplicates = 12,
            streamNum = 3, streamProvider = RNStreamProvider(name = "a")
        )
        val second = modeler(data).testComponentCount(
            hypothesisedNumComponents = 2, numReplicates = 12,
            streamNum = 9, streamProvider = RNStreamProvider(name = "a")
        )
        assertNotNull(first)
        assertNotNull(second)
        assertTrue(first.numUsable > 0, "the null must be non-empty for this test to mean anything")
        assertNotEquals(
            first.nullStatistics.toList(), second.nullStatistics.toList(),
            "different stream numbers produced identical nulls, so the stream is not being used"
        )
    }

    // ------------------------------------------------------------------ (c)

    @Test
    fun anInfeasibleHypothesisedCountReturnsNull() {
        // Twelve observations cannot support eight components at the default minimum group size.
        val data = NormalRV(0.0, 1.0, streamNum = 77).sample(12)
        assertNull(modeler(data).testComponentCount(8, numReplicates = 5))
    }

    @Test
    fun anInfeasibleAlternativeAlsoReturnsNull() {
        // The count itself is admissible but the one above it is not, so the increment cannot be
        // formed and the question does not arise.
        val data = NormalRV(0.0, 1.0, streamNum = 78).sample(12)
        val m = modeler(data)
        val k = (1..10).last { m.certificate.infeasibilityReason(it) == null }
        assertNull(m.testComponentCount(k, numReplicates = 5))
    }

    // ------------------------------------------------------------------ (d)

    @Test
    fun aTestWithNoUsableReplicatesIsInconclusiveRatherThanThrowing() {
        val test = ComponentCountTest(
            hypothesisedNumComponents = 2, alternativeNumComponents = 3,
            numObservations = 100, improvementStatistic = 4.0,
            nullStatistics = DoubleArray(0), numReplicates = 50
        )
        assertEquals(0, test.numUsable)
        assertEquals(50, test.numUnusable)
        assertNull(test.pValue)
        assertEquals(CountEvidence.INCONCLUSIVE, test.verdict)
        assertTrue(test.nullQuantiles().isEmpty())
    }

    @Test
    fun tooFewReplicatesToReachTheLevelIsInconclusiveNotNotContradicted() {
        // Ten replicates cannot produce a p-value below 1/11, so at a level of 0.05 no data could
        // reject. Reporting NOT_CONTRADICTED here would report the replicate count as a finding.
        val test = ComponentCountTest(
            hypothesisedNumComponents = 2, alternativeNumComponents = 3,
            numObservations = 100, improvementStatistic = 1000.0,
            nullStatistics = DoubleArray(10) { it.toDouble() }, numReplicates = 10, level = 0.05
        )
        assertTrue(test.pValue!! > 0.05)
        assertTrue(!test.canReject)
        assertEquals(CountEvidence.INCONCLUSIVE, test.verdict)
    }

    @Test
    fun heavyAttritionIsInconclusiveEvenWhenTheSurvivorsWouldReject() {
        // 30 of 200 usable: enough resolution to reject, but the survivors are the replicates this
        // procedure found easy and a null built from them is not the null.
        val test = ComponentCountTest(
            hypothesisedNumComponents = 2, alternativeNumComponents = 3,
            numObservations = 100, improvementStatistic = 1000.0,
            nullStatistics = DoubleArray(30) { it.toDouble() }, numReplicates = 200
        )
        assertTrue(test.canReject)
        assertEquals(0.15, test.usableShare, 1.0e-12)
        assertEquals(CountEvidence.INCONCLUSIVE, test.verdict)
    }

    // ------------------------------------------------------------------ the p-value's form

    @Test
    fun thePValueNeverReachesZero() {
        val test = ComponentCountTest(
            hypothesisedNumComponents = 2, alternativeNumComponents = 3,
            numObservations = 100, improvementStatistic = 1.0e9,
            nullStatistics = DoubleArray(199) { it.toDouble() }, numReplicates = 199
        )
        assertEquals(1.0 / 200.0, test.pValue!!, 1.0e-12)
        assertEquals(1.0 / 200.0, test.smallestAttainablePValue, 1.0e-12)
        assertEquals(CountEvidence.CONTRADICTED, test.verdict)
    }

    @Test
    fun anObservedInsideTheNullIsNotContradicted() {
        val test = ComponentCountTest(
            hypothesisedNumComponents = 3, alternativeNumComponents = 4,
            numObservations = 400, improvementStatistic = 50.0,
            nullStatistics = DoubleArray(199) { it.toDouble() }, numReplicates = 199
        )
        assertEquals(CountEvidence.NOT_CONTRADICTED, test.verdict)
        assertTrue(test.pValue!! > 0.05)
    }

    @Test
    fun theQuantilesAreOrderedAndIndependentOfTheOrderGiven() {
        val ascending = DoubleArray(100) { it.toDouble() }
        val shuffled = ascending.toList().shuffled().toDoubleArray()
        fun of(v: DoubleArray) = ComponentCountTest(2, 3, 100, 1.0, v, 100).nullQuantiles()
        assertEquals(of(ascending), of(shuffled))
        val q = of(ascending)
        assertTrue(q.getValue(0.50) <= q.getValue(0.90))
        assertTrue(q.getValue(0.90) <= q.getValue(0.95))
        assertTrue(q.getValue(0.95) <= q.getValue(0.99))
    }

    // ------------------------------------------------------------------ (e) the mechanism itself

    @Test
    fun theTruthSurvivesAndOneComponentFewerDoesNot() {
        // The only test here that asserts the thing works. Two components separated by twelve
        // standard deviations: a third buys nothing, a second buys a great deal. It must fail if
        // the comparison is inverted, which is why both directions are asserted together.
        val data = twoSeparated(300, 501)
        val provider = RNStreamProvider(name = "count-test-mechanism")

        val atTruth = modeler(data).testComponentCount(
            hypothesisedNumComponents = 2, numReplicates = 60,
            streamNum = 1, streamProvider = provider
        )
        assertNotNull(atTruth)
        assertEquals(
            CountEvidence.NOT_CONTRADICTED, atTruth.verdict,
            "two well-separated components should not be contradicted; " +
                    "statistic=${atTruth.improvementStatistic} p=${atTruth.pValue}"
        )

        val belowTruth = modeler(data).testComponentCount(
            hypothesisedNumComponents = 1, numReplicates = 60,
            streamNum = 1, streamProvider = RNStreamProvider(name = "count-test-mechanism-below")
        )
        assertNotNull(belowTruth)
        assertEquals(
            CountEvidence.CONTRADICTED, belowTruth.verdict,
            "one component should be contradicted by two separated groups; " +
                    "statistic=${belowTruth.improvementStatistic} p=${belowTruth.pValue}"
        )
    }

    // ------------------------------------------------------------------ argument checking

    @Test
    fun theAlternativeMustBeOneMoreThanTheHypothesis() {
        assertFailsWith<IllegalArgumentException> {
            ComponentCountTest(2, 4, 100, 1.0, DoubleArray(0), 10)
        }
    }

    @Test
    fun aLevelOutsideZeroToOneIsRefused() {
        assertFailsWith<IllegalArgumentException> {
            ComponentCountTest(2, 3, 100, 1.0, DoubleArray(0), 10, level = 0.0)
        }
        assertFailsWith<IllegalArgumentException> {
            ComponentCountTest(2, 3, 100, 1.0, DoubleArray(0), 10, level = 1.0)
        }
    }

    @Test
    fun aNonPositiveReplicateCountIsRefused() {
        val data = twoSeparated(120, 31)
        assertFailsWith<IllegalArgumentException> {
            modeler(data).testComponentCount(2, numReplicates = 0)
        }
    }

    @Test
    fun theNullCannotBeReorderedByACaller() {
        val test = ComponentCountTest(2, 3, 100, 1.0, doubleArrayOf(3.0, 1.0, 2.0), 3)
        val handedOut = test.nullStatistics
        handedOut[0] = 999.0
        assertContentEquals(doubleArrayOf(1.0, 2.0, 3.0), test.nullStatistics)
    }
}
