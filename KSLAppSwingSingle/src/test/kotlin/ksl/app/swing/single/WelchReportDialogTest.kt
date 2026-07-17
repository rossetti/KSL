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
import ksl.examples.book.appendixD.GIGcQueue
import ksl.observers.welch.WelchFileObserver
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
 *  Headless-guarded smoke test for [WelchReportDialog]'s Swing shell.
 *
 *  Constructing a `JDialog` and rendering the embedded lets-plot figures
 *  both need a display, so each test skips on a headless JVM via
 *  [assumeFalse].  The dialog's pure logic is covered headless-safely by
 *  [WelchReportDialogLogicTest]; this test exercises the
 *  discover → materialize → record seam end-to-end against temp dirs.
 */
class WelchReportDialogTest {

    private var controller: SingleAppController? = null
    private var openDialog: WelchReportDialogImpl? = null

    @AfterTest
    fun closeController() {
        // Dispose the dialog first: WelchReportDialog.dispose() closes the Welch analyzers it opened
        // (each holds a .wdf handle), so @TempDir cleanup can delete the captured files on Windows.
        openDialog?.dispose()
        openDialog = null
        controller?.close()
        controller = null
    }

    private val builder = object : ModelBuilderIfc {
        override fun build(
            modelConfiguration: Map<String, String>?,
            experimentRunParameters: ExperimentRunParametersIfc?
        ): Model = Model("WelchReportApp", autoCSVReports = false)
    }

    private fun freshController(): SingleAppController {
        val c = SingleAppController("WelchReportApp", builder)
        controller = c
        return c
    }

    /**
     * Streams real Welch data into [outputDir] by running a short sim with
     * two observers attached — matching Ch5Example4's 5x50000 scale so the
     * analysis has enough averages to batch.  No plotting here (headless-safe).
     */
    private fun writeWelchData(outputDir: Path) {
        val m = Model("WelchReportDataModel", autoCSVReports = false)
        m.outputDirectory = OutputDirectory(outputDir, "kslOutput.txt")
        m.numberOfReplications = 5
        m.lengthOfReplication = 50000.0
        GIGcQueue(m, numServers = 1, name = "Q")
        val observers = listOf(
            WelchFileObserver(m.response("System Time")!!, 1.0),
            WelchFileObserver(m.response("Num in System")!!, 10.0)
        )
        m.simulate()
        // Close the observers so their .wdf/.json handles are released (Windows blocks deleting an
        // open file); the dialog's read-side analyzers are released on dialog.dispose() in @AfterTest.
        observers.forEach { it.close() }
    }

    @Test
    @DisplayName("Save materializes a Welch report and records it as a MANUAL save")
    fun saveMaterializesAndRecordsManualSave(@TempDir tempDir: Path) {
        assumeFalse(GraphicsEnvironment.isHeadless(), "JDialog + plot rendering require a display")
        val welchOutputDir = tempDir.resolve("output").also { Files.createDirectories(it) }
        val reportsDir = tempDir.resolve("reports")
        writeWelchData(welchOutputDir)

        val c = freshController()
        val notifier = NotificationSink.Collecting()
        val dialog = WelchReportDialogImpl(
            controller = c,
            notifier = notifier,
            owner = null,
            welchOutputDir = welchOutputDir,
            reportsDir = reportsDir
        ).also { openDialog = it }
        // HTML is checked by default; the stem is pre-seeded.  Use the
        // synchronous render+record path (production runs it off the EDT).
        dialog.saveBlocking()

        val saves = c.recentReportSaves.value
        assertTrue(saves.any { it.origin == ksl.app.single.results.ReportSaveRecord.Origin.MANUAL },
            "a MANUAL ReportSaveRecord must be recorded; got: $saves")
        val saved = saves.first()
        assertTrue(Files.exists(saved.path), "the recorded report file must exist on disk: ${saved.path}")
        assertTrue(saved.fileName.endsWith(".html"), "default format is HTML; got: ${saved.fileName}")
    }

    @Test
    @DisplayName("Save is disabled until a format is selected")
    fun saveDisabledWithoutAnyFormat(@TempDir tempDir: Path) {
        assumeFalse(GraphicsEnvironment.isHeadless(), "JDialog construction requires a display")
        val welchOutputDir = tempDir.resolve("output").also { Files.createDirectories(it) }
        writeWelchData(welchOutputDir)

        val c = freshController()
        val dialog = WelchReportDialogImpl(
            controller = c,
            notifier = NotificationSink.Collecting(),
            owner = null,
            welchOutputDir = welchOutputDir,
            reportsDir = tempDir.resolve("reports")
        ).also { openDialog = it }
        assertTrue(dialog.saveButton.isEnabled, "with HTML checked by default, Save starts enabled")
        dialog.htmlBox.isSelected = false
        dialog.markdownBox.isSelected = false
        dialog.textBox.isSelected = false
        // Re-fire the listener the checkboxes use.
        dialog.htmlBox.actionListeners.forEach { it.actionPerformed(null) }
        assertTrue(!dialog.saveButton.isEnabled, "Save must disable when no format is selected")
    }
}
