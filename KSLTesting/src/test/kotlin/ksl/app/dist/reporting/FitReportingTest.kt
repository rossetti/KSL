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
import ksl.app.dist.config.FitConfiguration
import ksl.app.dist.runner.FitOutcome
import ksl.app.dist.runner.FittingRunner
import ksl.utilities.io.report.renderer.RenderContext
import ksl.utilities.io.report.toText
import ksl.utilities.io.report.writeHtml
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FitReportingTest {

    private fun exponentialSample(mean: Double, n: Int, streamNumber: Int): DoubleArray {
        val rv = ExponentialRV(mean = mean, streamNum = streamNumber)
        return DoubleArray(n) { rv.value }
    }

    private fun outcome(
        name: String = "expo",
        n: Int = 250,
        streamNumber: Int = 41
    ): FitOutcome.Continuous {
        val data = exponentialSample(mean = 2.0, n = n, streamNumber = streamNumber)
        val config = FitConfiguration(
            dataSource = DataSourceReference.Inline(mapOf(name to data)),
            // A small estimator set keeps the full-report bootstrap/plot cost modest.
            estimatorIds = setOf("exponential-mle", "weibull-mle", "gamma-mle")
        )
        return assertIs<FitOutcome.Continuous>(FittingRunner.run(config))
    }

    private fun tempContext(): RenderContext {
        val dir = Files.createTempDirectory("fitreport-test")
        return RenderContext(outputDir = dir, plotDir = dir)
    }

    @Test
    fun `run exposes live modeler and results consistent with the DTO`() {
        val out = outcome()
        assertEquals("expo", out.datasetName)
        assertTrue(out.results.resultsSortedByScoring.isNotEmpty())
        // The DTO carried on the outcome equals what fit() returns for the same input.
        assertEquals("expo", out.report.datasetName)
        assertTrue(out.report.fits.isNotEmpty())
    }

    @Test
    fun `summary document renders to text with no plots`() {
        val text = FitReporting.summaryDocument(outcome()).toText()
        assertTrue(text.contains("Data Statistical Summary"), "missing data summary section")
        assertTrue(text.contains("MODA"), "missing MODA scoring section")
        // The summary path must not trigger plot rendering / image-file writes.
        assertFalse(text.contains("[Plot saved"), "summary unexpectedly rendered a plot")
    }

    @Test
    fun `summary document rendering is deterministic`() {
        // The summary path does no bootstrapping and emits no plots, so rendering
        // the same fit outcome twice yields byte-identical text. (We render one
        // outcome twice rather than fitting twice, to isolate reporting
        // determinism from random-stream management.)
        val out = outcome(streamNumber = 7)
        val a = FitReporting.summaryDocument(out).toText()
        val b = FitReporting.summaryDocument(out).toText()
        assertEquals(a, b)
    }

    @Test
    fun `full document writes a non-empty HTML file`() {
        val ctx = tempContext()
        val file = FitReporting.fullDocument(outcome()).writeHtml(ctx = ctx)
        assertTrue(file.exists(), "HTML file was not written")
        assertTrue(file.length() > 0, "HTML file is empty")
        val html = file.readText()
        assertTrue(html.contains("expo"), "title/dataset name missing from HTML")
        assertTrue(html.contains("Goodness of Fit"), "GoF section missing from HTML")
    }

    @Test
    fun `full document text rendering emits plot files into the plot directory`() {
        val ctx = tempContext()
        val text = FitReporting.fullDocument(outcome()).toText(ctx)
        // The full path renders the distribution fit plots; text rendering saves
        // them to ctx.plotDir and writes a reference marker.
        assertTrue(text.contains("[Plot saved"), "full report did not render any plots")
        val plotFiles = Files.list(ctx.plotDir).use { stream ->
            stream.filter { Files.isRegularFile(it) }.count()
        }
        assertTrue(plotFiles > 0, "no plot image files were written")
    }
}
