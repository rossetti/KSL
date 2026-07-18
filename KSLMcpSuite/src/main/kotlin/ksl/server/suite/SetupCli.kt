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

/**
 * The suite's client-setup CLI, over the shared KSLAgentConfig library. It writes/removes the ONE
 * `ksl-suite` MCP entry in each detected coding agent's config (Claude Desktop, Codex), so a student
 * configures a single server for all three tool surfaces. The entry launches the thin `ksl-bridge`,
 * which forwards over HTTP to this long-running suite.
 *
 * The bridge command is a deployment detail (where `ksl-bridge` is installed), so `--configure`
 * takes it explicitly via `--bridge`; the suite URL defaults to the local bind. Honors the
 * `KSL_AGENT_CONFIG_HOME` sandbox redirect for safe testing.
 */
object SetupCli {

    const val SUITE_KEY: String = "ksl-suite"
    const val DEFAULT_URL: String = "http://127.0.0.1:3001/"

    /** True if [args] requested a setup action (so `main` should not start the server). */
    fun isSetupCommand(args: Array<String>): Boolean =
        "--configure" in args || "--remove" in args

    /** Dispatch a setup command; returns the per-agent outcomes (empty if no agent detected). */
    fun run(args: Array<String>): List<AgentConfigurator.ConfigResult> = when {
        "--remove" in args -> AgentConfigurator.remove(SUITE_KEY)
        "--configure" in args -> {
            val bridge = args.valueAfter("--bridge")
                ?: error("--configure requires --bridge <command that launches ksl-bridge>")
            val url = args.valueAfter("--url") ?: DEFAULT_URL
            AgentConfigurator.configure(SUITE_KEY, LaunchSpec(bridge, listOf("--url", url)))
        }
        else -> emptyList()
    }

    /** Run a setup command and print a human-readable report to stdout. */
    fun runAndReport(args: Array<String>) {
        val results = try {
            run(args)
        } catch (e: IllegalStateException) {
            System.err.println(e.message)
            return
        }
        if (results.isEmpty()) {
            println("No coding agents detected (no Claude Desktop or Codex config directory).")
        } else {
            results.forEach { println("${it.agent}: ${it.action}  ->  ${it.path}") }
        }
    }

    private fun Array<String>.valueAfter(flag: String): String? {
        val i = indexOf(flag)
        return if (i >= 0 && i + 1 < size) this[i + 1] else null
    }
}
