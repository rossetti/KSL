package ksl.utilities.distributions.fitting.mixture

import ksl.utilities.distributions.fitting.mixture.partition.HistogramValleyPartitionGenerator
import ksl.utilities.distributions.fitting.mixture.partition.JenksPartitionGenerator
import ksl.utilities.distributions.fitting.mixture.partition.QuantilePartitionGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  A generator only supplies the starting partition, so it can decide the result. Running several
 *  and keeping the best by criterion must never do worse, at any count, than the best single one.
 */
class MultiStartFitTest {

    private val data = receptionDeskData()
    private val range = 1..3

    private fun bestValueByCount(results: MixtureModelingResults): Map<Int, Double> =
        results.bestByNumComponents().mapValues { it.value.criterionValue.value }

    @Test
    fun multiStartIsNeverWorseThanAnySingleGenerator() {
        val multi = bestValueByCount(MixtureModeler(data).fitMultiStart(range))
        val singles = listOf(
            JenksPartitionGenerator(data.sortedArray(), range.last),
            QuantilePartitionGenerator(),
            HistogramValleyPartitionGenerator()
        ).map { bestValueByCount(MixtureModeler(data).fit(range, it)) }
        for (single in singles) {
            for ((k, value) in single) {
                val m = multi[k] ?: error("multi-start lost count $k")
                assertTrue(m <= value + 1e-9, "at k = $k multi-start gave $m, a single generator $value")
            }
        }
    }

    @Test
    fun theWinningGeneratorIsRecorded() {
        val results = MixtureModeler(data).fitMultiStart(range)
        assertEquals(results.bestByNumComponents().keys, results.generatorByGroupCount.keys)
        val names = setOf(
            JenksPartitionGenerator(data.sortedArray(), range.last).name,
            QuantilePartitionGenerator().name,
            HistogramValleyPartitionGenerator().name
        )
        assertTrue(results.generatorByGroupCount.values.all { it in names }, results.generatorByGroupCount.toString())
    }

    @Test
    fun aSingleGeneratorFitRecordsNoWinner() {
        assertTrue(MixtureModeler(data).fit(range).generatorByGroupCount.isEmpty())
    }
}
