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

package ksl.server.suite

import ksl.agent.config.AgentConfigurator
import ksl.agent.config.LaunchSpec
import ksl.agent.config.javaCommand
import java.nio.file.Files
import java.nio.file.Path

/**
 * The suite's client-setup logic, over the shared KSLAgentConfig library. It writes/removes the ONE
 * `ksl-suite` MCP entry in each detected coding agent (Claude Desktop, Codex, Cursor, Windsurf), so a student configures
 * a single server for every tool surface. The entry launches the thin `ksl-bridge`, which forwards
 * over HTTP to this long-running suite.
 *
 * The **bridge command is auto-detected** next to the running suite jar — the installed `ksl-bridge`
 * launcher, else `java -jar ksl-bridge.jar` — so the console's Configure button and a plain
 * `--configure` need no bridge path from the user. An explicit override is accepted (`--bridge`, or the
 * console's Advanced field) for a dev jar that isn't co-located with the bridge. Honors the
 * `KSL_AGENT_CONFIG_HOME` sandbox redirect for safe testing.
 */
object SetupCli {

    const val SUITE_KEY: String = "ksl-suite"
    const val DEFAULT_URL: String = "http://127.0.0.1:3001/"

    /** True if [args] requested a setup action (so `main` should not start the server). */
    fun isSetupCommand(args: Array<String>): Boolean =
        "--configure" in args || "--remove" in args

    /** Dispatch a CLI setup command; returns the per-agent outcomes (empty if no agent detected). */
    fun run(args: Array<String>): List<AgentConfigurator.ConfigResult> = when {
        "--remove" in args -> remove()
        "--configure" in args -> configure(args.valueAfter("--bridge"), args.valueAfter("--url") ?: DEFAULT_URL)
        else -> emptyList()
    }

    /**
     * Write the `ksl-suite` entry using an explicit [bridgeOverride], or — when null/blank — the bundled
     * bridge auto-detected next to the suite jar. The one-click console Configure and plain `--configure`
     * both rely on the default; throws only when neither an override nor a co-located bridge exists.
     */
    fun configure(bridgeOverride: String?, suiteUrl: String = DEFAULT_URL): List<AgentConfigurator.ConfigResult> {
        val spec = if (!bridgeOverride.isNullOrBlank()) {
            LaunchSpec(bridgeOverride, listOf("--url", suiteUrl))
        } else {
            defaultBridgeSpec(suiteUrl)
                ?: error("could not find the bundled ksl-bridge next to the suite; supply a bridge command")
        }
        return AgentConfigurator.configure(SUITE_KEY, spec)
    }

    /** Remove the `ksl-suite` entry from every detected agent. */
    fun remove(): List<AgentConfigurator.ConfigResult> = AgentConfigurator.remove(SUITE_KEY)

    /**
     * The bridge launch spec auto-detected beside the running suite jar: the installed `ksl-bridge`
     * launcher script when present (a single command), else `java -jar ksl-bridge.jar`. Null when the
     * suite runs from compiled classes (dev / tests) or no bridge sits beside it.
     */
    fun defaultBridgeSpec(suiteUrl: String = DEFAULT_URL): LaunchSpec? {
        val dir = suiteJarDir() ?: return null
        val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        val launcher = dir.resolve(if (windows) "ksl-bridge.cmd" else "ksl-bridge")
        if (Files.exists(launcher)) return LaunchSpec(launcher.toString(), listOf("--url", suiteUrl))
        val jar = dir.resolve("ksl-bridge.jar")
        if (Files.exists(jar)) return LaunchSpec(javaCommand(), listOf("-jar", jar.toString(), "--url", suiteUrl))
        return null
    }

    /** The directory holding the running suite jar, or null when running from classes (dev / tests). */
    private fun suiteJarDir(): Path? = runCatching {
        val uri = SetupCli::class.java.protectionDomain.codeSource.location.toURI()
        val path = Path.of(uri)
        if (path.toString().endsWith(".jar")) path.parent else null
    }.getOrNull()

    /** Run a setup command and print a human-readable report to stdout. */
    fun runAndReport(args: Array<String>) {
        val results = try {
            run(args)
        } catch (e: IllegalStateException) {
            System.err.println(e.message)
            return
        }
        if (results.isEmpty()) {
            println("No coding agents detected (no Claude Desktop, Codex, Cursor, or Windsurf config directory).")
        } else {
            results.forEach { println("${it.agent}: ${it.action}  ->  ${it.path}") }
        }
    }

    private fun Array<String>.valueAfter(flag: String): String? {
        val i = indexOf(flag)
        return if (i >= 0 && i + 1 < size) this[i + 1] else null
    }
}
