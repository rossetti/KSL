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

import ksl.server.manage.ServerManagerController
import ksl.server.manage.ServerProcessInventory
import java.nio.file.Path

/**
 * Manages the suite as a tray-owned child: resolve the installed `ksl-suite` launcher, start the suite
 * only when it is not already up, and on Quit stop only a suite that WE started (clean, no orphan; never
 * kills a suite someone else launched, e.g. a dev instance). All process work goes through the Phase-D
 * [ServerManagerController] seam.
 */
class SuiteChild(
    private val controller: ServerManagerController,
    private val healthUrl: String = ServerProcessInventory.DEFAULT_HEALTH_URL,
    private val port: Int? = null,
) {

    @Volatile
    private var startedByUs = false

    /** The `ksl-suite` launcher this agent would start, or null in a dev/classes run. */
    fun resolveSuiteLauncher(): Path? = InstallPaths.suiteLauncher()

    /**
     * Start the suite child if it is not already answering /health and a launcher is resolvable. Returns
     * true when we launched one (so the tray can show STARTING), false if it was already up or we are in a
     * dev run with no installed launcher.
     */
    fun startIfDown(): Boolean {
        if (ServerProcessInventory.isSuiteRunning(healthUrl)) return false
        val launcher = resolveSuiteLauncher() ?: return false
        controller.startSuiteLauncher(launcher, port)
        startedByUs = true
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

    /** On Quit: stop the suite only if we started it; returns the pids reaped (empty otherwise). */
    fun stopIfOurs(): List<Long> = if (startedByUs) controller.stopSuite() else emptyList()
}
