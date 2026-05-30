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
import ksl.app.single.results.StandardReportOutcome
import ksl.examples.general.appsession.SINGLE_HEADLESS_DEMO_ANALYSIS_NAME
import ksl.examples.general.appsession.SINGLE_HEADLESS_DEMO_APP_NAME
import ksl.examples.general.appsession.runKSLAppSessionSingleHeadlessDemo
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
 *  Substrate-validation tests for `KSLAppSessionSingleHeadlessDemo`.
 *
 *  Drives the demo with a JUnit `@TempDir` workspace and asserts:
 *
 *  - The single workflow reaches `RunResult.Completed` end-to-end
 *    via the substrate `KSLAppSession` / `RunHandle` API.
 *  - The substrate report writer (`StandardReportMaterializer`)
 *    materialises HTML against that result without a Swing host.
 *  - The rendered file lands at exactly the path the substrate
 *    `AppWorkspacePaths` primitives derive — no host-side
 *    intervention between path math and file write.
 *  - The substrate `NotificationSink` contract is sufficient for a
 *    headless host's user-facing messaging (collecting sink captures
 *    every emit).
 *  - The demo source itself does not import Swing / AWT or
 *    low-level orchestrators — proving the demo is a faithful
 *    non-Swing host reference.
 */
class KSLAppSessionSingleHeadlessDemoTest {

    @Test
    fun `headless single workflow renders standard report via substrate APIs`(
        @TempDir workspace: Path
    ) = runBlocking {
        val notifier = NotificationSink.Collecting()
        val report = runKSLAppSessionSingleHeadlessDemo(workspace, notifier)

        // Run completed.
        assertIs<RunResult.Completed>(report.runResult)
        assertTrue(report.events.any { it is RunEvent.RunCompleted },
            "Expected a RunCompleted event in the observed stream.")

        // Materialiser produced an Ok with a real file.
        val outcome = assertIs<StandardReportOutcome.Ok>(report.materializerOutcome)
        assertNotNull(report.reportFile, "Report file must be populated on Ok outcome.")
        assertTrue(report.reportFile!!.toFile().exists(),
            "Materialised report file must exist on disk.")
        assertEquals(outcome.file, report.reportFile!!.toFile())

        // HTML body has content.
        val htmlBody = Files.readString(report.reportFile!!)
        assertTrue(htmlBody.isNotBlank(), "HTML body must not be blank.")
        assertTrue(htmlBody.contains("<html", ignoreCase = true),
            "HTML body must contain a <html> tag.")

        // File lands at exactly the AppWorkspacePaths-derived path.
        val expectedAppWorkspace = AppWorkspacePaths.appWorkspaceDir(
            workspace,
            SINGLE_HEADLESS_DEMO_APP_NAME
        )
        val expectedReportsDir = AppWorkspacePaths.reportsDir(
            expectedAppWorkspace,
            SINGLE_HEADLESS_DEMO_ANALYSIS_NAME
        )
        assertEquals(expectedAppWorkspace, report.appWorkspace)
        assertEquals(expectedReportsDir, report.reportsDir)
        assertEquals(expectedReportsDir.resolve("standard.html"), report.reportFile)
    }

    @Test
    fun `headless single workflow emits notifications through the substrate sink`(
        @TempDir workspace: Path
    ) = runBlocking {
        val notifier = NotificationSink.Collecting()
        runKSLAppSessionSingleHeadlessDemo(workspace, notifier)

        val specs = notifier.specs()
        assertTrue(specs.size >= 3,
            "Demo must emit at least three notifications (start, submit, render); got ${specs.size}.")

        // Every emitted spec on the happy path is INFO.
        assertTrue(specs.all { it.severity == NotificationSeverity.INFO },
            "Happy-path demo must emit only INFO notifications; got " +
                specs.map { it.severity }.distinct())

        // The host explicitly announced the render completion with the file path.
        assertTrue(specs.any { it.message.contains("Wrote standard.html") },
            "Demo must emit a post-render INFO referencing the rendered file.")
    }

    @Test
    fun `headless single demo does not import low-level orchestrators or Swing`() {
        val source = readDemoSource()
        // Check actual `import` lines rather than any source occurrence,
        // so KDoc explanatory text that mentions the forbidden packages
        // (e.g. "does not reach into ksl.app.orchestrator.*") doesn't
        // trip the assertion.
        val importLines = source.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("import ") }
            .toList()

        assertTrue(importLines.none { it.startsWith("import ksl.app.orchestrator") },
            "Demo must drive runs through KSLAppSession, not orchestrators.  Offending imports: " +
                importLines.filter { it.startsWith("import ksl.app.orchestrator") })
        assertTrue(importLines.none { it.startsWith("import javax.swing") },
            "Demo must not import any Swing API.  Offending imports: " +
                importLines.filter { it.startsWith("import javax.swing") })
        assertTrue(importLines.none { it.startsWith("import java.awt") },
            "Demo must not import any AWT API.  Offending imports: " +
                importLines.filter { it.startsWith("import java.awt") })
    }

    private fun readDemoSource(): String {
        val repoRoot = File(System.getProperty("user.dir")).parentFile
        return repoRoot.resolve(
            "KSLExamples/src/main/kotlin/ksl/examples/general/appsession/" +
                "KSLAppSessionSingleHeadlessDemo.kt"
        ).readText()
    }
}
