package ksl.app.swing.common.workspace

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.swing.Swing
import ksl.app.settings.UserSettingsStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import javax.swing.JMenuItem
import javax.swing.SwingUtilities
import kotlin.io.path.createDirectories
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecentWorkingDirectoriesMenuTest {

    @Test
    fun `empty store renders a single disabled placeholder item`(@TempDir tempDir: Path) {
        val store = UserSettingsStore(
            settingsDir = tempDir.resolve("settings"),
            userHome = tempDir.resolve("home").also { it.createDirectories() }
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        val menu = onEdt { RecentWorkingDirectoriesMenu(store, scope) }

        try {
            onEdt {
                assertEquals(1, menu.itemCount)
                val only = menu.getItem(0)
                assertFalse(only.isEnabled, "placeholder must be disabled")
                assertTrue(only.text.contains("no recent", ignoreCase = true))
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `populated store renders one item per recent directory in order`(@TempDir tempDir: Path) {
        val store = UserSettingsStore(
            settingsDir = tempDir.resolve("settings"),
            userHome = tempDir.resolve("home").also { it.createDirectories() }
        )
        val a = tempDir.resolve("a").also { it.createDirectories() }
        val b = tempDir.resolve("b").also { it.createDirectories() }
        store.setCurrentDirectory(a)
        store.setCurrentDirectory(b)
        // recent list is now [b, a] (most-recent first)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        val menu = onEdt { RecentWorkingDirectoriesMenu(store, scope) }

        try {
            onEdt {
                assertEquals(2, menu.itemCount)
                assertEquals(b.toAbsolutePath().normalize().toString(), menu.getItem(0).text)
                assertEquals(a.toAbsolutePath().normalize().toString(), menu.getItem(1).text)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `clicking a menu item promotes that directory to current`(@TempDir tempDir: Path) {
        val store = UserSettingsStore(
            settingsDir = tempDir.resolve("settings"),
            userHome = tempDir.resolve("home").also { it.createDirectories() }
        )
        val a = tempDir.resolve("a").also { it.createDirectories() }
        val b = tempDir.resolve("b").also { it.createDirectories() }
        store.setCurrentDirectory(a)
        store.setCurrentDirectory(b)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        val menu = onEdt { RecentWorkingDirectoriesMenu(store, scope) }

        try {
            onEdt {
                val olderItem: JMenuItem = menu.getItem(1)  // points at 'a'
                olderItem.doClick()
            }
            assertEquals(
                a.toAbsolutePath().normalize().toString(),
                store.settings.value.workspace.currentDirectory
            )
        } finally {
            scope.cancel()
        }
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
