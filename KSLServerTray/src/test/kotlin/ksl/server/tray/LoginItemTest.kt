package ksl.server.tray

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoginItemTest {

    @Test
    @DisplayName("mac: enable writes a RunAtLoad LaunchAgent plist naming the launcher; disable removes it")
    fun macPlist(@TempDir home: Path) {
        val launcher = Files.createFile(home.resolve("ksl-server"))
        val item = LoginItem(launcher, os = "mac os x", home = home)
        assertFalse(item.isEnabled())
        assertTrue(item.setEnabled(true))
        val plist = home.resolve("Library/LaunchAgents/io.github.rossetti.ksl-server.plist")
        assertTrue(Files.exists(plist))
        val text = Files.readString(plist)
        assertTrue("RunAtLoad" in text && launcher.toAbsolutePath().toString() in text)
        assertFalse(item.setEnabled(false))
        assertFalse(Files.exists(plist))
    }

    @Test
    @DisplayName("windows: enable writes a Startup-folder cmd that starts the launcher")
    fun winStartup(@TempDir home: Path) {
        val launcher = Files.createFile(home.resolve("ksl-server.cmd"))
        val item = LoginItem(launcher, os = "windows 11", home = home)
        assertTrue(item.setEnabled(true))
        val cmd = home.resolve("AppData/Roaming/Microsoft/Windows/Start Menu/Programs/Startup/ksl-server.cmd")
        assertTrue(Files.exists(cmd))
        assertTrue("start" in Files.readString(cmd))
    }

    @Test
    @DisplayName("linux: enable writes an XDG autostart desktop entry")
    fun linuxAutostart(@TempDir home: Path) {
        val launcher = Files.createFile(home.resolve("ksl-server"))
        val item = LoginItem(launcher, os = "linux", home = home)
        assertTrue(item.setEnabled(true))
        val desktop = home.resolve(".config/autostart/ksl-server.desktop")
        assertTrue(Files.exists(desktop))
        assertTrue("[Desktop Entry]" in Files.readString(desktop))
    }

    @Test
    @DisplayName("no launcher (a dev run) → enabling is a no-op returning false")
    fun noLauncher(@TempDir home: Path) {
        val item = LoginItem(null, os = "mac os x", home = home)
        assertFalse(item.setEnabled(true))
        assertFalse(item.isEnabled())
    }
}
