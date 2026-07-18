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

package ksl.agent.config

import java.io.File

/** How a client should start a server: an executable command plus the arguments to pass it. */
data class LaunchSpec(val command: String, val args: List<String>)

/**
 * Absolute path to the running JVM's `java` launcher (falls back to bare `java`). GUI-launched
 * agents (notably on macOS) often don't inherit the shell `PATH`, so an absolute path is safer than
 * a bare `java` when a caller builds a `java -jar` launch spec.
 */
fun javaCommand(): String {
    val javaHome = System.getProperty("java.home") ?: return "java"
    val exe = if (osName().contains("win")) "java.exe" else "java"
    val candidate = File(File(javaHome, "bin"), exe)
    return if (candidate.exists()) candidate.path else exe
}

/**
 * Route a wrapper-script command through `cmd.exe` for Windows MCP clients that spawn with a bare
 * CreateProcess and no shell (Electron/Node, e.g. Claude Desktop). Windows cannot execute a `.cmd`
 * or `.bat` that way, so it is launched via `cmd.exe /c` with the original command and its args
 * appended. A `java`/`.exe` command spawns directly and is returned unchanged.
 */
fun shellWrapForWindows(spec: LaunchSpec): LaunchSpec {
    val script = spec.command.endsWith(".cmd", ignoreCase = true) ||
        spec.command.endsWith(".bat", ignoreCase = true)
    return if (osName().contains("win") && script)
        LaunchSpec("cmd.exe", listOf("/c", spec.command) + spec.args)
    else spec
}
