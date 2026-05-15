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

class OpenDatabaseActionTest {

    private class FakeDesktop(var openResult: Boolean = true) : DesktopOpener {
        val openCalls = mutableListOf<File>()
        override fun browse(uri: URI): Boolean = error("unexpected browse")
        override fun open(file: File): Boolean {
            openCalls.add(file)
            return openResult
        }
    }

    @Test
    fun `opens the parent directory of the db file`(@TempDir tempDir: Path) {
        val dbDir = tempDir.resolve("dbDir").also { it.createDirectories() }
        val dbFile = dbDir.resolve("scenario.db")
        val desktop = FakeDesktop()
        val action = OpenDatabaseAction(dbFile, desktop)
        action.actionPerformed(ActionEvent("", 0, ""))
        assertEquals(listOf(dbDir.toAbsolutePath().toFile()), desktop.openCalls)
    }

    @Test
    fun `onUnavailable fires when open fails`(@TempDir tempDir: Path) {
        val dbDir = tempDir.resolve("dbDir").also { it.createDirectories() }
        val dbFile = dbDir.resolve("scenario.db")
        val desktop = FakeDesktop(openResult = false)
        var unavailable: Path? = null
        val action = OpenDatabaseAction(dbFile, desktop, onUnavailable = { unavailable = it })
        action.actionPerformed(ActionEvent("", 0, ""))
        assertEquals(dbDir.toAbsolutePath(), unavailable?.toAbsolutePath())
    }

    @Test
    fun `onUnavailable does not fire on success`(@TempDir tempDir: Path) {
        val dbDir = tempDir.resolve("dbDir").also { it.createDirectories() }
        val dbFile = dbDir.resolve("scenario.db")
        val desktop = FakeDesktop()
        var unavailable: Path? = null
        val action = OpenDatabaseAction(dbFile, desktop, onUnavailable = { unavailable = it })
        action.actionPerformed(ActionEvent("", 0, ""))
        assertNull(unavailable)
    }
}
