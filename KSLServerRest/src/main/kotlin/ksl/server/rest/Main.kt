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

package ksl.server.rest

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import ksl.service.capability.run.BundleDirectoryWatcher
import ksl.service.capability.run.BundleRegistry
import ksl.service.config.ServerConfig
import ksl.service.store.ArtifactStore
import ksl.service.store.ResultStore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Entrypoint for the KSL REST/SSE service. Settings come from [ServerConfig]
 * (`~/.ksl/config.toml`); it listens on `server.restPort` (env `KSL_REST_PORT`
 * overrides) and serves the run + fit surfaces over the headless service core.
 * Bundles are discovered from the server's watched bundle directories
 * (`KSLWork/KSLServer/bundles/` then the shared `KSLWork/bundles/`), which a
 * [BundleDirectoryWatcher] keeps in sync.
 */
fun main() {
    val config = ServerConfig.load()
    val registry = BundleRegistry.empty()
    val ready = AtomicBoolean(false) // flips true once the initial bundle scan is done (/ready)
    val watcher = BundleDirectoryWatcher(registry, config.bundleDirs(), config.pollInterval())
    watcher.scanOnce()
    ready.set(true)
    val watcherScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    watcher.start(watcherScope)

    val resultStore = ResultStore(config.resultCacheDir(), config.cache.maxMemoryBytes, config.cache.maxDiskEntries, config.cache.maxDiskBytes)
    // Run work (capture output, the KSL database, rendered reports/exports) lands in
    // the KSLWork workspace like the desktop apps — NOT under ~/.ksl (settings/cache only).
    val artifactStore = ArtifactStore(config.outputRoot())
    val service = KslRestService(
        registry, config.server.maxConcurrentJobs, resultStore, artifactStore,
        runDeadline = config.runDeadline(),
    )
    val port = config.restPort()
    val host = config.bindHost() // localhost by default (local-trust model); see ServerConfig

    val server = embeddedServer(CIO, host = host, port = port) {
        kslRestModule(service, ready = ready::get, authToken = config.authToken())
    }
    Runtime.getRuntime().addShutdownHook(
        Thread {
            watcherScope.cancel()
            service.close()
            registry.close()
        },
    )
    server.start(wait = true)
}
