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

package ksl.app.servers

import java.nio.file.Files
import java.nio.file.Path

/**
 * GUI-independent brain of the KSL Server Manager. Wraps the two tested cores — `SuiteProcessManager`
 * (process lifecycle + health) and `SuiteConfigurator` (client config) — into user-facing actions
 * that each return a human-readable report string the frame shows. No Swing here, so it is testable.
 *
 * The suite jar and bridge command are resolved from `ksl.suite.jar` / `ksl.bridge.command` system
 * properties for now; Phase 6 wires them from the installed suite layout.
 */
class ServerManagerController(
    val appName: String,
    private val suiteJar: Path? = defaultSuiteJar(),
    private val bridgeCommand: String? = defaultBridgeCommand(),
    private val suiteUrl: String = DEFAULT_SUITE_URL,
    private val healthUrl: String = SuiteProcessManager.DEFAULT_HEALTH_URL,
) {

    /** Health of the suite + the live KSL process inventory. */
    fun statusReport(): String {
        val health = SuiteProcessManager.health(healthUrl)
        val procs = SuiteProcessManager.findKslProcesses().sortedBy { it.kind }
        return buildString {
            appendLine("Suite:  $health   ($healthUrl)")
            appendLine("Bridge: ${bridgeCommand ?: "(not set — pass -Dksl.bridge.command=...)"}")
            appendLine("Jar:    ${suiteJar ?: "(not set — pass -Dksl.suite.jar=...)"}")
            appendLine()
            appendLine("KSL MCP processes (${procs.size}):")
            if (procs.isEmpty()) appendLine("  (none)")
            procs.forEach { appendLine("  pid=${it.pid}  ${it.kind}${if (it.isOrphan) "   [orphan]" else ""}") }
        }
    }

    /** Write the one `ksl-suite` entry (via the bridge) into every detected coding agent. */
    fun configureClients(): String {
        val cmd = bridgeCommand ?: return "Cannot configure: the ksl-bridge command is not set."
        val results = SuiteConfigurator.configure(cmd, suiteUrl)
        if (results.isEmpty()) return "No coding agent detected (Claude Desktop / Codex)."
        return "Configured the KSL suite:\n" +
            results.joinToString("\n") { "  ${it.agent}: ${it.action}\n    ${it.path}" } +
            "\n\nRestart the agent for the change to take effect."
    }

    /** Remove the `ksl-suite` entry from every detected coding agent. */
    fun removeConfig(): String {
        val results = SuiteConfigurator.remove()
        if (results.isEmpty()) return "No coding agent config found to change."
        return "Removed the KSL suite entry:\n" + results.joinToString("\n") { "  ${it.agent}: ${it.action}" }
    }

    /** Start the shared suite server if it is not already up; waits briefly for it to report healthy. */
    fun startSuite(): String {
        if (SuiteProcessManager.isSuiteRunning(healthUrl)) return "The suite is already running."
        val jar = suiteJar ?: return "Cannot start: the ksl-suite-mcp jar is not set."
        SuiteProcessManager.startSuite(jar)
        repeat(30) {
            if (SuiteProcessManager.isSuiteRunning(healthUrl)) return "Suite started and healthy."
            Thread.sleep(300)
        }
        return "Suite launch requested, but it has not reported healthy yet — Refresh to check."
    }

    /** Stop the running suite (leaves clients configured; they will just fail to connect until restarted). */
    fun stopSuite(): String {
        val pids = SuiteProcessManager.findKslProcesses()
            .filter { it.kind == SuiteProcessManager.Kind.SUITE }.map { it.pid }
        if (pids.isEmpty()) return "No running suite found."
        val gone = SuiteProcessManager.terminate(pids)
        return "Stopped the suite (${gone.size} process(es))."
    }

    /** Terminate orphaned (client-less) KSL server JVMs — the leaked-process cleanup. */
    fun cleanupOrphans(): String {
        val orphans = SuiteProcessManager.findOrphans()
        if (orphans.isEmpty()) return "No orphaned KSL server processes."
        val gone = SuiteProcessManager.terminate(orphans.map { it.pid })
        return "Terminated ${gone.size} orphaned KSL process(es)."
    }

    /** The suite keeps running independently, so there is nothing to release on window close. */
    fun dispose() {}

    companion object {
        const val DEFAULT_SUITE_URL: String = "http://127.0.0.1:3001/"

        private fun defaultSuiteJar(): Path? =
            System.getProperty("ksl.suite.jar")?.let { Path.of(it) }?.takeIf { Files.isRegularFile(it) }

        private fun defaultBridgeCommand(): String? =
            System.getProperty("ksl.bridge.command")?.takeIf { it.isNotBlank() }
    }
}
