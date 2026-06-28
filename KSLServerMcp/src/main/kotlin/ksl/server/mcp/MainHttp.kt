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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import ksl.service.capability.run.BundleDirectoryWatcher
import ksl.service.capability.run.BundleRegistry
import ksl.service.config.ServerConfig
import ksl.service.store.ArtifactStore
import ksl.service.store.ResultStore

/**
 * HTTP entrypoint for the KSL MCP server (the SSE / Streamable HTTP transport).
 * An agent connects to `http://<host>:<port>` rather than launching a
 * subprocess. Settings come from [ServerConfig] (`~/.ksl/config.toml`); the
 * listen port is `server.mcpPort` (env `KSL_MCP_PORT` overrides).
 *
 * Bundles are discovered from the server's watched bundle directories
 * (`KSLWork/KSL_MCP_APPS/bundles/` then the shared `KSLWork/bundles/`), which a
 * [BundleDirectoryWatcher] keeps in sync.
 */
fun main() {
    val config = ServerConfig.load()
    val registry = BundleRegistry.empty()
    val ready = java.util.concurrent.atomic.AtomicBoolean(false) // flips true after the initial scan (/ready)
    val watcher = BundleDirectoryWatcher(registry, config.bundleDirs(), config.pollInterval())
    watcher.scanOnce()
    ready.set(true)
    val watcherScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    watcher.start(watcherScope)

    val resultStore = ResultStore(config.resultCacheDir(), config.cache.maxMemoryBytes, config.cache.maxDiskEntries)
    val artifactStore = ArtifactStore(config.resultCacheDir())
    val tools = KslMcpTools(registry, resultStore, artifactStore, maxConcurrentJobs = config.server.maxConcurrentJobs, runDeadline = config.runDeadline())
    val port = config.mcpPort()
    val host = config.bindHost() // localhost by default (local-trust model); see ServerConfig

    val server = KslMcpHttpServer.create(tools, host = host, port = port, ready = ready::get, authToken = config.authToken())
    Runtime.getRuntime().addShutdownHook(
        Thread {
            watcherScope.cancel()
            tools.close()
            registry.close()
        },
    )
    server.start(wait = true)
}
