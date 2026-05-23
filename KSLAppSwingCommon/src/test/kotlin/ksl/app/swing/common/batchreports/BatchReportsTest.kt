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

package ksl.app.swing.common.batchreports

import kotlinx.datetime.Instant
import ksl.app.config.ReportFormat
import ksl.app.session.OrchestratorSummary
import ksl.app.session.RunResult
import ksl.utilities.io.dbutil.ExperimentTableData
import ksl.utilities.io.dbutil.SimulationRunTableData
import ksl.utilities.io.dbutil.SimulationSnapshot
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  Focused tests for the `itemNames` filter on
 *  [BatchReports.renderBatchSummary].  The unfiltered path is
 *  exercised in practice by host apps' manual smoke tests; these
 *  tests pin down the filter semantics so the tab panel can rely on
 *  them.
 *
 *  Tests pass `itemTypeNamePlural = "scenarios"` so the assertions
 *  match the same body text the Scenario app produces.  The Experiment
 *  app's variant of this test (when its app lands) can re-use the
 *  same fixture with `itemTypeNamePlural = "design points"`.
 *
 *  HTML / browser-open is skipped by writing only MARKDOWN — the
 *  browser-open path uses `java.awt.Desktop` which raises in headless
 *  builds.
 */
class BatchReportsTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `null itemNames includes every snapshot`() {
        val result = threeItemResult()
        val out = BatchReports.renderBatchSummary(
            result = result,
            outputDir = tempDir,
            formats = setOf(ReportFormat.MARKDOWN),
            itemNames = null,
            itemTypeNamePlural = "scenarios",
            itemColumnHeader = "Scenario"
        )
        assertTrue(out.errors.isEmpty(), "unexpected errors: ${out.errors}")
        val body = Files.readString(out.written.single())
        // Every item appears as a row in the Run Overview table and
        // as a sub-section header in Across-Replication Statistics.
        assertContains(body, "S1")
        assertContains(body, "S2")
        assertContains(body, "S3")
    }

    @Test
    fun `non-null itemNames filters the Run Overview and the per-item sections`() {
        val result = threeItemResult()
        val out = BatchReports.renderBatchSummary(
            result = result,
            outputDir = tempDir,
            formats = setOf(ReportFormat.MARKDOWN),
            itemNames = setOf("S1", "S3"),
            itemTypeNamePlural = "scenarios",
            itemColumnHeader = "Scenario"
        )
        assertTrue(out.errors.isEmpty(), "unexpected errors: ${out.errors}")
        val body = Files.readString(out.written.single())
        assertContains(body, "S1")
        assertContains(body, "S3")
        // S2 must not appear — it was excluded by the filter.  Check the
        // strict standalone form so the assertion can't be satisfied by
        // an incidental "S2" in unrelated text.
        assertFalse(
            body.lineSequence().any { it.contains("| S2 ") || it.endsWith("S2") || it.contains("### S2") },
            "Filtered item 'S2' leaked into the output:\n$body"
        )
        // The filtered count surfaces in the header paragraph.
        assertContains(body, "Report filtered to 2 of 3 scenarios.")
    }

    @Test
    fun `unknown names in itemNames are silently ignored`() {
        val result = threeItemResult()
        val out = BatchReports.renderBatchSummary(
            result = result,
            outputDir = tempDir,
            formats = setOf(ReportFormat.MARKDOWN),
            itemNames = setOf("S1", "DoesNotExist"),
            itemTypeNamePlural = "scenarios",
            itemColumnHeader = "Scenario"
        )
        assertTrue(out.errors.isEmpty())
        val body = Files.readString(out.written.single())
        assertContains(body, "S1")
        // The bogus name is dropped silently — it never reaches the body.
        assertFalse(body.contains("DoesNotExist"))
    }

    @Test
    fun `empty itemNames set surfaces as an error and writes no files`() {
        val result = threeItemResult()
        val out = BatchReports.renderBatchSummary(
            result = result,
            outputDir = tempDir,
            formats = setOf(ReportFormat.MARKDOWN),
            itemNames = emptySet(),
            itemTypeNamePlural = "scenarios",
            itemColumnHeader = "Scenario"
        )
        assertTrue(out.written.isEmpty())
        assertTrue(out.errors.single().contains("No scenarios match"))
    }

    @Test
    fun `itemNames whitelist matching every snapshot omits the filtered-to footer`() {
        val result = threeItemResult()
        val out = BatchReports.renderBatchSummary(
            result = result,
            outputDir = tempDir,
            formats = setOf(ReportFormat.MARKDOWN),
            itemNames = setOf("S1", "S2", "S3"),
            itemTypeNamePlural = "scenarios",
            itemColumnHeader = "Scenario"
        )
        assertTrue(out.errors.isEmpty())
        val body = Files.readString(out.written.single())
        // All three present.
        assertContains(body, "S1"); assertContains(body, "S2"); assertContains(body, "S3")
        // No "filtered to N of M" sentence — the size matched the input.
        assertFalse(
            body.contains("Report filtered to"),
            "Unexpected filtered footer when whitelist matched every item"
        )
    }

    @Test
    fun `renderItemSummary called once per item in a loop writes every file`() {
        // Reproduces the exact pattern the tab panel uses for the
        // per-item generate flow: loop over picked names, call
        // renderItemSummary for each.  If only the first file appears,
        // the substrate has a state-carry bug between calls.
        val result = threeItemResult()
        val written = mutableListOf<java.nio.file.Path>()
        val errors = mutableListOf<String>()
        for (name in listOf("S1", "S2", "S3")) {
            val out = BatchReports.renderItemSummary(
                result = result,
                itemName = name,
                outputDir = tempDir,
                formats = setOf(ReportFormat.MARKDOWN),
                openHtmlInBrowser = false
            )
            written.addAll(out.written)
            errors.addAll(out.errors.map { "[$name] $it" })
        }
        assertTrue(errors.isEmpty(), "unexpected errors: $errors")
        assertEquals(3, written.size, "expected one file per item; got: $written")
        // Each file should be a distinct path.
        assertEquals(3, written.toSet().size, "writes collided: $written")
    }

    @Test
    fun `domain-natural file stems are honoured`() {
        // Sanity check that the new file-stem parameters take effect.
        // Pass an Experiment-app-shaped stem and verify the on-disk
        // filename matches.
        val result = threeItemResult()
        val out = BatchReports.renderItemSummary(
            result = result,
            itemName = "S1",
            outputDir = tempDir,
            formats = setOf(ReportFormat.MARKDOWN),
            openHtmlInBrowser = false,
            itemFileStemPrefix = "item-summary"
        )
        assertEquals(1, out.written.size)
        val name = out.written.single().fileName.toString()
        assertContains(name, "item-summary-S1")
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    private fun threeItemResult(): RunResult.BatchCompleted = RunResult.BatchCompleted(
        summary = OrchestratorSummary(
            runId = "deadbeef-cafe-1234-5678-feedfacef00d",
            orchestratorName = "TestOrchestrator",
            totalItems = 3,
            completedItems = 3,
            failedItems = 0,
            beginTime = Instant.fromEpochMilliseconds(0L),
            endTime = Instant.fromEpochMilliseconds(1000L)
        ),
        snapshots = listOf(
            experimentCompleted("S1", "MM1"),
            experimentCompleted("S2", "MM1"),
            experimentCompleted("S3", "LK")
        )
    )

    private fun experimentCompleted(name: String, model: String): SimulationSnapshot.ExperimentCompleted =
        SimulationSnapshot.ExperimentCompleted(
            simulationRun = SimulationRunTableData(),
            acrossRepStats = emptyList(),
            histograms = emptyList(),
            frequencies = emptyList(),
            timeSeries = emptyList(),
            experiment = ExperimentTableData().apply {
                exp_name = name
                model_name = model
            }
        )
}
