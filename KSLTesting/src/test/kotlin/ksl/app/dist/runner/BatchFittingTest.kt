/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.app.dist.runner

import kotlinx.serialization.json.Json
import ksl.app.dist.config.DataSourceReference
import ksl.app.dist.config.DistributionKind
import ksl.app.dist.config.FitConfiguration
import ksl.app.dist.config.FitSpec
import ksl.app.dist.config.NamedFitConfiguration
import ksl.app.dist.result.BatchFitResultData
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.PoissonRV
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BatchFittingTest {

    private fun exponentialSample(mean: Double, n: Int, streamNumber: Int): DoubleArray {
        val rv = ExponentialRV(mean = mean, streamNum = streamNumber)
        return DoubleArray(n) { rv.value }
    }

    private fun poissonSample(mean: Double, n: Int, streamNumber: Int): DoubleArray {
        val rv = PoissonRV(mean = mean, streamNum = streamNumber)
        return DoubleArray(n) { rv.value }
    }

    private val continuousEstimators = setOf("exponential-mle", "weibull-mle", "gamma-mle")

    @Test
    fun `expand turns a multi-dataset source into one entry per dataset`() {
        val config = FitConfiguration(
            dataSource = DataSourceReference.Inline(
                linkedMapOf(
                    "a" to exponentialSample(1.0, 150, 1),
                    "b" to exponentialSample(2.0, 150, 2),
                    "c" to exponentialSample(3.0, 150, 3)
                )
            ),
            estimatorIds = continuousEstimators
        )
        val batch = BatchFittingRunner.expand(config)
        assertEquals(listOf("a", "b", "c"), batch.configs.map { it.name })
        // each expanded entry is a single-dataset Inline source
        batch.configs.forEach {
            val src = it.config.dataSource
            assertTrue(src is DataSourceReference.Inline && src.datasets.size == 1)
        }
    }

    @Test
    fun `run fits every dataset in order`() {
        val config = FitConfiguration(
            dataSource = DataSourceReference.Inline(
                linkedMapOf(
                    "a" to exponentialSample(1.0, 150, 4),
                    "b" to exponentialSample(2.0, 150, 5)
                )
            ),
            estimatorIds = continuousEstimators
        )
        val result = BatchFittingRunner.run(BatchFittingRunner.expand(config))
        assertEquals(listOf("a", "b"), result.results.map { it.datasetName })
        assertTrue(result.failures.isEmpty())
        assertTrue(result.results.all { it.fits.isNotEmpty() })
    }

    @Test
    fun `per-dataset failure is captured without aborting the batch`() {
        val good = NamedFitConfiguration(
            name = "good",
            config = FitConfiguration(
                dataSource = DataSourceReference.Inline(mapOf("good" to exponentialSample(1.0, 150, 6))),
                estimatorIds = continuousEstimators
            )
        )
        // Discrete kind on non-integer data fails at run time (integer check).
        val bad = NamedFitConfiguration(
            name = "bad",
            config = FitConfiguration(
                dataSource = DataSourceReference.Inline(mapOf("bad" to exponentialSample(1.0, 50, 7))),
                kind = DistributionKind.DISCRETE,
                estimatorIds = setOf("poisson-mle")
            )
        )
        val result = BatchFittingRunner.run(FitSpec.Batch(listOf(good, bad)))
        assertEquals(listOf("good"), result.results.map { it.datasetName })
        assertEquals(1, result.failures.size)
        assertEquals("bad", result.failures[0].name)
    }

    @Test
    fun `heterogeneous batch mixes continuous and discrete results`() {
        val cont = NamedFitConfiguration(
            name = "cont",
            config = FitConfiguration(
                dataSource = DataSourceReference.Inline(mapOf("cont" to exponentialSample(2.0, 200, 8))),
                estimatorIds = continuousEstimators
            )
        )
        val disc = NamedFitConfiguration(
            name = "disc",
            config = FitConfiguration(
                dataSource = DataSourceReference.Inline(mapOf("disc" to poissonSample(5.0, 200, 9))),
                kind = DistributionKind.DISCRETE
            )
        )
        val result = BatchFittingRunner.run(FitSpec.Batch(listOf(cont, disc)))
        assertTrue(result.failures.isEmpty())
        assertEquals(
            setOf(DistributionKind.CONTINUOUS, DistributionKind.DISCRETE),
            result.results.map { it.kind }.toSet()
        )
    }

    @Test
    fun `batch result round-trips through JSON`() {
        val config = FitConfiguration(
            dataSource = DataSourceReference.Inline(
                linkedMapOf("a" to exponentialSample(1.0, 150, 10), "b" to exponentialSample(2.0, 150, 11))
            ),
            estimatorIds = continuousEstimators
        )
        val result = BatchFittingRunner.run(BatchFittingRunner.expand(config))
        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(BatchFitResultData.serializer(), result)
        val decoded = json.decodeFromString(BatchFitResultData.serializer(), encoded)
        assertEquals(result, decoded)
    }
}
