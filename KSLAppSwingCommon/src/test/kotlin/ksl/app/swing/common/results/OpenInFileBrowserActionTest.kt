package ksl.app.swing.common.results

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.event.ActionEvent
import java.io.File
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpenInFileBrowserActionTest {

    private class FakeDesktop(var openResult: Boolean = true) : DesktopOpener {
        val openCalls = mutableListOf<File>()
        override fun browse(uri: URI): Boolean = error("unexpected browse")
        override fun open(file: File): Boolean {
            openCalls.add(file)
            return openResult
        }
    }

    @Test
    fun `opens the directory directly`(@TempDir tempDir: Path) {
        val target = tempDir.resolve("output").also { it.createDirectories() }
        val desktop = FakeDesktop()
        val action = OpenInFileBrowserAction(target, desktop)
        action.actionPerformed(ActionEvent("", 0, ""))
        assertEquals(listOf(target.toFile()), desktop.openCalls)
    }

    @Test
    fun `onUnavailable fires on failure`(@TempDir tempDir: Path) {
        val target = tempDir.resolve("output").also { it.createDirectories() }
        val desktop = FakeDesktop(openResult = false)
        var unavailable: Path? = null
        val action = OpenInFileBrowserAction(target, desktop, onUnavailable = { unavailable = it })
        action.actionPerformed(ActionEvent("", 0, ""))
        assertEquals(target, unavailable)
    }

    @Test
    fun `onUnavailable does not fire on success`(@TempDir tempDir: Path) {
        val target = tempDir.resolve("output").also { it.createDirectories() }
        val desktop = FakeDesktop()
        var unavailable: Path? = null
        val action = OpenInFileBrowserAction(target, desktop, onUnavailable = { unavailable = it })
        action.actionPerformed(ActionEvent("", 0, ""))
        assertNull(unavailable)
    }
}
