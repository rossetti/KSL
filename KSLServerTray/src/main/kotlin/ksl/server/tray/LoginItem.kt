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

package ksl.server.tray

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * "Start at login" as a per-OS autostart entry that runs the installed `ksl-server` launcher — a macOS
 * LaunchAgent plist, a Windows Startup-folder script, or an XDG autostart desktop entry. Pure filesystem
 * writes (best-effort, never throws) with an injected [home] and [os] so the path logic unit-tests
 * without touching the real user profile.
 */
class LoginItem(
    private val launcher: Path?,
    private val os: String = System.getProperty("os.name").orEmpty().lowercase(),
    private val home: Path = Path.of(System.getProperty("user.home")),
) {

    private val label = "io.github.rossetti.ksl-server"

    /** The autostart entry file this platform uses. */
    fun entryPath(): Path = when {
        os.contains("mac") -> home.resolve("Library/LaunchAgents/$label.plist")
        os.contains("win") ->
            home.resolve("AppData/Roaming/Microsoft/Windows/Start Menu/Programs/Startup/ksl-server.cmd")
        else -> home.resolve(".config/autostart/ksl-server.desktop")
    }

    /** True if the autostart entry currently exists. */
    fun isEnabled(): Boolean = Files.exists(entryPath())

    /**
     * Create ([enabled] true) or remove the autostart entry; returns the resulting enabled state. A no-op
     * returning false when there is no launcher to point at (a dev run), and best-effort on any I/O error.
     */
    fun setEnabled(enabled: Boolean): Boolean {
        val cmd = launcher ?: return false
        return runCatching {
            val path = entryPath()
            if (enabled) {
                Files.createDirectories(path.parent)
                Files.writeString(
                    path, contentFor(cmd.toAbsolutePath().toString()),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                )
                if (!os.contains("win")) path.toFile().setExecutable(true)
            } else {
                Files.deleteIfExists(path)
            }
            isEnabled()
        }.getOrDefault(isEnabled())
    }

    private fun contentFor(cmd: String): String = when {
        os.contains("mac") ->
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0"><dict>
              <key>Label</key><string>$label</string>
              <key>ProgramArguments</key><array><string>$cmd</string></array>
              <key>RunAtLoad</key><true/>
            </dict></plist>
            """.trimIndent() + "\n"
        os.contains("win") -> "@echo off\r\nstart \"\" \"$cmd\"\r\n"
        else ->
            """
            [Desktop Entry]
            Type=Application
            Name=KSL Server
            Exec=$cmd
            X-GNOME-Autostart-enabled=true
            """.trimIndent() + "\n"
    }
}
