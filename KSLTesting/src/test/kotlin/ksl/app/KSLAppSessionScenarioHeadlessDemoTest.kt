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

package ksl.app

import kotlinx.coroutines.runBlocking
import ksl.app.notification.NotificationSeverity
import ksl.app.notification.NotificationSink
import ksl.app.session.AppWorkspacePaths
import ksl.app.session.RunEvent
import ksl.app.session.RunResult
import ksl.examples.general.appsession.SCENARIO_HEADLESS_DEMO_ANALYSIS_NAME
import ksl.examples.general.appsession.SCENARIO_HEADLESS_DEMO_APP_NAME
import ksl.examples.general.appsession.SCENARIO_HEADLESS_DEMO_RESPONSE
import ksl.examples.general.appsession.SCENARIO_HEADLESS_DEMO_SCENARIO_NAMES
import ksl.examples.general.appsession.runKSLAppSessionScenarioHeadlessDemo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  Substrate-validation tests for `KSLAppSessionScenarioHeadlessDemo`.
 *
 *  Drives the demo with a JUnit `@TempDir` workspace and asserts:
 *
 *  - The scenario sweep reaches `RunResult.BatchCompleted`
 *    end-to-end via the substrate `KSLAppSession` / `RunHandle`
 *    API with all 3 scenario snapshots.
 *  - The substrate batch report writer
 *    (`BatchReportsWriter.renderBatchSummary`) materialises HTML
 *    against that result without a Swing host, at exactly the
 *    `AppWorkspacePaths.outputDir(...)` path.
 *  - The substrate comparison stack
 *    (`BatchCompletedComparisonSource` →
 *    `ComparisonSelectionModel` →
 *    `ComparisonReportRenderer.renderBoxPlot`) composes end-to-end
 *    against the same result, with the box-plot HTML naming all
 *    three scenarios.
 *  - The substrate `NotificationSink` contract surfaces every
 *    user-facing message — file paths included — through a single
 *    sink the host can snapshot.
 *  - The demo source imports neither low-level orchestrators nor
 *    Swing / AWT, mirroring the E.1.1 invariant for the
 *    single-workflow demo.
 */
class KSLAppSessionScenarioHeadlessDemoTest {

    @Test
    fun `headless scenario workflow produces a 3-snapshot BatchCompleted`(
        @TempDir workspace: Path
    ) = runBlocking {
        val notifier = NotificationSink.Collecting()
        val report = runKSLAppSessionScenarioHeadlessDemo(workspace, notifier)

        val batch = assertIs<RunResult.BatchCompleted>(report.runResult)
        assertEquals(3, batch.snapshots.size,
            "Expected exactly 3 scenario snapshots in commit order.")
        assertEquals(
            SCENARIO_HEADLESS_DEMO_SCENARIO_NAMES,
            batch.snapshots.map { it.experiment.exp_name },
            "Scenario names must match the demo's published list in commit order."
        )
        assertTrue(report.events.any { it is RunEvent.ScenarioCompleted },
            "Expected at least one ScenarioCompleted event in the observed stream.")
        assertTrue(report.events.any { it is RunEvent.RunCompleted },
            "Expected a terminal RunCompleted event.")
    }

    @Test
    fun `batch summary HTML lands at AppWorkspacePaths-derived path`(
        @TempDir workspace: Path
    ) = runBlocking {
        val notifier = NotificationSink.Collecting()
        val report = runKSLAppSessionScenarioHeadlessDemo(workspace, notifier)

        assertNotNull(report.batchSummaryFile, "Batch summary file must be populated on success.")
        assertTrue(report.batchSummaryFile!!.toFile().exists(),
            "Batch summary file must exist on disk.")

        // Path math reproducible from the substrate primitives only.
        val expectedAppWorkspace = AppWorkspacePaths.appWorkspaceDir(
            workspace,
            SCENARIO_HEADLESS_DEMO_APP_NAME
        )
        val expectedAnalysisDir = AppWorkspacePaths.outputDir(
            expectedAppWorkspace,
            SCENARIO_HEADLESS_DEMO_ANALYSIS_NAME
        )
        assertEquals(expectedAppWorkspace, report.appWorkspace)
        assertEquals(expectedAnalysisDir, report.analysisOutputDir)
        // The demo passes `batchFileStem = "scenario-summary"`; HTML extension is "html".
        assertEquals(expectedAnalysisDir.resolve("scenario-summary.html"), report.batchSummaryFile)

        val body = Files.readString(report.batchSummaryFile!!)
        assertTrue(body.isNotBlank(), "Batch summary HTML must not be blank.")
        SCENARIO_HEADLESS_DEMO_SCENARIO_NAMES.forEach { name ->
            assertTrue(name in body,
                "Batch summary HTML must mention scenario '$name'; got:\n${body.take(200)}…")
        }
    }

    @Test
    fun `box-plot HTML lands at AppWorkspacePaths-derived path and names all scenarios`(
        @TempDir workspace: Path
    ) = runBlocking {
        val notifier = NotificationSink.Collecting()
        val report = runKSLAppSessionScenarioHeadlessDemo(workspace, notifier)

        assertNotNull(report.boxPlotFile, "Box-plot file must be populated on success.")
        assertTrue(report.boxPlotFile!!.toFile().exists(),
            "Box-plot file must exist on disk.")

        val expectedAppWorkspace = AppWorkspacePaths.appWorkspaceDir(
            workspace,
            SCENARIO_HEADLESS_DEMO_APP_NAME
        )
        val expectedReportsDir = AppWorkspacePaths.reportsDir(
            expectedAppWorkspace,
            SCENARIO_HEADLESS_DEMO_ANALYSIS_NAME
        )
        assertEquals(expectedReportsDir, report.reportsDir)
        assertTrue(report.boxPlotFile!!.parent == expectedReportsDir,
            "Box-plot file must live directly under the AppWorkspacePaths.reportsDir.")
        assertTrue(report.boxPlotFile!!.fileName.toString().endsWith(".html"),
            "Box-plot file must be HTML.")

        val body = Files.readString(report.boxPlotFile!!)
        assertTrue(body.isNotBlank(), "Box-plot HTML must not be blank.")
        SCENARIO_HEADLESS_DEMO_SCENARIO_NAMES.forEach { name ->
            assertTrue(name in body,
                "Box-plot HTML must mention scenario '$name'; got:\n${body.take(200)}…")
        }
        // Response name should also surface in the document title / caption.
        assertTrue(SCENARIO_HEADLESS_DEMO_RESPONSE in body,
            "Box-plot HTML must mention the response name '$SCENARIO_HEADLESS_DEMO_RESPONSE'.")
    }

    @Test
    fun `headless scenario demo emits notifications through the substrate sink`(
        @TempDir workspace: Path
    ) = runBlocking {
        val notifier = NotificationSink.Collecting()
        runKSLAppSessionScenarioHeadlessDemo(workspace, notifier)

        val specs = notifier.specs()
        // Conservative lower bound: start + submit + completed + summary-rendered +
        // wrap-as-source + gather + box-plot-rendered = 7 minimum.
        assertTrue(specs.size >= 6,
            "Demo must emit at least six notifications; got ${specs.size}.")
        // Happy path: no WARN / ERROR.
        assertTrue(specs.all { it.severity == NotificationSeverity.INFO },
            "Happy-path demo must emit only INFO notifications; got " +
                specs.map { it.severity }.distinct())

        assertTrue(specs.any { it.message.contains("batch summary") },
            "Demo must announce the batch summary render with a file path.")
        assertTrue(specs.any { it.message.contains("box-plot report") },
            "Demo must announce the box-plot render with a file path.")
    }

    @Test
    fun `headless scenario demo does not import low-level orchestrators or Swing`() {
        val source = readDemoSource()
        // Same import-line scoping as the E.1.1 test so KDoc explanatory
        // text that mentions forbidden packages doesn't trip the check.
        val importLines = source.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("import ") }
            .toList()

        assertTrue(importLines.none { it.startsWith("import ksl.app.orchestrator") },
            "Demo must drive runs through KSLAppSession, not orchestrators.  Offending imports: " +
                importLines.filter { it.startsWith("import ksl.app.orchestrator") })
        assertTrue(importLines.none { it.startsWith("import javax.swing") },
            "Demo must not import any Swing API.")
        assertTrue(importLines.none { it.startsWith("import java.awt") },
            "Demo must not import any AWT API.")
    }

    private fun readDemoSource(): String {
        val repoRoot = File(System.getProperty("user.dir")).parentFile
        return repoRoot.resolve(
            "KSLExamples/src/main/kotlin/ksl/examples/general/appsession/" +
                "KSLAppSessionScenarioHeadlessDemo.kt"
        ).readText()
    }
}
