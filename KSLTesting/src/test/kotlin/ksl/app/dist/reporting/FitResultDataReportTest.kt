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
import ksl.app.dist.config.FitConfiguration
import ksl.app.dist.result.FitResultData
import ksl.app.dist.runner.FittingRunner
import ksl.utilities.io.report.renderer.RenderContext
import ksl.utilities.io.report.toText
import ksl.utilities.io.report.writeHtml
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the DTO-driven reporting path: a `FitResultData` (built by the
 * runner) renders to a document using only the DTO plus the client's raw
 * data, with no live engine objects. Plots appear only when raw data is
 * supplied; a thin client (no data) gets tables only.
 */
class FitResultDataReportTest {

    private fun exponentialSample(mean: Double, n: Int, streamNumber: Int): DoubleArray {
        val rv = ExponentialRV(mean = mean, streamNum = streamNumber)
        return DoubleArray(n) { rv.value }
    }

    private data class Fixture(val data: DoubleArray, val report: FitResultData)

    private fun fixture(
        bootstrap: BootstrapConfig? = null,
        streamNumber: Int = 61
    ): Fixture {
        val data = exponentialSample(mean = 2.0, n = 300, streamNumber = streamNumber)
        val config = FitConfiguration(
            dataSource = DataSourceReference.Inline(mapOf("expo" to data)),
            estimatorIds = setOf("exponential-mle", "weibull-mle", "gamma-mle"),
            bootstrap = bootstrap
        )
        return Fixture(data, FittingRunner.fit(config))
    }

    private fun tempContext(): RenderContext {
        val dir = Files.createTempDirectory("fitreport-r3")
        return RenderContext(outputDir = dir, plotDir = dir)
    }

    @Test
    fun `document without raw data renders all tables and no plots`() {
        val text = fixture().report.toDocument(rawData = null).toText(tempContext())
        assertTrue(text.contains("Data Summary"), "missing data summary")
        assertTrue(text.contains("Fitted Distributions"), "missing fit ranking")
        assertTrue(text.contains("MODA Scoring"), "missing MODA section")
        assertTrue(text.contains("Goodness of Fit"), "missing GoF section")
        assertFalse(text.contains("[Plot saved"), "thin client should render no plots")
    }

    @Test
    fun `document with raw data renders plots into the plot directory`() {
        val (data, report) = fixture()
        val ctx = tempContext()
        val text = report.toDocument(rawData = data).toText(ctx)
        assertTrue(text.contains("[Plot saved"), "expected reconstructed fit plots")
        val plotFiles = Files.list(ctx.plotDir).use { s -> s.filter { Files.isRegularFile(it) }.count() }
        assertTrue(plotFiles > 0, "no plot image files were written")
    }

    @Test
    fun `document writes a non-empty HTML file containing the recommendation`() {
        val (data, report) = fixture()
        val file = report.toDocument(rawData = data).writeHtml(ctx = tempContext())
        assertTrue(file.exists() && file.length() > 0, "HTML not written / empty")
        val html = file.readText()
        assertTrue(html.contains("expo"), "dataset name missing from HTML")
        assertTrue(html.contains("Goodness of Fit"), "GoF section missing from HTML")
    }

    @Test
    fun `bootstrap table appears only when bootstrap was requested`() {
        val withBoot = fixture(bootstrap = BootstrapConfig(sampleSize = 50, level = 0.95, streamNumber = 1))
        val withText = withBoot.report.toDocument(rawData = withBoot.data).toText(tempContext())
        assertTrue(withText.contains("Bootstrap Parameter Estimates"), "bootstrap table missing when requested")

        val noBoot = fixture()
        val noText = noBoot.report.toDocument(rawData = noBoot.data).toText(tempContext())
        assertFalse(noText.contains("Bootstrap Parameter Estimates"), "bootstrap table present when not requested")
    }

    @Test
    fun `summary document is plot-free and byte-deterministic`() {
        val report = fixture().report
        val ctx = tempContext()
        val a = report.toSummaryDocument().toText(ctx)
        val b = report.toSummaryDocument().toText(ctx)
        assertEquals(a, b)
        assertFalse(a.contains("[Plot saved"), "summary must be plot-free")
        assertTrue(a.contains("MODA Scoring"))
    }

    @Test
    fun `MODA table alternatives match fit display names`() {
        val report = fixture().report
        val moda = requireNotNull(report.scoring)
        val displayNames = report.fits.map { it.displayName }.toSet()
        assertTrue(moda.scores.all { it.alternative in displayNames }, "score alternative not a known fit")
        assertTrue(moda.values.all { it.alternative in displayNames }, "value alternative not a known fit")
    }

    @Test
    fun `shifted data still reconstructs plots without error`() {
        // Shift values away from zero so automatic shifting engages, exercising
        // the reconstruct-with-shift branch.
        val base = exponentialSample(mean = 2.0, n = 300, streamNumber = 71)
        val shifted = DoubleArray(base.size) { base[it] + 50.0 }
        val config = FitConfiguration(
            dataSource = DataSourceReference.Inline(mapOf("shifted" to shifted)),
            estimatorIds = setOf("exponential-mle", "weibull-mle", "gamma-mle")
        )
        val report = FittingRunner.fit(config)
        val ctx = tempContext()
        // Should not throw, and should render (plots present for the recommended fit).
        val text = report.toDocument(rawData = shifted).toText(ctx)
        assertTrue(text.contains("Data Summary"))
        assertTrue(text.contains("Distribution Fit Plots") || text.contains("[Plot saved"))
    }
}
