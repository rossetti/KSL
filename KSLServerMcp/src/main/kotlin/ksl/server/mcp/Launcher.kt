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

package ksl.server.mcp

import ksl.service.capability.run.BundleDirectoryWatcher
import ksl.service.capability.run.BundleRegistry
import ksl.service.config.BuildInfo
import ksl.service.config.ServerConfig
import java.io.File
import kotlin.system.exitProcess

/**
 * Single-jar entrypoint for the self-contained KSL MCP server - the "idiot-proof"
 * student jar (`java -jar ksl-mcp.jar ...`). Dispatches by mode:
 *
 *   --stdio    run the MCP server over stdin/stdout (what a coding agent launches);
 *   --doctor   self-test: print the version and the model bundles found;
 *   --setup    detect installed agents and wire them to this jar (console);
 *   --remove   undo --setup (remove the ksl entry from detected agents);
 *   --gui      open the setup window explicitly;
 *   --version  print the version.
 *
 * Default (no args / double-click) opens the GUI when a display is available,
 * else runs console setup. Only `--stdio` writes to stdout (the MCP channel).
 */
fun main(args: Array<String>) {
    // The dist launcher passes -Dlogback.configurationFile; `java -jar` does not,
    // so pin the stderr-only config here (before any logger initializes) to keep
    // KSLCore's stdout appender from corrupting the MCP stdio channel.
    if (System.getProperty("logback.configurationFile") == null) {
        System.setProperty("logback.configurationFile", "logback-ksl-mcp.xml")
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
    }.getOrDefault("<path-to>/ksl-mcp.jar")

// ---- shared report builders (used by the console modes and the GUI) ----

/** In-process self-test: build the same registry the server uses and report what it finds. */
internal fun buildDoctorReport(): String {
    val config = ServerConfig.load()
    val registry = BundleRegistry.empty()
    BundleDirectoryWatcher(registry, config.bundleDirs(), config.pollInterval()).scanOnce()
    val bundles = registry.listBundles()
    return buildString {
        appendLine("KSL MCP server - doctor")
        appendLine("  version:     ${BuildInfo.version}")
        appendLine("  bundle dirs: ${config.bundleDirs().joinToString()}")
        appendLine("  bundles:     ${bundles.size}")
        for (b in bundles) {
            val note = b.notice?.let { "  ($it)" } ?: ""
            appendLine("    - ${b.bundleId}  models=${b.modelIds}$note")
        }
        for (c in registry.conflicts()) {
            appendLine("    conflict: ${c.bundleId} active=${c.activeSource} shadowed=${c.shadowedSources}")
        }
        append(
            if (bundles.isNotEmpty()) {
                "  OK - the server runs and ${bundles.size} model bundle(s) are available."
            } else {
                "  WARNING - the server runs but no model bundles were found in ${config.bundlesDir()}."
            },
        )
    }.also { registry.close() }
}

internal fun buildSetupReport(jar: String, results: List<SetupResult>): String = buildString {
    appendLine("KSL MCP server - setup")
    appendLine()
    if (results.isNotEmpty()) {
        appendLine("Configured your MCP agent(s) automatically (original config backed up):")
        for (r in results) {
            appendLine("  - ${r.agent}: ${r.action}")
            appendLine("      ${r.path}")
        }
        appendLine()
        appendLine("Restart the agent(s) above, then ask it: \"list the KSL models\".")
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
    appendLine("KSL MCP server - remove")
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
    println("Usage: java -jar ksl-mcp.jar [--stdio | --doctor | --setup | --remove | --gui | --version]")
}
