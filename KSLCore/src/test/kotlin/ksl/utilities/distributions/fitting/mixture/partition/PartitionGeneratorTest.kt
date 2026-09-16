package ksl.utilities.distributions.fitting.mixture.partition

import ksl.utilities.distributions.fitting.mixture.AdmissibilityCertificate
import ksl.utilities.distributions.fitting.mixture.DataPartition
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PartitionGeneratorTest {

    // ------------------------------------------------------------------ Jenks, unconstrained

    /**
     *  The dynamic program must attain the true minimum total within-group sum of squares.
     *  Checked against exhaustive enumeration of every contiguous partition.
     */
    @Test
    fun `jenks attains the brute force optimum`() {
        val rng = Random(11)
        repeat(400) {
            val n = rng.nextInt(2, 13)
            val k = rng.nextInt(1, minOf(n, 5) + 1)
            val data = DoubleArray(n) { rng.nextDouble(0.0, 20.0) }.sortedArray()
            val gen = JenksPartitionGenerator(data, k)
            val dp = gen.totalWithinGroupSumOfSquares(k)
            val brute = bruteForceMinimumSS(data, k)
            assertEquals(brute, dp, 1e-9, "n=$n k=$k data=${data.toList()}")
            // the reconstructed partition must actually achieve the reported value
            val p = gen.classify(k)
            assertEquals(k, p.numGroups)
            assertEquals(dp, totalSS(data, p), 1e-9, "reconstruction must attain the optimum")
        }
    }

    @Test
    fun `jenks separates well separated clusters`() {
        val data = doubleArrayOf(1.0, 1.1, 1.2, 10.0, 10.1, 10.2, 50.0, 50.1, 50.2)
        val gen = JenksPartitionGenerator(data, 3)
        val p = gen.classify(3)
        assertContentEquals(intArrayOf(3, 6), p.cutPositions)
    }

    @Test
    fun `total within group sum of squares is non-increasing in the number of groups`() {
        val rng = Random(3)
        val data = DoubleArray(40) { rng.nextDouble(0.0, 100.0) }.sortedArray()
        val gen = JenksPartitionGenerator(data, 8)
        var previous = Double.MAX_VALUE
        for (k in 1..8) {
            val v = gen.totalWithinGroupSumOfSquares(k)
            assertTrue(v <= previous + 1e-9, "SS increased from $previous to $v at k=$k")
            previous = v
        }
    }

    @Test
    fun `sum of squares is zero for constant and singleton ranges`() {
        val gen = JenksPartitionGenerator(doubleArrayOf(5.0, 5.0, 5.0, 5.0), 2)
        assertEquals(0.0, gen.sumOfSquares(0, 4), 1e-12)
        assertEquals(0.0, gen.sumOfSquares(1, 2), 1e-12)
        assertEquals(0.0, gen.sumOfSquares(2, 2), 1e-12)
    }

    // -------------------------------------------------------------------- Jenks, constrained

    /**
     *  The constrained generator must attain the optimum over partitions that respect ties and
     *  the group requirements, which is a different optimum from the unconstrained one.
     */
    @Test
    fun `constrained jenks attains the constrained brute force optimum`() {
        val rng = Random(1234)
        var exercised = 0
        repeat(600) {
            val n = rng.nextInt(2, 13)
            val k = rng.nextInt(1, minOf(n, 4) + 1)
            val minSize = rng.nextInt(1, 3)
            val minDistinct = rng.nextInt(1, 3)
            // draw from a small value set so ties occur often
            val data = DoubleArray(n) { rng.nextInt(1, 6).toDouble() }.sortedArray()
            val cert = AdmissibilityCertificate(data, minSize, minDistinct)
            val gen = JenksPartitionGenerator(data, maxOf(k, 1))
            val p = gen.generate(data, k, cert)
            val brute = bruteForceConstrained(data, k, cert)
            if (brute == null) {
                assertNull(p, "no feasible partition exists for n=$n k=$k data=${data.toList()}")
            } else {
                assertNotNull(p, "a feasible partition exists for n=$n k=$k data=${data.toList()}")
                assertTrue(
                    gen.satisfies(p, cert),
                    "returned partition violates the certificate for data=${data.toList()}"
                )
                assertEquals(
                    totalSS(data, brute), totalSS(data, p), 1e-9,
                    "n=$n k=$k minSize=$minSize minDistinct=$minDistinct data=${data.toList()}"
                )
                exercised++
            }
        }
        assertTrue(exercised > 100, "the feasible branch was barely exercised: $exercised")
    }

    @Test
    fun `constrained jenks never splits a run of equal values`() {
        val data = doubleArrayOf(0.0, 0.0, 0.0, 1.0, 2.0, 3.0, 3.0, 3.0)
        val cert = AdmissibilityCertificate(data, 1, 1)
        val gen = JenksPartitionGenerator(data, 3)
        val p = gen.generate(data, 3, cert)
        assertNotNull(p)
        for (c in p.cutPositions) {
            assertTrue(cert.isAdmissibleCut(c), "cut $c splits a tied block")
        }
    }

    @Test
    fun `constrained jenks returns null when the request is infeasible`() {
        val data = DoubleArray(10) { 4.0 }
        val cert = AdmissibilityCertificate(data, 1, 1)
        val gen = JenksPartitionGenerator(data, 3)
        assertNull(gen.generate(data, 2, cert), "tied data admits only one group")
    }

    // ------------------------------------------------------------------------ all generators

    @Test
    fun `every generator returns a partition satisfying the certificate or null`() {
        val rng = Random(77)
        val generators: List<(DoubleArray, Int) -> PartitionGeneratorIfc> = listOf(
            { d, k -> JenksPartitionGenerator(d, k) },
            { _, _ -> QuantilePartitionGenerator() },
            { _, _ -> HistogramValleyPartitionGenerator() }
        )
        repeat(150) {
            val n = rng.nextInt(6, 60)
            val k = rng.nextInt(1, 5)
            val data = DoubleArray(n) { rng.nextDouble(0.0, 50.0) }.sortedArray()
            val cert = AdmissibilityCertificate(data, 2, 2)
            for (factory in generators) {
                val gen = factory(data, maxOf(k, 1))
                val p = gen.generate(data, k, cert)
                if (p != null) {
                    assertEquals(k, p.numGroups, "${gen.name} returned the wrong group count")
                    assertTrue(
                        gen.satisfies(p, cert),
                        "${gen.name} returned a partition violating the certificate"
                    )
                }
            }
        }
    }

    @Test
    fun `quantile generator produces roughly balanced groups on distinct data`() {
        val data = DoubleArray(100) { it.toDouble() }
        val cert = AdmissibilityCertificate(data, 2, 2)
        val p = QuantilePartitionGenerator().generate(data, 4, cert)
        assertNotNull(p)
        for (g in 0 until p.numGroups) {
            assertTrue(p.sizeOf(g) in 20..30, "group $g had size ${p.sizeOf(g)}")
        }
    }

    @Test
    fun `histogram valley generator finds the gap in a clearly bimodal sample`() {
        val data = (DoubleArray(60) { 1.0 + it * 0.01 } + DoubleArray(60) { 40.0 + it * 0.01 })
            .sortedArray()
        val cert = AdmissibilityCertificate(data, 2, 2)
        val p = HistogramValleyPartitionGenerator().generate(data, 2, cert)
        assertNotNull(p)
        val cut = p.cutPositions[0]
        assertTrue(cut in 50..70, "the cut should fall near the gap at 60, it was $cut")
    }

    @Test
    fun `generators return null rather than an invalid partition when k exceeds what data allows`() {
        val data = doubleArrayOf(1.0, 1.0, 2.0, 2.0)
        val cert = AdmissibilityCertificate(data, 2, 2)
        assertEquals(1, cert.maximumFeasibleGroups)
        assertNull(JenksPartitionGenerator(data, 2).generate(data, 2, cert))
        assertNull(QuantilePartitionGenerator().generate(data, 2, cert))
        assertNull(HistogramValleyPartitionGenerator().generate(data, 2, cert))
    }

    // ------------------------------------------------------------------------------ helpers

    private fun totalSS(data: DoubleArray, p: DataPartition): Double {
        var total = 0.0
        for (g in 0 until p.numGroups) {
            val start = p.startIndex(g)
            val end = p.endIndex(g)
            val count = end - start
            if (count <= 1) continue
            var sum = 0.0
            for (i in start until end) sum += data[i]
            val mean = sum / count
            for (i in start until end) {
                val d = data[i] - mean
                total += d * d
            }
        }
        return total
    }

    private fun bruteForceMinimumSS(data: DoubleArray, k: Int): Double {
        var best = Double.MAX_VALUE
        forEachPartition(data.size, k) { cuts ->
            val ss = totalSS(data, DataPartition(data.size, cuts))
            if (ss < best) best = ss
        }
        return best
    }

    private fun bruteForceConstrained(
        data: DoubleArray,
        k: Int,
        cert: AdmissibilityCertificate
    ): DataPartition? {
        var best: DataPartition? = null
        var bestSS = Double.MAX_VALUE
        forEachPartition(data.size, k) { cuts ->
            val p = DataPartition(data.size, cuts)
            var ok = true
            for (c in cuts) if (!cert.isAdmissibleCut(c)) ok = false
            if (ok) {
                for (g in 0 until p.numGroups) {
                    if (!cert.isValidGroup(p.startIndex(g), p.endIndex(g))) {
                        ok = false
                        break
                    }
                }
            }
            if (ok) {
                val ss = totalSS(data, p)
                if (ss < bestSS) {
                    bestSS = ss
                    best = p
                }
            }
        }
        return best
    }

    /** Enumerates every set of k-1 strictly increasing interior cuts of n observations. */
    private fun forEachPartition(n: Int, k: Int, action: (IntArray) -> Unit) {
        if (k == 1) {
            action(IntArray(0))
            return
        }
        val cuts = IntArray(k - 1)
        fun recurse(depth: Int, from: Int) {
            if (depth == k - 1) {
                action(cuts.copyOf())
                return
            }
            for (t in from until n) {
                cuts[depth] = t
                recurse(depth + 1, t + 1)
            }
        }
        recurse(0, 1)
    }
}
