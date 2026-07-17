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

package ksl.app.swing.single

import ksl.app.notification.NotificationSink
import ksl.app.single.results.ReportSaveRecord
import ksl.examples.book.appendixD.GIGcQueue
import ksl.observers.ResponseTrace
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.utilities.io.OutputDirectory
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.assertTrue

/**
 *  Headless-guarded smoke test for [TraceReportDialog]'s Swing shell.
 *
 *  Constructing a `JDialog` and rendering the embedded lets-plot figures both
 *  need a display, so each test skips on a headless JVM via [assumeFalse].
 *  The dialog's pure logic is covered headless-safely by
 *  [TraceReportDialogLogicTest]; this test exercises the
 *  resolve → materialize → record seam end-to-end against temp dirs.
 */
class TraceReportDialogTest {

    private companion object {
        const val SYSTEM_TIME = "System Time"
        const val NUM_IN_SYSTEM = "Num in System"
    }

    private var controller: SingleAppController? = null

    @AfterTest
    fun closeController() {
        controller?.close()
        controller = null
    }

    /** Builder whose probe model exposes the same responses the trace files
     *  carry, so the dialog can resolve isTimeWeighted from the snapshot. */
    private val builder = object : ModelBuilderIfc {
        override fun build(
            modelConfiguration: Map<String, String>?,
            experimentRunParameters: ExperimentRunParametersIfc?
        ): Model {
            val m = Model("TraceReportApp", autoCSVReports = false)
            GIGcQueue(m, numServers = 1, name = "Q")
            return m
        }
    }

    private fun freshController(): SingleAppController {
        val c = SingleAppController("TraceReportApp", builder)
        controller = c
        return c
    }

    /** Stream real trace files for the tally and time-weighted responses. */
    private fun writeTraces(outputDir: Path) {
        val m = Model("TraceReportDataModel", autoCSVReports = false)
        m.outputDirectory = OutputDirectory(outputDir, "kslOutput.txt")
        m.numberOfReplications = 2
        m.lengthOfReplication = 200.0
        GIGcQueue(m, numServers = 1, name = "Q")
        val traces = listOf(
            ResponseTrace(m.response(SYSTEM_TIME)!!),
            ResponseTrace(m.response(NUM_IN_SYSTEM)!!)
        )
        m.simulate()
        // Close the trace observers so their *_Trace handles are released and @TempDir can be
        // deleted (on Windows an open handle blocks temp-dir cleanup; invisible on Unix).
        traces.forEach { it.close() }
    }

    @Test
    @DisplayName("Save materializes a trace report and records it as a MANUAL save")
    fun saveMaterializesAndRecordsManualSave(@TempDir tempDir: Path) {
        assumeFalse(GraphicsEnvironment.isHeadless(), "JDialog + plot rendering require a display")
        val traceOutputDir = tempDir.resolve("output").also { Files.createDirectories(it) }
        val reportsDir = tempDir.resolve("reports")
        writeTraces(traceOutputDir)

        val c = freshController()
        val notifier = NotificationSink.Collecting()
        val dialog = TraceReportDialogImpl(
            controller = c,
            notifier = notifier,
            owner = null,
            traceOutputDir = traceOutputDir,
            reportsDir = reportsDir
        )
        // HTML is checked by default; the stem is pre-seeded.  Use the
        // synchronous render+record path (production runs it off the EDT).
        dialog.saveBlocking()

        val saves = c.recentReportSaves.value
        assertTrue(saves.any { it.origin == ReportSaveRecord.Origin.MANUAL },
            "a MANUAL ReportSaveRecord must be recorded; got: $saves")
        val saved = saves.first()
        assertTrue(Files.exists(saved.path), "the recorded report file must exist: ${saved.path}")
        assertTrue(saved.fileName.endsWith(".html"), "default format is HTML; got: ${saved.fileName}")
    }

    @Test
    @DisplayName("Save is disabled until a format is selected")
    fun saveDisabledWithoutAnyFormat(@TempDir tempDir: Path) {
        assumeFalse(GraphicsEnvironment.isHeadless(), "JDialog construction requires a display")
        val traceOutputDir = tempDir.resolve("output").also { Files.createDirectories(it) }
        writeTraces(traceOutputDir)

        val c = freshController()
        val dialog = TraceReportDialogImpl(
            controller = c,
            notifier = NotificationSink.Collecting(),
            owner = null,
            traceOutputDir = traceOutputDir,
            reportsDir = tempDir.resolve("reports")
        )
        assertTrue(dialog.saveButton.isEnabled, "with HTML checked by default, Save starts enabled")
        dialog.htmlBox.isSelected = false
        dialog.markdownBox.isSelected = false
        dialog.textBox.isSelected = false
        dialog.htmlBox.actionListeners.forEach { it.actionPerformed(null) }
        assertTrue(!dialog.saveButton.isEnabled, "Save must disable when no format is selected")
    }
}
