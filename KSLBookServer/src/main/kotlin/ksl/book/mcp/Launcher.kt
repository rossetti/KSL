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

package ksl.book.mcp

import java.io.File
import kotlin.system.exitProcess

/**
 * Single-jar entrypoint for the self-contained KSL book MCP server - the
 * "idiot-proof" student jar (`java -jar ksl-book-mcp.jar ...`), ported from
 * KSLServerMcp's Launcher. Dispatches by mode:
 *
 *   --stdio    run the MCP server over stdin/stdout (what a coding agent launches);
 *   --doctor   self-test: print the version and the bundled content statistics;
 *   --setup    detect installed agents and wire them to this jar (console);
 *   --remove   undo --setup (remove the ksl-book entry from detected agents);
 *   --gui      open the setup window explicitly;
 *   --version  print the version.
 *
 * Default (no args / double-click) opens the GUI when a display is available,
 * else runs console setup. Only `--stdio` writes to stdout (the MCP channel).
 */
fun main(args: Array<String>) {
    // The dist launcher passes -Dlogback.configurationFile; `java -jar` does not,
    // so pin the stderr-only config here (before any logger initializes) to keep
    // log output from corrupting the MCP stdio channel.
    if (System.getProperty("logback.configurationFile") == null) {
        System.setProperty("logback.configurationFile", "logback-ksl-book-mcp.xml")
    }
    when (args.firstOrNull()) {
        "--stdio", "stdio" -> runStdioServer()
        "--doctor", "doctor" -> println(buildDoctorReport())
        "--setup", "setup" -> println(buildSetupReport(jarPath(), AgentSetup.configureDetected(jarPath())))
        "--remove", "remove" -> println(buildRemoveReport(AgentSetup.removeDetected()))
        "--gui", "gui" -> SetupGui.launch(jarPath())
        "--version", "-v", "version" -> println(BuildInfo.version)
        null -> SetupGui.launch(jarPath()) // double-click → GUI (console fallback if headless)
        "--help", "-h", "help" -> usage()
        else -> {
            System.err.println("Unknown option: ${args.first()}")
            usage()
            exitProcess(2)
        }
    }
}

/** Absolute path to this running jar (so the agent config / setup can launch it). */
internal fun jarPath(): String =
    runCatching {
        File(object {}.javaClass.protectionDomain.codeSource.location.toURI()).absolutePath
    }.getOrDefault("<path-to>/ksl-book-mcp.jar")

// ---- shared report builders (used by the console modes and the GUI) ----

/** In-process self-test: load the bundled content and build the search index once. */
internal fun buildDoctorReport(): String {
    val loadStart = System.currentTimeMillis()
    val store = BookStore.load()
    val loadMs = System.currentTimeMillis() - loadStart
    val indexStart = System.currentTimeMillis()
    BookSearch(store).search("simulation", 1)
    val indexMs = System.currentTimeMillis() - indexStart
    return buildString {
        appendLine("KSL Book MCP server - doctor")
        appendLine("  version:   ${BuildInfo.version}")
        appendLine("  chunks:    ${store.chunks.size} (${store.chunks.count { it.number == null }} front matter)")
        appendLine("  exercises: ${store.exercises.size}")
        appendLine("  chapters:  ${store.chapters.joinToString(" ") { it.number }}")
        appendLine("  timing:    content loaded in $loadMs ms, search index built in $indexMs ms")
        append("  OK - the server runs and the full book content is bundled.")
    }
}

internal fun buildSetupReport(jar: String, results: List<SetupResult>): String = buildString {
    appendLine("KSL Book MCP server - setup")
    appendLine()
    if (results.isNotEmpty()) {
        appendLine("Configured your MCP agent(s) automatically (original config backed up):")
        for (r in results) {
            appendLine("  - ${r.agent}: ${r.action}")
            appendLine("      ${r.path}")
        }
        appendLine()
        appendLine("Restart the agent(s) above, then ask it: \"what does the KSL book cover?\".")
        appendLine()
        appendLine("For any OTHER agent, add this to its MCP servers config:")
    } else {
        appendLine("No supported agent detected (Claude Desktop / Codex).")
        appendLine("Add this to your agent's MCP servers configuration:")
    }
    appendLine()
    appendLine(AgentSetup.universalSnippet(jar))
    appendLine()
    append("Self-test any time:  java -jar \"$jar\" --doctor")
}

internal fun buildRemoveReport(results: List<SetupResult>): String = buildString {
    appendLine("KSL Book MCP server - remove")
    appendLine()
    if (results.isNotEmpty()) {
        for (r in results) {
            appendLine("  - ${r.agent}: ${r.action}")
            appendLine("      ${r.path}")
        }
        appendLine()
        append("Restart any affected agent for the change to take effect.")
    } else {
        append("No supported agent detected (Claude Desktop / Codex); nothing to remove.")
    }
}

private fun usage() {
    println("Usage: java -jar ksl-book-mcp.jar [--stdio | --doctor | --setup | --remove | --gui | --version]")
}
