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
import ksl.app.dist.runner.FittingRunner
import ksl.utilities.io.report.renderer.RenderContext
import ksl.utilities.io.report.toText
import ksl.utilities.random.rvariable.PoissonRV
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The DTO-driven report renders a discrete result without special-casing:
 * the MODA section is omitted (scoring is null), the goodness-of-fit section
 * shows the dispersion statistics, and plot reconstruction no-ops (the
 * continuous distribution factory does not build discrete distributions).
 */
class DiscreteFitReportTest {

    private fun poissonReport() = FittingRunner.fit(
        FitConfiguration(
            dataSource = DataSourceReference.Inline(
                mapOf("counts" to DoubleArray(400) { PoissonRV(mean = 5.0, streamNum = 93).value })
            ),
            kind = DistributionKind.DISCRETE
        )
    )

    private fun tempContext(): RenderContext {
        val dir = Files.createTempDirectory("discrete-report")
        return RenderContext(outputDir = dir, plotDir = dir)
    }

    @Test
    fun `discrete document renders GoF with dispersion and omits MODA`() {
        val report = poissonReport()
        val ctx = tempContext()
        // Provide rawData; for discrete, plot reconstruction is a no-op.
        val data = report.fits // just to ensure report built
        val text = report.toDocument(rawData = DoubleArray(0)).toText(ctx)
        assertTrue(text.contains("Data Summary"))
        assertTrue(text.contains("Fitted Distributions"))
        assertTrue(text.contains("Goodness of Fit"))
        assertTrue(text.contains("Index of Dispersion"))
        assertTrue(text.contains("Poisson Variance Test"))
        // No MODA scoring section for discrete results.
        assertFalse(text.contains("MODA Scoring"))
        // Plot reconstruction does not apply to discrete fits.
        assertFalse(text.contains("[Plot saved"))
        assertTrue(data.isNotEmpty())
    }
}
