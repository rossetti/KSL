package ksl.utilities.distributions.fitting.mixture.partition

import ksl.utilities.distributions.fitting.mixture.AdmissibilityCertificate
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  Equal-width bins put no resolution where a skewed sample lives. With the whole of a two-cluster
 *  structure inside the first of fifty bins, the old generator cut at rank 902 of 1,000, out among
 *  the tail, when the gap it exists to find is at rank 450.
 */
class HistogramValleyGapTest {

    /**
     *  Two clusters with a clear gap between them, all between 1 and 20, plus a sparse upper tail
     *  reaching 2000. Equal-width binning over 1 to 2000 with 50 bins gives a bin width of about 40,
     *  so the whole of the structure falls inside the first bin.
     */
    private fun twoClustersWithATail(): DoubleArray {
        val low = DoubleArray(450) { 1.0 + it * (7.0 / 450.0) }
        val high = DoubleArray(450) { 12.0 + it * (8.0 / 450.0) }
        val tail = DoubleArray(100) { 100.0 + it * (1900.0 / 100.0) }
        return (low + high + tail).sortedArray()
    }

    @Test
    fun theCutFallsNearTheGapOnASkewedSample() {
        val data = twoClustersWithATail()
        val cert = AdmissibilityCertificate(data, 2, 2)
        val p = HistogramValleyPartitionGenerator().generate(data, 2, cert)
        assertNotNull(p)
        val cut = p.cutPositions[0]
        // The true boundary is rank 450. Edges are n/numBins = 1000/32, about 31 ranks, apart;
        // the band allows twice that.
        assertTrue(cut in 385..515, "the cut should fall near rank 450, it was $cut")
    }

    @Test
    fun aRunOfTiesIsNotReadAsAGap() {
        // Every edge in the middle of the sample lands on the same value, giving zero-width bins.
        val data = (DoubleArray(300) { 1.0 + it * 0.01 } + DoubleArray(400) { 5.0 } +
                DoubleArray(300) { 9.0 + it * 0.01 }).sortedArray()
        val cert = AdmissibilityCertificate(data, 2, 2)
        val p = HistogramValleyPartitionGenerator().generate(data, 2, cert)
        if (p != null) {
            val cut = p.cutPositions[0]
            assertTrue(data[cut - 1] != 5.0 || data[cut] != 5.0, "the cut split the run of ties at $cut")
        }
    }

    @Test
    fun constantDataHasNoValley() {
        val data = DoubleArray(200) { 3.0 }
        val cert = AdmissibilityCertificate(data, 1, 1)
        assertNull(HistogramValleyPartitionGenerator().generate(data, 2, cert))
    }
}
