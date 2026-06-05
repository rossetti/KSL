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

import ksl.app.dist.config.BootstrapConfig
import ksl.app.dist.config.DataSourceReference
import ksl.app.dist.config.DistributionKind
import ksl.app.dist.config.FitConfiguration
import ksl.app.dist.runner.FittingRunner
import ksl.utilities.io.report.toText
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.PoissonRV
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves that a serializable [ksl.app.dist.result.FitResultData] (plus the
 * client's raw data) renders the *canonical* engine report via the DTO
 * adapters — the same report extensions a live PDFModeler uses — and that the
 * render is deterministic.
 *
 * Byte-for-byte equality against a live render is intentionally NOT asserted:
 * the live report build recomputes the bootstrap CIs every render (a stochastic
 * quantity), whereas the DTO adapters serve carried snapshots. The two
 * guarantees that matter are asserted instead: (1) the canonical section
 * structure is produced via the canonical extensions, and (2) the DTO render is
 * reproducible.
 */
class FitResultDataCanonicalRenderTest {

    private fun continuousData(): DoubleArray {
        val rv = ExponentialRV(mean = 2.5, streamNum = 21)
        return DoubleArray(400) { rv.value }
    }

    private fun fitWithBootstrap(name: String, data: DoubleArray) =
        FittingRunner.fit(
            FitConfiguration(
                dataSource = DataSourceReference.Inline(mapOf(name to data)),
                bootstrap = BootstrapConfig(sampleSize = 200, level = 0.95, streamNumber = 7)
            )
        )

    @Test
    fun `continuous canonical render is deterministic`() {
        val data = continuousData()
        val result = fitWithBootstrap("expo", data)
        val first = result.toCanonicalDocument(data).toText()
        val second = result.toCanonicalDocument(data).toText()
        assertEquals(first, second, "DTO canonical render must be reproducible (no in-render bootstrap)")
    }

    @Test
    fun `continuous canonical render uses the standard report extensions`() {
        val data = continuousData()
        val result = fitWithBootstrap("expo", data)
        val text = result.toCanonicalDocument(data, allGoodnessOfFit = false).toText()
        // Section titles only the canonical extensions emit (the DTO table
        // renderer never produces these), proving the standard extensions drive
        // the render rather than the hand-rolled tables.
        listOf(
            "Box Plot Summary", "Data Visualization", "Observations", "Autocorrelation",
            "Scores and Values", "Rankings", "Goodness of Fit Tests"
        ).forEach { assertTrue(text.contains(it), "canonical render missing section: '$it'") }
        // The carried bootstrap snapshot is rendered (not recomputed).
        assertTrue(text.contains("Bootstrap Parameter Estimates"), "bootstrap section missing")
    }

    private fun discreteData(): DoubleArray {
        val rv = PoissonRV(mean = 5.0, streamNum = 22)
        return DoubleArray(300) { rv.value }
    }

    private fun fitDiscrete(name: String, data: DoubleArray) =
        FittingRunner.fit(
            FitConfiguration(
                dataSource = DataSourceReference.Inline(mapOf(name to data)),
                kind = DistributionKind.DISCRETE
            )
        )

    @Test
    fun `discrete canonical render is deterministic`() {
        val data = discreteData()
        val result = fitDiscrete("pois", data)
        assertEquals(DistributionKind.DISCRETE, result.kind)
        val first = result.toCanonicalDocument(data).toText()
        val second = result.toCanonicalDocument(data).toText()
        assertEquals(first, second, "discrete DTO canonical render must be reproducible")
    }

    @Test
    fun `discrete canonical render uses the standard discrete extensions`() {
        val data = discreteData()
        val result = fitDiscrete("pois", data)
        val text = result.toCanonicalDocument(data).toText()
        // Section titles only the canonical discrete extensions emit (the DTO
        // table renderer never produces these).
        listOf(
            "Discrete Data Summary", "Discrete Data Visualization",
            "Dispersion Tests", "Distribution Comparison"
        ).forEach { assertTrue(text.contains(it), "discrete canonical render missing section: '$it'") }
    }
}
