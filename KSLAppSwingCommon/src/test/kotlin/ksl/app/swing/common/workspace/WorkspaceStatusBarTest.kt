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
import javax.swing.SwingUtilities
import kotlin.io.path.createDirectories
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkspaceStatusBarTest {

    @Test
    fun `bar reports the current active workspace`(@TempDir tempDir: Path) {
        val home = tempDir.resolve("home").also { it.createDirectories() }
        val workspace = tempDir.resolve("ws").also { it.createDirectories() }
        val store = UserSettingsStore(settingsDir = tempDir.resolve("settings"), userHome = home)
        store.setCurrentDirectory(workspace)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val bar = onEdt { WorkspaceStatusBar(store, scope) }
            assertEquals(workspace.toAbsolutePath().normalize(), bar.currentDisplayedWorkspace())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `bar tooltip carries the full path even when text is abbreviated`(@TempDir tempDir: Path) {
        val home = tempDir.resolve("home").also { it.createDirectories() }
        val longSegment = "a".repeat(50)
        val workspace = tempDir.resolve(longSegment).also { it.createDirectories() }
        val store = UserSettingsStore(settingsDir = tempDir.resolve("settings"), userHome = home)
        store.setCurrentDirectory(workspace)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val bar = onEdt { WorkspaceStatusBar(store, scope, maxLen = 30) }
            val text = onEdt { bar.text }
            val tip = onEdt { bar.toolTipText }
            assertTrue(
                text.startsWith(".../") || text.startsWith("~/.../"),
                "expected abbreviation marker, got: $text"
            )
            assertTrue(text.length < tip.length, "abbreviated text must be shorter than the full path; text=$text tip=$tip")
            assertEquals(workspace.toAbsolutePath().normalize().toString(), tip)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `bar falls back to userHome when no workspace is set`(@TempDir tempDir: Path) {
        val home = tempDir.resolve("home").also { it.createDirectories() }
        val store = UserSettingsStore(settingsDir = tempDir.resolve("settings"), userHome = home)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val bar = onEdt { WorkspaceStatusBar(store, scope) }
            assertEquals(home, bar.currentDisplayedWorkspace())
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
