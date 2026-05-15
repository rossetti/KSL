package ksl.app.settings

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioural tests for [UserSettingsStore].  All instances are
 * redirected at `@TempDir` directories so the user's real `~/.ksl/` is
 * untouched.
 */
class UserSettingsStoreTest {

    @Test
    fun `fresh store with no settings file uses defaults`(@TempDir tempDir: Path) {
        val store = UserSettingsStore(settingsDir = tempDir.resolve("settings"), userHome = tempDir.resolve("home"))
        assertEquals(UserSettings(), store.settings.value)
    }

    @Test
    fun `setCurrentDirectory persists to disk and updates the flow`(@TempDir tempDir: Path) {
        val settingsDir = tempDir.resolve("settings")
        val home = tempDir.resolve("home").also { it.createDirectories() }
        val workspace = tempDir.resolve("ws").also { it.createDirectories() }

        val store = UserSettingsStore(settingsDir = settingsDir, userHome = home)
        store.setCurrentDirectory(workspace)

        val settingsFile = settingsDir.resolve(UserSettingsStore.SETTINGS_FILENAME)
        assertTrue(settingsFile.exists(), "settings file should be written")
        assertEquals(workspace.toAbsolutePath().normalize().toString(), store.settings.value.workspace.currentDirectory)
        assertEquals(listOf(workspace.toAbsolutePath().normalize().toString()), store.settings.value.workspace.recent.directories)
    }

    @Test
    fun `setCurrentDirectory survives a store reload`(@TempDir tempDir: Path) {
        val settingsDir = tempDir.resolve("settings")
        val home = tempDir.resolve("home").also { it.createDirectories() }
        val workspace = tempDir.resolve("ws").also { it.createDirectories() }

        UserSettingsStore(settingsDir = settingsDir, userHome = home).setCurrentDirectory(workspace)
        val reloaded = UserSettingsStore(settingsDir = settingsDir, userHome = home)
        assertEquals(workspace.toAbsolutePath().normalize().toString(), reloaded.settings.value.workspace.currentDirectory)
    }

    @Test
    fun `setCurrentDirectory dedups recent list and bumps to front`(@TempDir tempDir: Path) {
        val settingsDir = tempDir.resolve("settings")
        val home = tempDir.resolve("home").also { it.createDirectories() }
        val a = tempDir.resolve("a").also { it.createDirectories() }
        val b = tempDir.resolve("b").also { it.createDirectories() }

        val store = UserSettingsStore(settingsDir = settingsDir, userHome = home)
        store.setCurrentDirectory(a)
        store.setCurrentDirectory(b)
        store.setCurrentDirectory(a)

        val recent = store.settings.value.workspace.recent.directories
        assertEquals(2, recent.size)
        assertEquals(a.toAbsolutePath().normalize().toString(), recent[0])
        assertEquals(b.toAbsolutePath().normalize().toString(), recent[1])
    }

    @Test
    fun `recent list is capped at RECENT_LIMIT entries`(@TempDir tempDir: Path) {
        val settingsDir = tempDir.resolve("settings")
        val home = tempDir.resolve("home").also { it.createDirectories() }
        val store = UserSettingsStore(settingsDir = settingsDir, userHome = home)

        for (i in 1..(UserSettingsStore.RECENT_LIMIT + 3)) {
            val dir = tempDir.resolve("ws$i").also { it.createDirectories() }
            store.setCurrentDirectory(dir)
        }
        assertEquals(UserSettingsStore.RECENT_LIMIT, store.settings.value.workspace.recent.directories.size)
    }

    @Test
    fun `addRecentDirectory does not change currentDirectory`(@TempDir tempDir: Path) {
        val settingsDir = tempDir.resolve("settings")
        val home = tempDir.resolve("home").also { it.createDirectories() }
        val current = tempDir.resolve("current").also { it.createDirectories() }
        val other = tempDir.resolve("other").also { it.createDirectories() }

        val store = UserSettingsStore(settingsDir = settingsDir, userHome = home)
        store.setCurrentDirectory(current)
        store.addRecentDirectory(other)

        assertEquals(current.toAbsolutePath().normalize().toString(), store.settings.value.workspace.currentDirectory)
        assertEquals(
            listOf(other.toAbsolutePath().normalize().toString(), current.toAbsolutePath().normalize().toString()),
            store.settings.value.workspace.recent.directories
        )
    }

    @Test
    fun `activeWorkspace falls back to userHome when no currentDirectory is set`(@TempDir tempDir: Path) {
        val home = tempDir.resolve("home").also { it.createDirectories() }
        val store = UserSettingsStore(settingsDir = tempDir.resolve("settings"), userHome = home)
        assertEquals(home, store.activeWorkspace())
    }

    @Test
    fun `activeWorkspace evicts a stale currentDirectory`(@TempDir tempDir: Path) {
        val settingsDir = tempDir.resolve("settings").also { it.createDirectories() }
        val home = tempDir.resolve("home").also { it.createDirectories() }
        val stale = tempDir.resolve("stale-ws")  // never created

        // Hand-write a settings file referencing the missing directory.
        settingsDir.resolve(UserSettingsStore.SETTINGS_FILENAME).writeText(
            """
            [workspace]
            currentDirectory = "${stale.toAbsolutePath().normalize()}"
            [workspace.recent]
            directories = ["${stale.toAbsolutePath().normalize()}"]
            """.trimIndent()
        )

        val store = UserSettingsStore(settingsDir = settingsDir, userHome = home)
        assertEquals(home, store.activeWorkspace())
        assertNull(store.settings.value.workspace.currentDirectory)
        assertTrue(store.settings.value.workspace.recent.directories.none { it.contains("stale-ws") })
    }

    @Test
    fun `unwritable settings dir does not throw and retains in-memory state`(@TempDir tempDir: Path) {
        // Use a settingsDir that cannot be created (a path under a file, not a directory).
        val blocker = tempDir.resolve("blocker").also { it.writeText("not a directory") }
        val settingsDir = blocker.resolve("settings")  // cannot be created — blocker is a file
        val home = tempDir.resolve("home").also { it.createDirectories() }
        val workspace = tempDir.resolve("ws").also { it.createDirectories() }

        val store = UserSettingsStore(settingsDir = settingsDir, userHome = home)
        store.setCurrentDirectory(workspace)   // must not throw
        assertEquals(workspace.toAbsolutePath().normalize().toString(), store.settings.value.workspace.currentDirectory)
    }

    @Test
    fun `corrupt settings file falls back to defaults`(@TempDir tempDir: Path) {
        val settingsDir = tempDir.resolve("settings").also { it.createDirectories() }
        settingsDir.resolve(UserSettingsStore.SETTINGS_FILENAME).writeText("this is not [toml")
        val home = tempDir.resolve("home").also { it.createDirectories() }

        val store = UserSettingsStore(settingsDir = settingsDir, userHome = home)
        assertEquals(UserSettings(), store.settings.value)
    }

    @Test
    fun `canonical absolute path is stored regardless of input form`(@TempDir tempDir: Path) {
        val home = tempDir.resolve("home").also { it.createDirectories() }
        val workspace = tempDir.resolve("ws").also { it.createDirectories() }
        val relative = workspace.toAbsolutePath().resolve("./../ws")  // contains a '..' segment

        val store = UserSettingsStore(settingsDir = tempDir.resolve("settings"), userHome = home)
        store.setCurrentDirectory(relative)
        assertEquals(workspace.toAbsolutePath().normalize().toString(), store.settings.value.workspace.currentDirectory)
        assertNotEquals(relative.toString(), store.settings.value.workspace.currentDirectory)
    }
}
