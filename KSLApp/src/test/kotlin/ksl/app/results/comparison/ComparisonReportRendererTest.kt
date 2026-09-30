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
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ksl.testutils.DisabledIfHeadless

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
    @DisabledIfHeadless
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
        // The stem now carries a hash of what identifies the analysis, so it is matched by shape
        // rather than spelled out -- pinning the hash would be pinning an implementation detail.
        // What matters and is asserted: one stem, two extensions, and it is stable (below).
        assertTrue(names.all { it.startsWith("comparison-boxplot-NumBusy-") }, "unexpected names: $names")
        assertEquals(listOf("md", "txt"), names.map { it.substringAfterLast('.') })
        assertEquals(1, names.map { it.substringBeforeLast('.') }.distinct().size, "one stem: $names")
        for (p in out.written) assertTrue(Files.exists(p))
    }

    @Test
    @DisabledIfHeadless
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
    @DisabledIfHeadless
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
        val mcaName = out.written.single().fileName.toString()
        // d0 is the default indifference zone, l0.95 the difference level: the two parameters a
        // reader compares runs by are spelled out, the rest is hashed. See fileStem.
        assertTrue(
            mcaName.startsWith("comparison-mca-NumBusy-d0-l0.95-") && mcaName.endsWith(".md"),
            "unexpected name: $mcaName"
        )
    }

    @Test
    @DisplayName("two indifference zones produce two reports, and the first survives")
    fun differentDeltasDoNotOverwriteEachOther() {
        val model = mm1Selection()
        fun render(delta: Double) = ComparisonReportRenderer.renderMca(
            sourceLabel = sourceLabel(model),
            responseName = "NumBusy",
            observations = model.gatherObservationsFor("NumBusy"),
            outputDir = tempDir,
            formats = setOf(ReportFormat.MARKDOWN),
            indifferenceZone = delta,
        )

        val first = render(0.0)
        val second = render(0.5)

        assertTrue(first.errors.isEmpty() && second.errors.isEmpty(), "unexpected errors")
        val a = first.written.single()
        val b = second.written.single()

        // The defect this closes: both calls used to land on comparison-mca-NumBusy.md, so the second
        // overwrote the first at the same URL with nothing on the page saying it had changed -- and the
        // tools tell the agent to hand that URL to the user.
        assertTrue(a != b, "a different indifference zone must be a different file: $a")
        assertTrue(Files.exists(a), "the first report must still exist after the second render")
        assertTrue(Files.exists(b))
        assertTrue(a.fileName.toString().contains("-d0-"), "the delta is visible in the name: $a")
        assertTrue(b.fileName.toString().contains("-d0.5-"), "and distinguishes them: $b")

        // Neither write replaced anything, which is what `replaced` exists to report.
        assertTrue(first.replaced.isEmpty() && second.replaced.isEmpty(), "nothing should be overwritten")
    }

    @Test
    @DisplayName("the same analysis rendered twice reuses its file and says it replaced it")
    fun sameAnalysisIsIdempotentAndReportsReplacement() {
        val model = mm1Selection()
        fun render() = ComparisonReportRenderer.renderMca(
            sourceLabel = sourceLabel(model),
            responseName = "NumBusy",
            observations = model.gatherObservationsFor("NumBusy"),
            outputDir = tempDir,
            formats = setOf(ReportFormat.MARKDOWN),
        )

        val first = render()
        val second = render()

        // Stability matters as much as distinctness: an identical analysis must not accumulate files,
        // or a caching layer keyed on the URL would miss every time.
        assertEquals(first.written.single(), second.written.single(), "the same analysis is the same file")
        assertTrue(first.replaced.isEmpty(), "the first write created the file")
        assertEquals(listOf(second.written.single()), second.replaced, "the second overwrote it, and says so")
    }

    @Test
    @DisabledIfHeadless
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
    @DisabledIfHeadless
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
    @DisabledIfHeadless
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
        val ciName = out.written.single().fileName.toString()
        assertTrue(
            ciName.startsWith("comparison-ciplot-NumBusy-l0.95-") && ciName.endsWith(".md"),
            "unexpected name: $ciName"
        )
    }

    @Test
    @DisabledIfHeadless
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
    @DisabledIfHeadless
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
