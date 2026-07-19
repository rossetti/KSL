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

package ksl.server.manage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ksl.agent.config.AgentConfigurator
import ksl.server.manage.ServerProcessInventory.Health
import ksl.server.manage.ServerProcessInventory.Kind
import ksl.server.manage.ServerProcessInventory.KslProcess
import ksl.service.admin.ServerAdminOperations
import ksl.service.admin.SuiteStatus
import ksl.service.usage.UsageSummary
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext

/**
 * The GUI-agnostic controller a front-end drives to OPERATE the suite (the web console, a CLI, a
 * future desktop app). It owns no widgets and INJECTS its `CoroutineScope`, so any front-end supplies
 * its own dispatcher (`Dispatchers.Swing`, a CLI scope, a `TestScope`) — the single rule that makes it
 * a genuine peer of the built-in console rather than Swing-bound.
 *
 * It exposes observable state as `StateFlow`s and commands that return outcome records; `refresh`
 * polls the suite's `/admin` surface (via the injected [ServerAdminOperations]) plus the local process
 * inventory and client-config, off the caller's thread. Commands do not auto-refresh — the front-end
 * calls [refresh] after a mutation so the timing is its own.
 */
class ServerManagerController(
    private val adminOps: ServerAdminOperations = HttpAdminOperations(),
    private val healthUrl: String = ServerProcessInventory.DEFAULT_HEALTH_URL,
    scope: CoroutineScope? = null,
    private val io: CoroutineContext = Dispatchers.IO,
) : AutoCloseable {

    private val ownsScope: Boolean = scope == null
    private val scope: CoroutineScope = scope ?: CoroutineScope(SupervisorJob())

    private val myHealth = MutableStateFlow(Health.DOWN)
    val health: StateFlow<Health> = myHealth.asStateFlow()

    private val myStatus = MutableStateFlow<SuiteStatus?>(null)
    val status: StateFlow<SuiteStatus?> = myStatus.asStateFlow()

    private val myUsage = MutableStateFlow<UsageSummary?>(null)
    val usage: StateFlow<UsageSummary?> = myUsage.asStateFlow()

    private val myProcesses = MutableStateFlow<List<KslProcess>>(emptyList())
    val processes: StateFlow<List<KslProcess>> = myProcesses.asStateFlow()

    private val myClients = MutableStateFlow<List<AgentConfigurator.ClientState>>(emptyList())
    val clients: StateFlow<List<AgentConfigurator.ClientState>> = myClients.asStateFlow()

    /**
     * Poll every observable once (health → status/usage when up → processes → clients), off the
     * caller's thread. A `/admin` read that fails leaves the suite state as DOWN/null rather than
     * throwing, so a transient outage is a state change, not an error.
     */
    suspend fun refresh() = withContext(io) {
        val h = ServerProcessInventory.health(healthUrl)
        myHealth.value = h
        if (h == Health.UP) {
            runCatching { adminOps.status() }.onSuccess { myStatus.value = it }.onFailure { myStatus.value = null }
            runCatching { adminOps.usageSummary() }.onSuccess { myUsage.value = it }
        } else {
            myStatus.value = null
        }
        myProcesses.value = ServerProcessInventory.findKslProcesses()
        myClients.value = SuiteClientConfig.state()
    }

    /** Fire a background refresh on the injected scope (for a poll tick or a front-end refresh button). */
    fun refreshAsync() {
        scope.launch { refresh() }
    }

    // ---- commands (return outcome records; the front-end reports them, then calls refresh) ----

    fun configureClient(bridgeCommand: String, suiteUrl: String): List<AgentConfigurator.ConfigResult> =
        SuiteClientConfig.configure(bridgeCommand, suiteUrl)

    fun removeClient(): List<AgentConfigurator.ConfigResult> = SuiteClientConfig.remove()

    /** Terminate the orphaned (client-less) KSL JVMs; returns the pids reaped. */
    fun cleanupOrphans(): List<Long> {
        val orphans = ServerProcessInventory.findOrphans().map { it.pid }
        return if (orphans.isEmpty()) emptyList() else ServerProcessInventory.terminate(orphans)
    }

    /** Start the suite jar detached (lifecycle is the launcher/host's, not the console's). */
    fun startSuite(suiteJar: Path, port: Int? = null): Process = ServerProcessInventory.startSuite(suiteJar, port)

    /** Start the suite via its installed launcher script (the thin distribution suite; see inventory). */
    fun startSuiteLauncher(launcher: Path, port: Int? = null): Process =
        ServerProcessInventory.startSuiteLauncher(launcher, port)

    /** Stop the running suite process(es); returns the pids reaped. */
    fun stopSuite(): List<Long> {
        val suites = ServerProcessInventory.findKslProcesses().filter { it.kind == Kind.SUITE }.map { it.pid }
        return if (suites.isEmpty()) emptyList() else ServerProcessInventory.terminate(suites)
    }

    /** Cancels only a scope this controller created; an injected scope is the front-end's to cancel. */
    override fun close() {
        if (ownsScope) scope.cancel()
    }
}
