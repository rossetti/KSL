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

package ksl.app.dist.reporting

import ksl.app.dist.config.DataSourceReference
import ksl.app.dist.config.DistributionKind
import ksl.app.dist.config.FitConfiguration
import ksl.app.dist.config.FitSpec
import ksl.app.dist.config.NamedFitConfiguration
import ksl.app.dist.runner.BatchFittingRunner
import ksl.utilities.io.report.renderer.RenderContext
import ksl.utilities.io.report.toText
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BatchFitReportTest {

    private fun exponentialSample(mean: Double, n: Int, streamNumber: Int): DoubleArray {
        val rv = ExponentialRV(mean = mean, streamNum = streamNumber)
        return DoubleArray(n) { rv.value }
    }

    private val estimators = setOf("exponential-mle", "weibull-mle", "gamma-mle")

    private fun batchResult() = BatchFittingRunner.run(
        BatchFittingRunner.expand(
            FitConfiguration(
                dataSource = DataSourceReference.Inline(
                    linkedMapOf("alpha" to exponentialSample(1.0, 150, 21), "beta" to exponentialSample(2.0, 150, 22))
                ),
                estimatorIds = estimators
            )
        )
    )

    private fun tempContext(): RenderContext {
        val dir = Files.createTempDirectory("batch-report")
        return RenderContext(outputDir = dir, plotDir = dir)
    }

    @Test
    fun `cross-dataset summary lists one row per dataset`() {
        val text = batchResult().toDocument().toText(tempContext())
        assertTrue(text.contains("Cross-Dataset Summary"))
        assertTrue(text.contains("Recommended Distribution by Dataset"))
        assertTrue(text.contains("alpha"))
        assertTrue(text.contains("beta"))
        // summary only by default — no per-dataset detail sections
        assertFalse(text.contains("Dataset: alpha"))
    }

    @Test
    fun `include per dataset appends detail sections`() {
        val text = batchResult().toDocument(includePerDataset = true).toText(tempContext())
        assertTrue(text.contains("Dataset: alpha"))
        assertTrue(text.contains("Dataset: beta"))
        assertTrue(text.contains("Fitted Distributions"))
        assertTrue(text.contains("Goodness of Fit"))
    }

    @Test
    fun `failed datasets are listed in the summary`() {
        val good = NamedFitConfiguration(
            name = "good",
            config = FitConfiguration(
                dataSource = DataSourceReference.Inline(mapOf("good" to exponentialSample(1.0, 150, 23))),
                estimatorIds = estimators
            )
        )
        val bad = NamedFitConfiguration(
            name = "bad",
            config = FitConfiguration(
                dataSource = DataSourceReference.Inline(mapOf("bad" to exponentialSample(1.0, 50, 24))),
                kind = DistributionKind.DISCRETE,
                estimatorIds = setOf("poisson-mle")
            )
        )
        val result = BatchFittingRunner.run(FitSpec.Batch(listOf(good, bad)))
        val text = result.toDocument().toText(tempContext())
        assertTrue(text.contains("Failed Datasets"))
        assertTrue(text.contains("bad"))
    }
}
