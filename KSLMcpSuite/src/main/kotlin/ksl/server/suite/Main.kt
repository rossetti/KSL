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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import ksl.book.search.BookSearch
import ksl.book.search.BookStore
import ksl.code.search.CodeSearch
import ksl.code.search.CodeStore
import ksl.server.mcp.KslMcpTools
import ksl.service.capability.run.BundleDirectoryWatcher
import ksl.service.capability.run.BundleRegistry
import ksl.service.config.BuildInfo
import ksl.service.config.ServerConfig
import ksl.service.store.ArtifactStore
import ksl.service.store.ResultStore
import ksl.service.usage.UsageStore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HTTP entrypoint for the KSL MCP Suite — one long-running server exposing the enabled tool surfaces
 * (simulation, textbook, source-code) on a single MCP endpoint. `main(args)` either performs a
 * client-setup command (`--configure` / `--remove`, see `SetupCli`) or starts the server.
 *
 * The server is the composition root: it builds the heavy per-capability state ONCE, wraps each
 * enabled surface in an [McpToolCapability], and hands the list to the serving helper. Which surfaces
 * are enabled comes from `ServerConfig` (`[capabilities]`, or `KSL_CAPABILITY_{SIM,BOOK,CODE}`);
 * disabling `sim` skips the bundle registry and run services entirely, so a textbook-only deployment
 * starts light. The book/code stores load their JSON at first touch and their Lucene indexes build
 * lazily on the first search.
 */
fun main(args: Array<String>) {
    if (SetupCli.isSetupCommand(args)) {
        SetupCli.runAndReport(args)
        return
    }
    runServer()
}

private fun runServer() {
    val config = ServerConfig.load()
    val usage = UsageStore(config.appFolder().resolve("usage"))
    val capabilities = mutableListOf<McpToolCapability>()
    val cleanups = mutableListOf<() -> Unit>()
    // Only the simulation surface needs a readiness gate (its initial bundle scan); without it the
    // server is ready as soon as it is listening.
    val ready = AtomicBoolean(true)

    if (config.simEnabled()) {
        val registry = BundleRegistry.empty()
        ready.set(false) // not ready until the initial bundle scan completes
        val watcher = BundleDirectoryWatcher(registry, config.bundleDirs(), config.pollInterval())
        watcher.scanOnce()
        ready.set(true)
        val watcherScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        watcher.start(watcherScope)

        val resultStore = ResultStore(
            config.resultCacheDir(),
            config.cache.maxMemoryBytes,
            config.cache.maxDiskEntries,
            config.cache.maxDiskBytes,
        )
        val artifactStore = ArtifactStore(config.outputRoot())
        val kslTools = KslMcpTools(
            registry,
            resultStore,
            artifactStore,
            maxConcurrentJobs = config.server.maxConcurrentJobs,
            runDeadline = config.runDeadline(),
        )
        capabilities += SimMcpCapability(kslTools, registry, usage.recorderFor("sim"))
        cleanups += {
            watcherScope.cancel()
            kslTools.close()
            registry.close()
        }
    }

    if (config.bookEnabled()) {
        val bookStore = BookStore.instance
        capabilities += BookMcpCapability(bookStore, BookSearch(bookStore), usage.recorderFor("book"))
    }

    if (config.codeEnabled()) {
        val codeStore = CodeStore.instance
        capabilities += CodeMcpCapability(codeStore, CodeSearch(codeStore), usage.recorderFor("code"))
    }

    require(capabilities.isNotEmpty()) {
        "No capabilities enabled — enable at least one of sim/book/code in [capabilities] or via KSL_CAPABILITY_*."
    }

    val adminOps = InProcessAdminOperations(BuildInfo.version, capabilities, usage)
    val server = KslSuiteMcpServer.create(
        capabilities = capabilities,
        adminOps = adminOps,
        host = config.bindHost(),
        port = config.mcpPort(),
        ready = ready::get,
        authToken = config.authToken(),
    )
    Runtime.getRuntime().addShutdownHook(Thread { cleanups.forEach { runCatching { it() } } })
    server.start(wait = true)
}
