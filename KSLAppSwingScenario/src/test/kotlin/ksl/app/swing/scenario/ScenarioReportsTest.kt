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

package ksl.app.swing.scenario

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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  Focused tests for the `scenarioNames` filter on
 *  [ScenarioReports.renderScenarioSummaries].  The unfiltered path is
 *  exercised in practice by the Scenario app's manual smoke tests;
 *  these tests pin down the filter semantics so the new
 *  `ScenarioReportDialog` work can rely on them.
 *
 *  HTML / browser-open is skipped by writing only MARKDOWN — the
 *  browser-open path uses `java.awt.Desktop` which raises in headless
 *  builds.
 */
class ScenarioReportsTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `null scenarioNames includes every snapshot`() {
        val result = threeScenarioResult()
        val out = ScenarioReports.renderScenarioSummaries(
            result = result,
            outputDir = tempDir,
            formats = setOf(ReportFormat.MARKDOWN),
            scenarioNames = null
        )
        assertTrue(out.errors.isEmpty(), "unexpected errors: ${out.errors}")
        val body = Files.readString(out.written.single())
        // Every scenario appears as a row in the Run Overview table and
        // as a sub-section header in Across-Replication Statistics.
        assertContains(body, "S1")
        assertContains(body, "S2")
        assertContains(body, "S3")
    }

    @Test
    fun `non-null scenarioNames filters the Run Overview and the per-scenario sections`() {
        val result = threeScenarioResult()
        val out = ScenarioReports.renderScenarioSummaries(
            result = result,
            outputDir = tempDir,
            formats = setOf(ReportFormat.MARKDOWN),
            scenarioNames = setOf("S1", "S3")
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
            "Filtered scenario 'S2' leaked into the output:\n$body"
        )
        // The filtered count surfaces in the header paragraph.
        assertContains(body, "Report filtered to 2 of 3 scenarios.")
    }

    @Test
    fun `unknown names in scenarioNames are silently ignored`() {
        val result = threeScenarioResult()
        val out = ScenarioReports.renderScenarioSummaries(
            result = result,
            outputDir = tempDir,
            formats = setOf(ReportFormat.MARKDOWN),
            scenarioNames = setOf("S1", "DoesNotExist")
        )
        assertTrue(out.errors.isEmpty())
        val body = Files.readString(out.written.single())
        assertContains(body, "S1")
        // The bogus name is dropped silently — it never reaches the body.
        assertFalse(body.contains("DoesNotExist"))
    }

    @Test
    fun `empty scenarioNames set surfaces as an error and writes no files`() {
        val result = threeScenarioResult()
        val out = ScenarioReports.renderScenarioSummaries(
            result = result,
            outputDir = tempDir,
            formats = setOf(ReportFormat.MARKDOWN),
            scenarioNames = emptySet()
        )
        assertTrue(out.written.isEmpty())
        assertTrue(out.errors.single().contains("No scenarios match"))
    }

    @Test
    fun `scenarioNames whitelist matching every snapshot omits the filtered-to footer`() {
        val result = threeScenarioResult()
        val out = ScenarioReports.renderScenarioSummaries(
            result = result,
            outputDir = tempDir,
            formats = setOf(ReportFormat.MARKDOWN),
            scenarioNames = setOf("S1", "S2", "S3")
        )
        assertTrue(out.errors.isEmpty())
        val body = Files.readString(out.written.single())
        // All three present.
        assertContains(body, "S1"); assertContains(body, "S2"); assertContains(body, "S3")
        // No "filtered to N of M" sentence — the size matched the input.
        assertFalse(
            body.contains("Report filtered to"),
            "Unexpected filtered footer when whitelist matched every scenario"
        )
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    private fun threeScenarioResult(): RunResult.BatchCompleted = RunResult.BatchCompleted(
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
