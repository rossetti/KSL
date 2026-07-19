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

/**
 * Resolves the sibling launcher scripts inside the installed `Servers/suite/` directory. The installed
 * `ksl-server` launcher passes the two paths explicitly (`-Dksl.suite.launcher`, `-Dksl.server.launcher`);
 * failing that (a dev/classes run), the scripts are looked up next to this agent's jar. Null when neither
 * is available, so callers degrade gracefully in development.
 */
object InstallPaths {

    private val windows: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /** The directory holding this agent's jar, or null when running from compiled classes (dev/tests). */
    fun jarDir(): Path? = runCatching {
        val uri = InstallPaths::class.java.protectionDomain.codeSource.location.toURI()
        val p = Path.of(uri)
        if (p.toString().endsWith(".jar")) p.parent else null
    }.getOrNull()

    /**
     * A launcher script by base name: the explicit [property] (set by the installed launcher) if it
     * exists, else a sibling of this jar (`base` on macOS/Linux, `base.cmd` on Windows). Null if neither.
     */
    fun launcher(base: String, property: String): Path? {
        System.getProperty(property)?.takeIf { it.isNotBlank() }?.let {
            val p = Path.of(it)
            if (Files.exists(p)) return p
        }
        val dir = jarDir() ?: return null
        val f = dir.resolve(if (windows) "$base.cmd" else base)
        return if (Files.exists(f)) f else null
    }

    /** The suite server launcher (`ksl-suite`) — what the tray execs as the managed child. */
    fun suiteLauncher(): Path? = launcher("ksl-suite", "ksl.suite.launcher")

    /** This agent's own launcher (`ksl-server`) — what a Start-at-login entry runs. */
    fun serverLauncher(): Path? = launcher("ksl-server", "ksl.server.launcher")
}
