package ksl.app.swing.common.results

import ksl.app.config.ReportFormat
import org.junit.jupiter.api.Test
import java.awt.event.ActionEvent
import java.io.File
import java.net.URI
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenReportActionTest {

    private class FakeDesktop(
        var browseResult: Boolean = true,
        var openResult: Boolean = true
    ) : DesktopOpener {
        val browseCalls = mutableListOf<URI>()
        val openCalls = mutableListOf<File>()
        override fun browse(uri: URI): Boolean {
            browseCalls.add(uri)
            return browseResult
        }
        override fun open(file: File): Boolean {
            openCalls.add(file)
            return openResult
        }
    }

    @Test
    fun `HTML format invokes browse with the file URI`() {
        val desktop = FakeDesktop()
        val path = Path.of("/tmp/report.html")
        val action = OpenReportAction(ReportFormat.HTML, path, desktop)
        action.actionPerformed(ActionEvent("", 0, ""))
        assertEquals(listOf(path.toUri()), desktop.browseCalls)
        assertTrue(desktop.openCalls.isEmpty(), "HTML should not call open")
    }

    @Test
    fun `Markdown format invokes open with the file`() {
        val desktop = FakeDesktop()
        val path = Path.of("/tmp/report.md")
        val action = OpenReportAction(ReportFormat.MARKDOWN, path, desktop)
        action.actionPerformed(ActionEvent("", 0, ""))
        assertEquals(listOf(path.toFile()), desktop.openCalls)
        assertTrue(desktop.browseCalls.isEmpty(), "Markdown should not call browse")
    }

    @Test
    fun `Text format invokes open with the file`() {
        val desktop = FakeDesktop()
        val path = Path.of("/tmp/report.txt")
        val action = OpenReportAction(ReportFormat.TEXT, path, desktop)
        action.actionPerformed(ActionEvent("", 0, ""))
        assertEquals(listOf(path.toFile()), desktop.openCalls)
    }

    @Test
    fun `onUnavailable fires when browse fails`() {
        val desktop = FakeDesktop(browseResult = false)
        var unavailable: Path? = null
        val path = Path.of("/tmp/report.html")
        val action = OpenReportAction(ReportFormat.HTML, path, desktop, onUnavailable = { unavailable = it })
        action.actionPerformed(ActionEvent("", 0, ""))
        assertEquals(path, unavailable)
    }

    @Test
    fun `onUnavailable fires when open fails for Markdown`() {
        val desktop = FakeDesktop(openResult = false)
        var unavailable: Path? = null
        val path = Path.of("/tmp/report.md")
        val action = OpenReportAction(ReportFormat.MARKDOWN, path, desktop, onUnavailable = { unavailable = it })
        action.actionPerformed(ActionEvent("", 0, ""))
        assertEquals(path, unavailable)
    }

    @Test
    fun `onUnavailable does not fire on success`() {
        val desktop = FakeDesktop()
        var unavailable: Path? = null
        val action = OpenReportAction(
            ReportFormat.HTML, Path.of("/tmp/r.html"), desktop, onUnavailable = { unavailable = it }
        )
        action.actionPerformed(ActionEvent("", 0, ""))
        assertNull(unavailable)
    }

    @Test
    fun `default title varies by format`() {
        assertEquals("Open Report (HTML)", OpenReportAction.defaultTitle(ReportFormat.HTML))
        assertEquals("Open Report (Markdown)", OpenReportAction.defaultTitle(ReportFormat.MARKDOWN))
        assertEquals("Open Report (Text)", OpenReportAction.defaultTitle(ReportFormat.TEXT))
    }

    @Test
    fun `custom title is honored`() {
        val action = OpenReportAction(
            ReportFormat.HTML, Path.of("/tmp/r.html"), FakeDesktop(), title = "Custom Label"
        )
        assertEquals("Custom Label", action.getValue(javax.swing.Action.NAME))
    }
}
