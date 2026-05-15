package ksl.app.swing.common.workspace

import ksl.app.settings.UserSettingsStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Component
import java.awt.event.ActionEvent
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SetWorkingDirectoryActionTest {

    @Test
    fun `chooser-selected path is written through the store`(@TempDir tempDir: Path) {
        val home = tempDir.resolve("home").also { it.createDirectories() }
        val chosen = tempDir.resolve("chosen").also { it.createDirectories() }
        val store = UserSettingsStore(settingsDir = tempDir.resolve("settings"), userHome = home)

        val action = SetWorkingDirectoryAction(
            store = store,
            chooser = { _: Component?, _: Path -> chosen }
        )
        action.actionPerformed(ActionEvent("", 0, ""))

        assertEquals(
            chosen.toAbsolutePath().normalize().toString(),
            store.settings.value.workspace.currentDirectory
        )
    }

    @Test
    fun `cancelled chooser leaves the store unchanged`(@TempDir tempDir: Path) {
        val home = tempDir.resolve("home").also { it.createDirectories() }
        val store = UserSettingsStore(settingsDir = tempDir.resolve("settings"), userHome = home)

        val action = SetWorkingDirectoryAction(
            store = store,
            chooser = { _: Component?, _: Path -> null }
        )
        action.actionPerformed(ActionEvent("", 0, ""))

        assertNull(store.settings.value.workspace.currentDirectory)
    }

    @Test
    fun `chooser receives the active workspace as its starting directory`(@TempDir tempDir: Path) {
        val home = tempDir.resolve("home").also { it.createDirectories() }
        val current = tempDir.resolve("current").also { it.createDirectories() }
        val store = UserSettingsStore(settingsDir = tempDir.resolve("settings"), userHome = home)
        store.setCurrentDirectory(current)

        var startSeen: Path? = null
        val action = SetWorkingDirectoryAction(
            store = store,
            chooser = { _: Component?, start: Path ->
                startSeen = start
                null
            }
        )
        action.actionPerformed(ActionEvent("", 0, ""))

        assertNotNull(startSeen)
        assertEquals(current.toAbsolutePath().normalize(), startSeen!!.toAbsolutePath().normalize())
    }
}
