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

package ksl.app.results.comparison

import ksl.app.comparison.ComparisonSelectionModel
import ksl.app.comparison.InMemoryComparisonSource
import ksl.app.comparison.ResponseCategory

import ksl.app.config.ReportFormat
import ksl.utilities.io.report.extensions.MCBDirection
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  Black-box tests for [ComparisonReportRenderer].  Each test drives
 *  one of the per-analysis renderers with a synthetic observation
 *  map, asserts the right files appear in the temp dir, and verifies
 *  the renderer's own pre-flight validation rejects invalid inputs
 *  without writing partial files.
 *
 *  Note: HTML writes also try to open the result in a browser via
 *  java.awt.Desktop.  In a headless build environment this raises
 *  an UnsupportedOperationException which the renderer catches and
 *  surfaces as a non-fatal `errors` entry.  The tests therefore use
 *  MARKDOWN+TEXT (no browser path) so they pass on every harness.
 */
class ComparisonReportRendererTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `box plot writes one file per format with stable stem`() {
        val model = mm1Selection()
        val out = ComparisonReportRenderer.renderBoxPlot(
            sourceLabel = sourceLabel(model),
            responseName = "NumBusy",
            observations = model.gatherObservationsFor("NumBusy"),
            outputDir = tempDir,
            formats = setOf(ReportFormat.MARKDOWN, ReportFormat.TEXT)
        )
        assertTrue(out.errors.isEmpty(), "unexpected errors: ${out.errors}")
        assertEquals(2, out.written.size)
        val names = out.written.map { it.fileName.toString() }.sorted()
        assertEquals(
            listOf("comparison-boxplot-NumBusy.md", "comparison-boxplot-NumBusy.txt"),
            names
        )
        for (p in out.written) assertTrue(Files.exists(p))
    }

    @Test
    fun `box plot honors caption override`() {
        val model = mm1Selection()
        val out = ComparisonReportRenderer.renderBoxPlot(
            sourceLabel = sourceLabel(model),
            responseName = "NumBusy",
            observations = model.gatherObservationsFor("NumBusy"),
            outputDir = tempDir,
            formats = setOf(ReportFormat.MARKDOWN),
            caption = "Custom caption text"
        )
        assertTrue(out.errors.isEmpty(), "unexpected errors: ${out.errors}")
        val body = Files.readString(out.written.single())
        assertTrue(body.contains("Custom caption text"), "caption missing from rendered markdown")
    }

    @Test
    fun `box plot fails fast when formats empty`() {
        val model = mm1Selection()
        val out = ComparisonReportRenderer.renderBoxPlot(
            sourceLabel = sourceLabel(model),
            responseName = "NumBusy",
            observations = model.gatherObservationsFor("NumBusy"),
            outputDir = tempDir,
            formats = emptySet()
        )
        assertTrue(out.written.isEmpty())
        assertFalse(out.errors.isEmpty())
    }

    @Test
    fun `box plot fails fast when no observations`() {
        val model = mm1Selection()
        val out = ComparisonReportRenderer.renderBoxPlot(
            sourceLabel = sourceLabel(model),
            responseName = "DoesNotExist",
            observations = model.gatherObservationsFor("DoesNotExist"),
            outputDir = tempDir,
            formats = setOf(ReportFormat.MARKDOWN)
        )
        assertTrue(out.written.isEmpty())
        assertTrue(out.errors.single().contains("No checked experiment"))
    }

    @Test
    fun `mca writes one file per format`() {
        val model = mm1Selection()
        val out = ComparisonReportRenderer.renderMca(
            sourceLabel = sourceLabel(model),
            responseName = "NumBusy",
            observations = model.gatherObservationsFor("NumBusy"),
            outputDir = tempDir,
            formats = setOf(ReportFormat.MARKDOWN)
        )
        assertTrue(out.errors.isEmpty(), "unexpected errors: ${out.errors}")
        assertEquals(1, out.written.size)
        assertEquals("comparison-mca-NumBusy.md", out.written.single().fileName.toString())
    }

    @Test
    fun `mca honors title override`() {
        val model = mm1Selection()
        val out = ComparisonReportRenderer.renderMca(
            sourceLabel = sourceLabel(model),
            responseName = "NumBusy",
            observations = model.gatherObservationsFor("NumBusy"),
            outputDir = tempDir,
            formats = setOf(ReportFormat.MARKDOWN),
            title = "Custom MCA title"
        )
        assertTrue(out.errors.isEmpty(), "unexpected errors: ${out.errors}")
        val body = Files.readString(out.written.single())
        assertTrue(body.contains("Custom MCA title"), "title missing from rendered markdown")
    }

    @Test
    fun `mca honors direction and indifference zone`() {
        // Sanity check that the renderer accepts the configurable knobs
        // without choking; substrate covers the statistical correctness.
        val model = mm1Selection()
        val out = ComparisonReportRenderer.renderMca(
            sourceLabel = sourceLabel(model),
            responseName = "NumBusy",
            observations = model.gatherObservationsFor("NumBusy"),
            outputDir = tempDir,
            formats = setOf(ReportFormat.MARKDOWN),
            direction = MCBDirection.MAX,
            indifferenceZone = 0.05,
            altConfidenceLevel = 0.90,
            diffConfidenceLevel = 0.90,
            probCorrectSelection = 0.90
        )
        assertTrue(out.errors.isEmpty(), "unexpected errors: ${out.errors}")
        assertEquals(1, out.written.size)
    }

    @Test
    fun `mca fails fast when formats empty`() {
        val model = mm1Selection()
        val out = ComparisonReportRenderer.renderMca(
            sourceLabel = sourceLabel(model),
            responseName = "NumBusy",
            observations = model.gatherObservationsFor("NumBusy"),
            outputDir = tempDir,
            formats = emptySet()
        )
        assertTrue(out.written.isEmpty())
        assertFalse(out.errors.isEmpty())
    }

    @Test
    fun `ci plot writes one file per format`() {
        val model = mm1Selection()
        val out = ComparisonReportRenderer.renderCiPlot(
            sourceLabel = sourceLabel(model),
            responseName = "NumBusy",
            observations = model.gatherObservationsFor("NumBusy"),
            outputDir = tempDir,
            formats = setOf(ReportFormat.MARKDOWN)
        )
        assertTrue(out.errors.isEmpty(), "unexpected errors: ${out.errors}")
        assertEquals(1, out.written.size)
        assertEquals("comparison-ciplot-NumBusy.md", out.written.single().fileName.toString())
    }

    @Test
    fun `ci plot honors level reference point title and caption`() {
        val model = mm1Selection()
        val out = ComparisonReportRenderer.renderCiPlot(
            sourceLabel = sourceLabel(model),
            responseName = "NumBusy",
            observations = model.gatherObservationsFor("NumBusy"),
            outputDir = tempDir,
            formats = setOf(ReportFormat.MARKDOWN),
            level = 0.90,
            referencePoint = 0.5,
            caption = "Custom CI caption",
            title = "Custom CI title"
        )
        assertTrue(out.errors.isEmpty(), "unexpected errors: ${out.errors}")
        val body = Files.readString(out.written.single())
        assertTrue(body.contains("Custom CI title"), "title missing")
        assertTrue(body.contains("Custom CI caption"), "caption missing")
    }

    @Test
    fun `ci plot fails fast on too few observations`() {
        val src = InMemoryComparisonSource.builder("singleton").apply {
            experiment("S1", model = "MM1") {
                response("NumBusy", ResponseCategory.TIME_WEIGHTED, doubleArrayOf(0.5))
            }
        }.build()
        val model = ComparisonSelectionModel(listOf(src)).apply { selectAll() }
        val out = ComparisonReportRenderer.renderCiPlot(
            sourceLabel = sourceLabel(model),
            responseName = "NumBusy",
            observations = model.gatherObservationsFor("NumBusy"),
            outputDir = tempDir,
            formats = setOf(ReportFormat.MARKDOWN)
        )
        assertTrue(out.written.isEmpty())
        assertTrue(out.errors.single().contains("at least 2 replications"))
    }

    @Test
    fun `ci plot fails fast when formats empty`() {
        val model = mm1Selection()
        val out = ComparisonReportRenderer.renderCiPlot(
            sourceLabel = sourceLabel(model),
            responseName = "NumBusy",
            observations = model.gatherObservationsFor("NumBusy"),
            outputDir = tempDir,
            formats = emptySet()
        )
        assertTrue(out.written.isEmpty())
        assertFalse(out.errors.isEmpty())
    }

    @Test
    fun `mca rejects unequal replication counts before writing anything`() {
        val src = InMemoryComparisonSource.builder("uneven").apply {
            experiment("S1", model = "MM1") {
                response("NumBusy", ResponseCategory.TIME_WEIGHTED, doubleArrayOf(0.5, 0.6, 0.7))
            }
            experiment("S2", model = "MM1") {
                response("NumBusy", ResponseCategory.TIME_WEIGHTED, doubleArrayOf(0.5, 0.6))
            }
        }.build()
        val model = ComparisonSelectionModel(listOf(src)).apply { selectAll() }
        val out = ComparisonReportRenderer.renderMca(
            sourceLabel = sourceLabel(model),
            responseName = "NumBusy",
            observations = model.gatherObservationsFor("NumBusy"),
            outputDir = tempDir,
            formats = setOf(ReportFormat.MARKDOWN)
        )
        assertTrue(out.written.isEmpty())
        assertTrue(out.errors.single().contains("equal replication counts"))
    }

    @Test
    fun `file stems sanitise filesystem-unsafe characters in response names`() {
        val src = InMemoryComparisonSource.builder("punct").apply {
            experiment("S1", model = "MM1") {
                response("System Time / Sec", ResponseCategory.OBSERVATION, doubleArrayOf(1.0, 2.0))
            }
            experiment("S2", model = "MM1") {
                response("System Time / Sec", ResponseCategory.OBSERVATION, doubleArrayOf(3.0, 4.0))
            }
        }.build()
        val model = ComparisonSelectionModel(listOf(src)).apply { selectAll() }
        val out = ComparisonReportRenderer.renderBoxPlot(
            sourceLabel = sourceLabel(model),
            responseName = "System Time / Sec",
            observations = model.gatherObservationsFor("System Time / Sec"),
            outputDir = tempDir,
            formats = setOf(ReportFormat.MARKDOWN)
        )
        val name = out.written.single().fileName.toString()
        // Spaces and the slash collapse to underscores.
        assertTrue(name.startsWith("comparison-boxplot-System"))
        assertTrue(name.endsWith(".md"))
        assertFalse(name.contains("/"))
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    private fun sourceLabel(model: ComparisonSelectionModel): String =
        model.sources.joinToString(" · ") { it.sourceLabel }

    private fun mm1Selection(): ComparisonSelectionModel {
        val src = InMemoryComparisonSource.builder("two-mm1").apply {
            experiment("S1", model = "MM1") {
                response("NumBusy", ResponseCategory.TIME_WEIGHTED, doubleArrayOf(0.5, 0.6, 0.7))
            }
            experiment("S2", model = "MM1") {
                response("NumBusy", ResponseCategory.TIME_WEIGHTED, doubleArrayOf(0.8, 0.9, 1.0))
            }
        }.build()
        return ComparisonSelectionModel(listOf(src)).apply { selectAll() }
    }
}
