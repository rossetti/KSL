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
import ksl.server.manage.ServerManagerController
import ksl.server.manage.ServerProcessInventory
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Manages the suite as a tray-owned child: resolve the installed `ksl-suite` launcher, start the suite
 * only when it is not already up, and on Quit stop only a suite that WE started (clean, no orphan; never
 * kills a suite someone else launched, e.g. a dev instance).
 *
 * Ours is known by the [Process] the launch returned, not by searching command lines: on Windows the JDK
 * reports no command line for any other process, so a search finds nothing and Quit stopped nothing
 * (0.4.2). The handle also reaches the server through the `cmd.exe` the Windows launcher runs in.
 */
class SuiteChild(
    private val controller: ServerManagerController,
    private val healthUrl: String = ServerProcessInventory.DEFAULT_HEALTH_URL,
    private val port: Int? = null,
    private val launcher: () -> Path? = InstallPaths::suiteLauncher,
) {

    @Volatile
    private var child: Process? = null

    /** The launcher process this tray started and has not yet stopped, if any. */
    internal val started: ProcessHandle? get() = child?.toHandle()

    /** The `ksl-suite` launcher this agent would start, or null in a dev/classes run. */
    fun resolveSuiteLauncher(): Path? = launcher()

    /**
     * Start the suite child if it is not already answering /health and a launcher is resolvable. Returns
     * true when we launched one (so the tray can show STARTING), false if it was already up or we are in a
     * dev run with no installed launcher.
     */
    fun startIfDown(): Boolean {
        if (ServerProcessInventory.isSuiteRunning(healthUrl)) {
            logger.info { "The KSL suite is already running; this tray will leave it running on Quit." }
            return false
        }
        val path = resolveSuiteLauncher() ?: return false
        val process = controller.startSuiteLauncher(path, port)
        child = process
        logger.info { "Started the KSL suite (pid ${process.pid()}) with $path" }
        return true
    }

    /** Block up to [timeoutMs] for the suite to answer /health; returns its final up/down. */
    fun awaitUp(timeoutMs: Long = 25_000): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (ServerProcessInventory.isSuiteRunning(healthUrl)) return true
            Thread.sleep(300)
        }
        return ServerProcessInventory.isSuiteRunning(healthUrl)
    }

    /**
     * On Quit: stop the suite only if we started it; returns the pids reaped (empty otherwise).
     *
     * The tree is captured before anything is stopped: once the Windows `cmd.exe` is gone, the server
     * under it can no longer be reached from here. Each process gets a normal stop first (SIGTERM on
     * macOS/Linux, so the suite's shutdown hook runs) and a forced one if it outlives the grace period.
     */
    fun stopIfOurs(): List<Long> {
        val root = child?.toHandle() ?: run {
            logger.info { "Quit: this tray did not start the KSL suite; leaving it running." }
            return emptyList()
        }
        val tree = deepestFirst(root)
        val reaped = ServerProcessInventory.terminate(tree.map { it.pid() })
        child = null
        logger.info { "Quit: stopped the KSL suite this tray started; pids ${tree.map { it.pid() }}, reaped $reaped" }
        if (ServerProcessInventory.isSuiteRunning(healthUrl)) {
            logger.warn { "Quit: the KSL suite still answers $healthUrl after its process tree was stopped" }
        }
        return reaped
    }
}

/**
 * [root]'s descendants, deepest first, then [root]. The JDK does not order `descendants()`, and stopping a
 * parent first can leave its children unreachable, so sort by the number of parent steps up to [root].
 */
internal fun deepestFirst(root: ProcessHandle): List<ProcessHandle> {
    fun depth(handle: ProcessHandle): Int {
        var steps = 0
        var parent = handle.parent().orElse(null)
        while (parent != null && parent.pid() != root.pid() && steps < 64) {
            steps++
            parent = parent.parent().orElse(null)
        }
        return steps
    }
    return root.descendants().toList().sortedByDescending(::depth) + root
}
