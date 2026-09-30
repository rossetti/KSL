package ksl.utilities.distributions.fitting.mixture

import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.LognormalRV
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  With automatic shifting on, fitting a group bootstraps a confidence interval for its minimum, and
 *  that bootstrap drew from the default stream provider. A fitter given its own provider must give the
 *  same fit every time and leave the default provider untouched.
 */
class ComponentFitterStreamsTest {

    private val data = LognormalRV(8.0, 9.0, streamNum = 5, streamProvider = RNStreamProvider())
        .sample(200).sortedArray()

    private fun fitWithOwnProvider(): GroupFitResult =
        PDFComponentFitter(automaticShifting = true, bootstrapStreamProvider = RNStreamProvider())
            .fitGroup(data, 0, data.size)

    @Test
    fun aFitterWithItsOwnProviderIsReproducibleAndLeavesTheDefaultAlone() {
        val before = KSLRandom.DefaultRNStreamProvider.lastRNStreamNumber()
        val first = fitWithOwnProvider()
        val second = fitWithOwnProvider()
        assertEquals(before, KSLRandom.DefaultRNStreamProvider.lastRNStreamNumber(),
            "the shift check drew a stream from the default provider")
        assertTrue(first.candidates.isNotEmpty())
        assertEquals(first.candidates.map { it.name }, second.candidates.map { it.name })
        for ((a, b) in first.candidates.zip(second.candidates)) {
            assertContentEquals(a.distribution.parameters(), b.distribution.parameters(), a.name)
        }
    }
}
