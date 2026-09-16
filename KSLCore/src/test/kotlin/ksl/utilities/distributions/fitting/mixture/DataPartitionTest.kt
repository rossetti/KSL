package ksl.utilities.distributions.fitting.mixture

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DataPartitionTest {

    @Test
    fun `single group covers all observations`() {
        val p = DataPartition.single(10)
        assertEquals(1, p.numGroups)
        assertEquals(0, p.startIndex(0))
        assertEquals(10, p.endIndex(0))
        assertEquals(10, p.sizeOf(0))
    }

    @Test
    fun `group ranges tile the observations without gap or overlap`() {
        val p = DataPartition(12, intArrayOf(3, 7))
        assertEquals(3, p.numGroups)
        assertEquals(0, p.startIndex(0))
        for (g in 0 until p.numGroups - 1) {
            assertEquals(p.endIndex(g), p.startIndex(g + 1), "group $g must abut group ${g + 1}")
        }
        assertEquals(12, p.endIndex(p.numGroups - 1))
        assertEquals(12, p.groupSizes.sum())
    }

    @Test
    fun `proportions sum to one and match sizes`() {
        val p = DataPartition(10, intArrayOf(2, 6))
        assertContentEquals(intArrayOf(2, 4, 4), p.groupSizes)
        val props = p.proportions
        assertEquals(1.0, props.sum(), 1e-12)
        assertEquals(0.2, props[0], 1e-12)
    }

    @Test
    fun `cuts must be strictly increasing and inside the data`() {
        assertFailsWith<IllegalArgumentException> { DataPartition(10, intArrayOf(5, 5)) }
        assertFailsWith<IllegalArgumentException> { DataPartition(10, intArrayOf(6, 3)) }
        assertFailsWith<IllegalArgumentException> { DataPartition(10, intArrayOf(0)) }
        assertFailsWith<IllegalArgumentException> { DataPartition(10, intArrayOf(10)) }
    }

    @Test
    fun `withCut returns a new partition and leaves the receiver unchanged`() {
        val original = DataPartition(12, intArrayOf(3, 7))
        val moved = original.withCut(0, 5)
        assertContentEquals(intArrayOf(3, 7), original.cutPositions, "receiver must be immutable")
        assertContentEquals(intArrayOf(5, 7), moved.cutPositions)
        assertNotEquals(original, moved)
    }

    @Test
    fun `withCut rejects a move that breaks ordering`() {
        val p = DataPartition(12, intArrayOf(3, 7))
        assertFailsWith<IllegalArgumentException> { p.withCut(0, 9) }
        assertFailsWith<IllegalArgumentException> { p.withCut(1, 2) }
    }

    @Test
    fun `range keys identify a group range and are reused across partitions`() {
        val a = DataPartition(12, intArrayOf(4, 8))
        val b = DataPartition(12, intArrayOf(4, 9))
        assertEquals(a.rangeKey(0), b.rangeKey(0), "an unchanged leading group must reuse its key")
        assertNotEquals(a.rangeKey(1), b.rangeKey(1))
    }

    @Test
    fun `group data extracts the correct observations`() {
        val data = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0)
        val p = DataPartition(6, intArrayOf(2, 4))
        assertContentEquals(doubleArrayOf(1.0, 2.0), p.groupData(data, 0))
        assertContentEquals(doubleArrayOf(3.0, 4.0), p.groupData(data, 1))
        assertContentEquals(doubleArrayOf(5.0, 6.0), p.groupData(data, 2))
    }

    @Test
    fun `equal sized partition keeps cuts strictly increasing even for small samples`() {
        for (n in 3..12) {
            for (k in 1..minOf(n, 5)) {
                val p = DataPartition.equalSized(n, k)
                assertEquals(k, p.numGroups, "n=$n k=$k")
                assertTrue(p.minimumGroupSize >= 1, "n=$n k=$k produced an empty group")
                assertEquals(n, p.groupSizes.sum(), "n=$n k=$k")
            }
        }
    }

    @Test
    fun `equality is by observation count and cuts`() {
        assertEquals(DataPartition(10, intArrayOf(3)), DataPartition(10, intArrayOf(3)))
        assertEquals(
            DataPartition(10, intArrayOf(3)).hashCode(),
            DataPartition(10, intArrayOf(3)).hashCode()
        )
        assertNotEquals(DataPartition(10, intArrayOf(3)), DataPartition(11, intArrayOf(3)))
    }

    @Test
    fun `invalid group index is rejected`() {
        val p = DataPartition(10, intArrayOf(5))
        assertFailsWith<IllegalArgumentException> { p.startIndex(2) }
        assertFailsWith<IllegalArgumentException> { p.startIndex(-1) }
    }
}
