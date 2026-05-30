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
import ksl.examples.general.appsession.EXPERIMENT_HEADLESS_DEMO_ANALYSIS_NAME
import ksl.examples.general.appsession.EXPERIMENT_HEADLESS_DEMO_APP_NAME
import ksl.examples.general.appsession.EXPERIMENT_HEADLESS_DEMO_DESIGN_POINTS
import ksl.examples.general.appsession.EXPERIMENT_HEADLESS_DEMO_RESPONSE
import ksl.examples.general.appsession.runKSLAppSessionExperimentHeadlessDemo
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
 *  Substrate-validation tests for `KSLAppSessionExperimentHeadlessDemo`.
 *
 *  Drives the experiment-workflow demo with a JUnit `@TempDir`
 *  workspace and asserts:
 *
 *  - The full-factorial design reaches `RunResult.BatchCompleted`
 *    end-to-end via the substrate `KSLAppSession` / `RunHandle` API
 *    with one snapshot per design point.
 *  - The substrate `RegressionFitRecord` DTO populates correctly
 *    from a `ParallelDesignedExperiment.regressionResults(...)`
 *    call made post-submit — proving the experiment instance
 *    retains its run state after the orchestrator returns.
 *  - The regression HTML report lands under the
 *    `AppWorkspacePaths.reportsDir(...)` path and includes the
 *    response name and a regression summary section.
 *  - The substrate `NotificationSink.Collecting` captures every
 *    user-facing message including fit and render announcements.
 *  - The demo source does not import Swing / AWT or low-level
 *    orchestrators, matching the E.1.1 / E.1.2 invariant.
 */
class KSLAppSessionExperimentHeadlessDemoTest {

    @Test
    fun `headless experiment workflow produces a BatchCompleted with one snapshot per design point`(
        @TempDir workspace: Path
    ) = runBlocking {
        val notifier = NotificationSink.Collecting()
        val report = runKSLAppSessionExperimentHeadlessDemo(workspace, notifier)

        val batch = assertIs<RunResult.BatchCompleted>(report.runResult)
        assertEquals(EXPERIMENT_HEADLESS_DEMO_DESIGN_POINTS, batch.snapshots.size,
            "Expected one snapshot per design point in the 2² factorial.")
        assertEquals(EXPERIMENT_HEADLESS_DEMO_DESIGN_POINTS, report.designPointCount,
            "Demo report's designPointCount must equal the substrate snapshot count.")
        assertTrue(report.events.any { it is RunEvent.DesignPointStarted },
            "Expected at least one DesignPointStarted event in the observed stream.")
        assertTrue(report.events.any { it is RunEvent.DesignPointCompleted },
            "Expected at least one DesignPointCompleted event in the observed stream.")
        assertTrue(report.events.any { it is RunEvent.RunCompleted },
            "Expected a terminal RunCompleted event.")
    }

    @Test
    fun `RegressionFitRecord is populated from substrate regressionResults call`(
        @TempDir workspace: Path
    ) = runBlocking {
        val notifier = NotificationSink.Collecting()
        val report = runKSLAppSessionExperimentHeadlessDemo(workspace, notifier)

        val fit = assertNotNull(report.fitRecord,
            "Fit record must be populated on a successful run.")
        assertEquals(EXPERIMENT_HEADLESS_DEMO_RESPONSE, fit.response)
        assertEquals(true, fit.coded)
        assertEquals(0.95, fit.confidenceLevel)
        assertTrue(fit.modelExpression.isNotBlank(),
            "First-order LinearModel.asString() must produce a non-blank expression.")
        // The substrate fit object itself is a numerically-meaningful
        // result — the responseName field round-trips, and a few core
        // statistics are populated.
        assertEquals(EXPERIMENT_HEADLESS_DEMO_RESPONSE, fit.fit.responseName)
        assertTrue(fit.fit.numParameters > 0,
            "Regression must estimate at least one parameter (intercept).")
        assertTrue(fit.fit.numObservations > 0,
            "Regression must be fit against a non-empty observation set.")
    }

    @Test
    fun `regression HTML lands at AppWorkspacePaths-derived path with response in body`(
        @TempDir workspace: Path
    ) = runBlocking {
        val notifier = NotificationSink.Collecting()
        val report = runKSLAppSessionExperimentHeadlessDemo(workspace, notifier)

        assertNotNull(report.regressionReportFile,
            "Regression report file must be populated on a successful run.")
        assertTrue(report.regressionReportFile!!.toFile().exists(),
            "Regression report file must exist on disk.")

        val expectedAppWorkspace = AppWorkspacePaths.appWorkspaceDir(
            workspace,
            EXPERIMENT_HEADLESS_DEMO_APP_NAME
        )
        val expectedReportsDir = AppWorkspacePaths.reportsDir(
            expectedAppWorkspace,
            EXPERIMENT_HEADLESS_DEMO_ANALYSIS_NAME
        )
        assertEquals(expectedAppWorkspace, report.appWorkspace)
        assertEquals(expectedReportsDir, report.reportsDir)
        assertEquals(expectedReportsDir, report.regressionReportFile!!.parent,
            "Regression report file must live directly under the reportsDir.")
        assertTrue(report.regressionReportFile!!.fileName.toString().endsWith(".html"),
            "Regression report file must be HTML.")

        val body = Files.readString(report.regressionReportFile!!)
        assertTrue(body.isNotBlank(), "Regression HTML must not be blank.")
        assertTrue(body.contains("<html", ignoreCase = true),
            "Regression HTML must contain a <html> tag.")
        assertTrue(EXPERIMENT_HEADLESS_DEMO_RESPONSE in body,
            "Regression HTML must mention the response name " +
                "'$EXPERIMENT_HEADLESS_DEMO_RESPONSE'.")
        // toReport()'s default block builds a regressionSummary + regressionParameters
        // + regressionDiagnostics composite; we look for the common section labels.
        assertTrue(body.contains("Regression Summary", ignoreCase = true)
            || body.contains("R-squared", ignoreCase = true)
            || body.contains("Coefficient", ignoreCase = true),
            "Regression HTML must include at least one of the standard section labels.")
    }

    @Test
    fun `headless experiment demo emits notifications through the substrate sink`(
        @TempDir workspace: Path
    ) = runBlocking {
        val notifier = NotificationSink.Collecting()
        runKSLAppSessionExperimentHeadlessDemo(workspace, notifier)

        val specs = notifier.specs()
        // Conservative lower bound: start + submit + completed + fit + render-start +
        // render-done = 6 minimum.
        assertTrue(specs.size >= 5,
            "Demo must emit at least five notifications; got ${specs.size}.")
        assertTrue(specs.all { it.severity == NotificationSeverity.INFO },
            "Happy-path demo must emit only INFO notifications; got " +
                specs.map { it.severity }.distinct())
        assertTrue(specs.any { it.message.contains("Fitting first-order regression") },
            "Demo must announce the regression fit step.")
        assertTrue(specs.any { it.message.contains("Wrote regression report") },
            "Demo must announce the regression report render with a file path.")
    }

    @Test
    fun `headless experiment demo does not import low-level orchestrators or Swing`() {
        val source = readDemoSource()
        // Same import-line scoping as the E.1.1 / E.1.2 tests so KDoc
        // explanatory text mentioning forbidden packages doesn't trip
        // the check.
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
                "KSLAppSessionExperimentHeadlessDemo.kt"
        ).readText()
    }
}
