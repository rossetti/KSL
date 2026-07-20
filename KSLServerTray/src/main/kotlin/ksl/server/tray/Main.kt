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

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import ksl.server.manage.ServerManagerController
import ksl.server.manage.ServerProcessInventory
import ksl.server.manage.SuiteClientConfig
import java.awt.GraphicsEnvironment
import java.awt.SystemTray

private val logger = KotlinLogging.logger {}

private const val DEFAULT_PORT = 3001

/**
 * The "KSL Server" menu-bar / system-tray agent: a small resident supervisor that starts the long-running
 * suite as a managed child and opens the web console for detailed work (the Postgres.app / Docker-Desktop
 * model). With a tray it installs an icon whose color is the suite's status; headless (a server box, or a
 * JVM with no display) it degrades to a foreground supervisor — start the suite, print the console URL,
 * and stay up until the suite exits.
 *
 * `--version` prints the distribution version; `--remove` strips the `ksl-suite` client entry (what
 * `ksl uninstall` calls). Both exit without starting the server.
 */
fun main(args: Array<String>) {
    if ("--version" in args) {
        // Reads the packaged jar manifest's Implementation-Version (stamped by the jar task); "dev" from classes.
        println(ServerTray::class.java.`package`?.implementationVersion ?: "dev")
        return
    }
    // Client-setup commands. `ksl uninstall` / `ksl unregister` run this launcher with `--remove` to
    // strip the one `ksl-suite` MCP entry from detected agents (Claude Desktop, Codex, Cursor, …). Handle setup
    // flags HERE so they never fall through to *starting* the server. remove() edits config only (and
    // honors the KSL_AGENT_CONFIG_HOME sandbox); a live client keeps running until it restarts.
    // Configuring is the console's Connect (or `ksl-suite --configure`), which auto-detects the bridge,
    // so `--configure` here just points there rather than duplicating that detection.
    if ("--remove" in args) {
        val results = SuiteClientConfig.remove()
        if (results.isEmpty()) {
            println("No coding agents detected (no Claude Desktop, Codex, Cursor, or Windsurf config directory).")
        } else {
            results.forEach { println("${it.agent}: ${it.action}  ->  ${it.path}") }
        }
        return
    }
    if ("--configure" in args) {
        println("To connect a client, open the console and click Connect, or run: ksl-suite --configure")
        return
    }
    val port = System.getenv("KSL_MCP_PORT")?.toIntOrNull() ?: DEFAULT_PORT
    val healthUrl = "http://127.0.0.1:$port/health"
    val consoleUrl = "http://127.0.0.1:$port/admin"

    val scope = CoroutineScope(SupervisorJob())
    val controller = ServerManagerController(healthUrl = healthUrl, scope = scope)
    val child = SuiteChild(controller, healthUrl, port)

    val hasTray = !GraphicsEnvironment.isHeadless() && SystemTray.isSupported()
    if (!hasTray) {
        runHeadless(child, healthUrl, consoleUrl)
        scope.cancel()
        return
    }

    // A tray keeps the JVM alive on the AWT event thread, so main() may return after installing.
    ServerTray(
        child = child,
        loginItem = LoginItem(InstallPaths.serverLauncher()),
        healthUrl = healthUrl,
        consoleUrl = consoleUrl,
        scope = scope,
    ).install()
}

/** No display: start the suite, wait for it, and foreground-supervise until it exits (reaping on stop). */
private fun runHeadless(child: SuiteChild, healthUrl: String, consoleUrl: String) {
    logger.info { "No system tray available — running the KSL server as a headless supervisor." }
    child.startIfDown()
    if (!child.awaitUp()) {
        logger.error { "The KSL suite did not come up; check ~/.ksl/logs." }
        return
    }
    logger.info { "KSL suite is up. Console: $consoleUrl" }
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { child.stopIfOurs() } })
    while (ServerProcessInventory.isSuiteRunning(healthUrl)) Thread.sleep(1000)
    logger.info { "The KSL suite is no longer running; exiting." }
}
