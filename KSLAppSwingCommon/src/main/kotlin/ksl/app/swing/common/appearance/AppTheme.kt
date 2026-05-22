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

package ksl.app.swing.common.appearance

/**
 *  Visual appearance preference for a KSL Swing app.
 *
 *  - [LIGHT] / [DARK] force an explicit theme.
 *  - [SYSTEM] follows the host OS's current preference.  On macOS the
 *    detection runs `defaults read -g AppleInterfaceStyle` as a
 *    cheap one-shot; on other platforms it falls back to [LIGHT]
 *    (a richer cross-platform detection can be added later via
 *    JNA without changing this enum).
 *
 *  Used by [LookAndFeel.install] at app bootstrap and by
 *  [ThemeMenu] for the user-facing toggle.  The enum is intentionally
 *  String-named so it can later be persisted into
 *  `ksl.app.settings.UserSettings` without dragging a Swing
 *  dependency into KSLCore (the persisted form would be the enum
 *  name, parsed back via [valueOf]).
 */
enum class AppTheme {
    LIGHT,
    DARK,
    SYSTEM;

    /**
     *  Resolve [SYSTEM] to a concrete [LIGHT] / [DARK] choice by
     *  probing the host OS.  Other values pass through unchanged.
     *  Safe to call from any thread.
     */
    fun resolve(): AppTheme = when (this) {
        LIGHT, DARK -> this
        SYSTEM -> if (osPrefersDark()) DARK else LIGHT
    }

    private companion object {
        /**
         *  Best-effort dark-mode probe.  Returns `false` on any
         *  platform / detection failure — callers default to light
         *  rather than guess wrong.
         */
        fun osPrefersDark(): Boolean {
            val osName = System.getProperty("os.name").orEmpty().lowercase()
            return when {
                osName.contains("mac") -> macPrefersDark()
                // Windows / Linux detection requires JNA or a
                // subprocess to GSettings; deferred until a real user
                // need surfaces.  Default to light.
                else -> false
            }
        }

        private fun macPrefersDark(): Boolean = runCatching {
            val process = ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val finished = process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@runCatching false
            }
            // The key is absent when the system is in light mode —
            // `defaults` exits non-zero with "domain/default pair of
            // (kCFPreferencesAnyApplication, AppleInterfaceStyle) does
            // not exist" on stderr.  In dark mode it exits 0 with
            // "Dark" on stdout.
            process.exitValue() == 0 && output.equals("Dark", ignoreCase = true)
        }.getOrDefault(false)
    }
}
