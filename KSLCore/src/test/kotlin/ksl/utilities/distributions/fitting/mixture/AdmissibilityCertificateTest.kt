package ksl.utilities.distributions.fitting.mixture

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdmissibilityCertificateTest {

    @Test
    fun `admissible cuts fall only between distinct values`() {
        val c = AdmissibilityCertificate(doubleArrayOf(1.0, 1.0, 2.0, 3.0, 3.0, 3.0), 1, 1)
        assertContentEquals(intArrayOf(0, 2, 3, 6), c.admissibleCuts)
        assertFalse(c.isAdmissibleCut(1), "a cut inside the run of 1.0 must be inadmissible")
        assertFalse(c.isAdmissibleCut(4), "a cut inside the run of 3.0 must be inadmissible")
        assertTrue(c.isAdmissibleCut(2))
    }

    @Test
    fun `distinct prefix counts distinct values`() {
        val c = AdmissibilityCertificate(doubleArrayOf(1.0, 1.0, 2.0, 3.0, 3.0), 1, 1)
        assertEquals(0, c.distinctPrefix(0))
        assertEquals(1, c.distinctPrefix(2))
        assertEquals(2, c.distinctPrefix(3))
        assertEquals(3, c.numDistinctValues)
        assertEquals(2, c.distinctCount(2, 5))
    }

    @Test
    fun `a group of tied values fails the distinct requirement`() {
        val c = AdmissibilityCertificate(doubleArrayOf(0.0, 0.0, 1.0, 2.0, 3.0, 4.0), 2, 2)
        assertFalse(c.isValidGroup(0, 2), "two tied observations hold one distinct value")
        assertTrue(c.isValidGroup(0, 3))
    }

    @Test
    fun `data must be sorted and finite`() {
        assertFailsWith<IllegalArgumentException> {
            AdmissibilityCertificate(doubleArrayOf(2.0, 1.0))
        }
        assertFailsWith<IllegalArgumentException> {
            AdmissibilityCertificate(doubleArrayOf(1.0, Double.NaN))
        }
        assertFailsWith<IllegalArgumentException> {
            AdmissibilityCertificate(DoubleArray(0))
        }
    }

    @Test
    fun `all values tied admits exactly one group`() {
        val c = AdmissibilityCertificate(DoubleArray(20) { 7.0 }, 1, 1)
        assertEquals(1, c.numDistinctValues)
        assertEquals(1, c.maximumFeasibleGroups)
        assertTrue(c.isFeasible(1))
        assertFalse(c.isFeasible(2))
        assertNotNull(c.infeasibilityReason(2))
        assertNull(c.infeasibilityReason(1))
    }

    @Test
    fun `constant data cannot meet a distinct requirement of two`() {
        val c = AdmissibilityCertificate(DoubleArray(20) { 7.0 }, 1, 2)
        assertEquals(0, c.maximumFeasibleGroups)
        assertFalse(c.isFeasible(1))
    }

    @Test
    fun `feasible group counts restrict a requested range`() {
        val c = AdmissibilityCertificate(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0), 2, 2)
        // 6 observations, each group needs 2 observations and 2 distinct values -> at most 3
        assertEquals(3, c.maximumFeasibleGroups)
        assertContentEquals(listOf(1, 2, 3), c.feasibleGroupCounts(1..6))
    }

    /**
     *  The greedy maximum must agree with exhaustive enumeration. This is the property the
     *  design relies on to make the infeasible branch unreachable, so it is checked against a
     *  brute-force reference rather than assumed.
     */
    @Test
    fun `maximum feasible groups agrees with exhaustive enumeration`() {
        val rng = Random(20260731)
        var checked = 0
        repeat(3000) {
            val n = rng.nextInt(1, 11)
            val minSize = rng.nextInt(1, 4)
            val minDistinct = rng.nextInt(1, 4)
            val data = DoubleArray(n) { rng.nextInt(1, 5).toDouble() }.sortedArray()
            val c = AdmissibilityCertificate(data, minSize, minDistinct)
            val brute = bruteForceMaximumGroups(c, n)
            assertEquals(
                brute, c.maximumFeasibleGroups,
                "n=$n minSize=$minSize minDistinct=$minDistinct data=${data.toList()}"
            )
            checked++
        }
        assertTrue(checked == 3000)
    }

    /**
     *  Largest k for which some admissible, size-valid, distinct-valid partition exists, by
     *  enumerating all combinations of interior admissible cuts.
     */
    private fun bruteForceMaximumGroups(c: AdmissibilityCertificate, n: Int): Int {
        val interior = c.admissibleCuts.filter { it in 1 until n }
        var best = 0
        for (k in 1..(interior.size + 1)) {
            if (existsPartition(c, interior, k, n)) best = k
        }
        return best
    }

    private fun existsPartition(
        c: AdmissibilityCertificate,
        interior: List<Int>,
        k: Int,
        n: Int
    ): Boolean {
        if (k == 1) return c.isValidGroup(0, n)
        val chosen = IntArray(k - 1)
        return choose(c, interior, chosen, 0, 0, k, n)
    }

    private fun choose(
        c: AdmissibilityCertificate,
        interior: List<Int>,
        chosen: IntArray,
        depth: Int,
        startAt: Int,
        k: Int,
        n: Int
    ): Boolean {
        if (depth == k - 1) {
            var previous = 0
            for (cut in chosen) {
                if (!c.isValidGroup(previous, cut)) return false
                previous = cut
            }
            return c.isValidGroup(previous, n)
        }
        for (i in startAt until interior.size) {
            chosen[depth] = interior[i]
            if (choose(c, interior, chosen, depth + 1, i + 1, k, n)) return true
        }
        return false
    }

    @Test
    fun `from unsorted does not modify the caller array`() {
        val data = doubleArrayOf(3.0, 1.0, 2.0)
        val copy = data.copyOf()
        val c = AdmissibilityCertificate.fromUnsorted(data, 1, 1)
        assertContentEquals(copy, data, "the caller's array must not be sorted in place")
        assertEquals(3, c.numDistinctValues)
    }
}
