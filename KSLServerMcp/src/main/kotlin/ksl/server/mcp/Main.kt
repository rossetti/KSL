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

import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlin.system.exitProcess
import ksl.service.capability.run.BundleDirectoryWatcher
import ksl.service.capability.run.BundleRegistry
import ksl.service.config.ServerConfig
import ksl.service.store.ArtifactStore
import ksl.service.store.ResultStore

/**
 * Stdio entrypoint for the KSL MCP server: an agent launches this as a
 * subprocess and speaks MCP over stdin/stdout.
 *
 * Settings come from [ServerConfig] (`~/.ksl/config.toml`, env-overridable).
 * Bundles are discovered from the server's watched bundle directories
 * (`KSLWork/KSLServer/bundles/` first, then the shared `KSLWork/bundles/`),
 * which a [BundleDirectoryWatcher] keeps in sync so a JAR dropped there becomes
 * available with no restart. The server runs until the transport closes.
 *
 * IMPORTANT: stdout is the MCP channel — never println to it. All diagnostics
 * must go to stderr or a log file.
 */
fun main() = runStdioServer()

/** Runs the MCP server over stdin/stdout until the transport closes. Shared by the
 *  application entrypoint and the single-jar [Launcher] (`--stdio`). */
fun runStdioServer() {
    // Rendering a fit report's diagnostic plots initializes AWT (lets-plot). On
    // macOS that promotes this background subprocess into a foreground GUI app — a
    // Java Dock icon pops up and lingers. Marking the process an "accessory" (the
    // LSUIElement equivalent) before any AWT class loads keeps it off the Dock and
    // out of the menu bar while still allowing off-screen plot rendering. Apple-only
    // property; a no-op on other JVMs. Must be set before the toolkit initializes.
    if (System.getProperty("apple.awt.UIElement") == null) {
        System.setProperty("apple.awt.UIElement", "true")
    }
    serveStdio()
    // The client disconnected (the transport closed) and cleanup ran. Force JVM exit rather than
    // returning: a heavy run may have started non-daemon threads — the AWT EDT from a rendered
    // fit-report plot, or a HikariCP pool — that would otherwise keep this now-clientless
    // background process alive indefinitely (the orphaned-JVM leak this fixes).
    exitProcess(0)
}

private fun serveStdio() = runBlocking {
    val config = ServerConfig.load()
    val registry = BundleRegistry.empty()
    val watcher = BundleDirectoryWatcher(registry, config.bundleDirs(), config.pollInterval())
    watcher.scanOnce() // discover bundles from the watched directories before serving
    val watcherScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    watcher.start(watcherScope)

    // The result cache, run work (output, the KSL database, rendered reports/exports), and documents all land in
    // the KSLWork workspace like the desktop apps — NOT under ~/.ksl, which stays settings-only.
    val resultStore = ResultStore(config.resultCacheDir(), config.cache.maxMemoryBytes, config.cache.maxDiskEntries, config.cache.maxDiskBytes)
    val artifactStore = ArtifactStore(config.outputRoot())
    // Saved config/layout documents live beside the run artifacts in the workspace (KSLServer/documents/),
    // NOT under ~/.ksl — they are the user's authored files, not cache.
    val documentStore = ksl.service.store.DocumentStore(config.outputRoot().resolveSibling("documents"))
    val tools = KslMcpTools(registry, resultStore, artifactStore, documentStore, maxConcurrentJobs = config.server.maxConcurrentJobs, runDeadline = config.runDeadline())
    val server = KslMcpServer.build(tools)

    val transport = StdioServerTransport(
        System.`in`.asSource().buffered(),
        System.out.asSink().buffered(),
    )

    // Complete `done` from the TRANSPORT's onClose, not the Server's. The SDK's multi-session
    // Server fires session teardown on stdin EOF (client disconnect) but never the server-level
    // onClose — so hooking server.onClose left this coroutine parked in runBlocking forever, the
    // root cause of the orphaned server JVMs. Registering on the transport BEFORE createSession
    // chains ahead of the session's own teardown, with no race.
    val done = Job()
    transport.onClose { done.complete() }
    server.createSession(transport)
    done.join()
    watcherScope.cancel()
    tools.close()
    registry.close()
}
