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
import ksl.book.mcp.BookSearch
import ksl.book.mcp.BookStore
import ksl.code.mcp.CodeSearch
import ksl.code.mcp.CodeStore
import ksl.server.mcp.KslMcpTools
import ksl.service.capability.run.BundleDirectoryWatcher
import ksl.service.capability.run.BundleRegistry
import ksl.service.config.ServerConfig
import ksl.service.store.ArtifactStore
import ksl.service.store.ResultStore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HTTP entrypoint for the KSL MCP Suite — one long-running server exposing the simulation, code, and
 * textbook tool surfaces on a single MCP endpoint. Settings come from `ServerConfig`
 * (`~/.ksl/config.toml`); the listen port is `server.mcpPort` (env `KSL_MCP_PORT`).
 *
 * The heavy shared state is built once: the bundle registry + run services (as in the standalone
 * simulation server), and the book/code stores. The two Lucene indexes build lazily on the first
 * search of each, so startup stays light.
 */
fun main() {
    val config = ServerConfig.load()
    val registry = BundleRegistry.empty()
    val ready = AtomicBoolean(false) // flips true after the initial bundle scan (/ready)
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

    // The book/code stores load their (small) JSON at first touch; the Lucene indexes build lazily
    // on the first search of each, so we do NOT warm them up here — startup stays light.
    val bookStore = BookStore.instance
    val bookSearch = BookSearch(bookStore)
    val codeStore = CodeStore.instance
    val codeSearch = CodeSearch(codeStore)

    val server = KslSuiteMcpServer.create(
        kslTools = kslTools,
        bookStore = bookStore,
        bookSearch = bookSearch,
        codeStore = codeStore,
        codeSearch = codeSearch,
        host = config.bindHost(),
        port = config.mcpPort(),
        ready = ready::get,
        authToken = config.authToken(),
    )
    Runtime.getRuntime().addShutdownHook(
        Thread {
            watcherScope.cancel()
            kslTools.close()
            registry.close()
        },
    )
    server.start(wait = true)
}
