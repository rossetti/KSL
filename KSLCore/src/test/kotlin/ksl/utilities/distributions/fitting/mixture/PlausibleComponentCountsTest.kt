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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlausibleComponentCountsTest {

    /** A test with the given verdict, built without fitting anything. */
    private fun contradicted(k: Int) = ComponentCountTest(
        k, k + 1, 400, improvementStatistic = 1.0e9,
        nullStatistics = DoubleArray(199) { it.toDouble() }, numReplicates = 199
    )

    private fun notContradicted(k: Int) = ComponentCountTest(
        k, k + 1, 400, improvementStatistic = 5.0,
        nullStatistics = DoubleArray(199) { it.toDouble() }, numReplicates = 199
    )

    private fun inconclusive(k: Int) = ComponentCountTest(
        k, k + 1, 400, improvementStatistic = 1.0e9,
        nullStatistics = DoubleArray(5) { it.toDouble() }, numReplicates = 199
    )

    @Test
    fun theSetHoldsOnlyCountsThatSurvivedTheirTest() {
        val set = PlausibleComponentCounts(
            listOf(1, 2, 3, 4),
            mapOf(1 to contradicted(1), 2 to contradicted(2),
                3 to notContradicted(3), 4 to notContradicted(4)),
            0.05
        )
        assertEquals(setOf(3, 4), set.notRejected)
        assertEquals(setOf(1, 2), set.rejected)
        assertEquals(3, set.smallestNotRejected)
        assertEquals(4, set.largestNotRejected)
        assertEquals(2, set.size)
    }

    @Test
    fun anInconclusiveCountIsNotCountedAsSurviving() {
        // The distinction the class exists to keep: nobody could evaluate 2, which is not the same
        // as 2 having survived. Folding it into the set would turn a cost failure into support.
        val set = PlausibleComponentCounts(
            listOf(1, 2, 3),
            mapOf(1 to contradicted(1), 2 to inconclusive(2), 3 to notContradicted(3)),
            0.05
        )
        assertEquals(setOf(3), set.notRejected)
        assertEquals(setOf(2), set.inconclusiveAt)
        assertFalse(2 in set.notRejected)
        assertEquals(3, set.smallestNotRejected)
    }

    @Test
    fun aCountWithNoTestIsNotAssessableRatherThanInconclusive() {
        // No test could be formed at all. Distinct from a test that ran and could not decide.
        val set = PlausibleComponentCounts(
            listOf(1, 2, 3),
            mapOf(1 to notContradicted(1), 2 to inconclusive(2)),
            0.05
        )
        assertEquals(setOf(3), set.notAssessable)
        assertEquals(setOf(2), set.inconclusiveAt)
        assertFalse(3 in set.notRejected)
        assertFalse(3 in set.inconclusiveAt)
    }

    @Test
    fun aBrokenRunIsReportedAsNotContiguous() {
        // The case that makes reporting min..max a lie: 3 is rejected but sits between two
        // survivors, so a range would assert coverage of a count the test ruled out.
        val set = PlausibleComponentCounts(
            listOf(2, 3, 4),
            mapOf(2 to notContradicted(2), 3 to contradicted(3), 4 to notContradicted(4)),
            0.05
        )
        assertEquals(setOf(2, 4), set.notRejected)
        assertFalse(set.isContiguous)
        assertTrue(set.explain().contains("not an unbroken run"))
    }

    @Test
    fun anUnbrokenRunIsContiguousAndSoAreTheTrivialCases() {
        assertTrue(
            PlausibleComponentCounts(
                listOf(2, 3, 4),
                mapOf(2 to notContradicted(2), 3 to notContradicted(3), 4 to notContradicted(4)),
                0.05
            ).isContiguous
        )
        assertTrue(
            PlausibleComponentCounts(listOf(2), mapOf(2 to notContradicted(2)), 0.05).isContiguous
        )
        // Empty survives vacuously rather than throwing.
        assertTrue(
            PlausibleComponentCounts(listOf(2), mapOf(2 to contradicted(2)), 0.05).isContiguous
        )
    }

    @Test
    fun anEmptySetSaysSoRatherThanReportingASmallestMember() {
        val set = PlausibleComponentCounts(
            listOf(1, 2), mapOf(1 to contradicted(1), 2 to contradicted(2)), 0.05
        )
        assertTrue(set.notRejected.isEmpty())
        assertNull(set.smallestNotRejected)
        assertNull(set.largestNotRejected)
        assertTrue(set.explain().contains("No component count"))
    }

    @Test
    fun untestedCountsEarnAnExtraCaveat() {
        val without = PlausibleComponentCounts(
            listOf(1, 2), mapOf(1 to contradicted(1), 2 to notContradicted(2)), 0.05
        )
        val with = PlausibleComponentCounts(
            listOf(1, 2, 3),
            mapOf(1 to contradicted(1), 2 to notContradicted(2), 3 to inconclusive(3)),
            0.05
        )
        assertTrue(with.caveats().size > without.caveats().size)
        assertTrue(with.caveats().any { it.contains("could not be decided") })
    }

    @Test
    fun aTestForAnUnconsideredCountIsRefused() {
        assertFailsWith<IllegalArgumentException> {
            PlausibleComponentCounts(listOf(1, 2), mapOf(7 to notContradicted(7)), 0.05)
        }
    }

    @Test
    fun anEmptyRangeIsRefused() {
        assertFailsWith<IllegalArgumentException> {
            PlausibleComponentCounts(emptyList(), emptyMap(), 0.05)
        }
    }

    // ------------------------------------------------------------------ end to end

    @Test
    fun theSetOnSeparatedDataKeepsTheTruthAndDropsOneComponent() {
        val data = NormalRV(0.0, 1.0, streamNum = 601).sample(150) +
                NormalRV(12.0, 1.0, streamNum = 602).sample(150)
        val set = MixtureModeler(data).plausibleComponentCounts(
            numComponentsRange = 1..3, numReplicates = 40,
            streamNum = 1, streamProvider = RNStreamProvider(name = "plausible-end-to-end")
        )
        assertTrue(1 in set.rejected, "one component should be ruled out\n$set")
        assertTrue(2 in set.notRejected, "the truth should survive\n$set")
        assertEquals(2, set.smallestNotRejected, "the smallest survivor should be the truth\n$set")
    }

    @Test
    fun everyCountConsideredIsAccountedForExactlyOnce() {
        val data = NormalRV(0.0, 1.0, streamNum = 611).sample(120) +
                NormalRV(10.0, 1.0, streamNum = 612).sample(120)
        val set = MixtureModeler(data).plausibleComponentCounts(
            numComponentsRange = 1..4, numReplicates = 20,
            streamNum = 5, streamProvider = RNStreamProvider(name = "plausible-partition")
        )
        val union = set.notRejected + set.rejected + set.inconclusiveAt + set.notAssessable
        assertEquals(set.numComponentsConsidered.toSet(), union)
        val total = set.notRejected.size + set.rejected.size +
                set.inconclusiveAt.size + set.notAssessable.size
        assertEquals(set.numComponentsConsidered.size, total, "the four outcomes must not overlap")
    }
}
