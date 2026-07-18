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

package ksl.bridge

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.mcpSseTransport
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

private const val DEFAULT_URL = "http://127.0.0.1:3001/"

/**
 * KSL MCP bridge: a thin stdio->HTTP proxy so a stdio-only client (Claude Desktop) can reach the
 * long-running KSLMcpSuite HTTP server. Claude launches this as its stdio MCP server; internally it
 * connects to the suite over SSE and pumps JSON-RPC messages both ways, unchanged. It holds none of
 * the heavy KSL state (no KSLCore, no indexes) — that lives in the one shared suite server.
 *
 * Usage: `ksl-bridge [--stdio] [--url <suite-sse-url>]`  (default http://127.0.0.1:3001/, or env
 * `KSL_SUITE_URL`). `--stdio` is accepted and ignored — the bridge is always a stdio<->HTTP pump.
 *
 * IMPORTANT: stdout is the MCP channel to the client — never println to it; all logging goes to
 * stderr (logback-ksl-bridge.xml).
 */
fun main(args: Array<String>) {
    if (System.getProperty("logback.configurationFile") == null) {
        System.setProperty("logback.configurationFile", "logback-ksl-bridge.xml")
    }
    val url = resolveUrl(args)
    logger.info { "ksl-bridge starting: stdio <-> $url" }

    val httpClient = HttpClient(CIO) { install(SSE) }
    val upstream = httpClient.mcpSseTransport(url) // toward the suite (SSE)
    val downstream = StdioServerTransport( // toward the client (Claude), over stdin/stdout
        System.`in`.asSource().buffered(),
        System.out.asSink().buffered(),
    )

    val done = Job()
    // The pump: forward every JSON-RPC message across unchanged. onMessage is a suspend callback and
    // send is suspend, so this is a direct hand-off with no extra buffering or interpretation.
    downstream.onMessage { message -> upstream.send(message) } // client -> suite
    upstream.onMessage { message -> downstream.send(message) } // suite -> client
    // Either side closing ends the bridge: the client disconnecting (stdin EOF) or the suite going
    // away. Registered before start() so no close is missed.
    downstream.onClose { done.complete() }
    upstream.onClose { done.complete() }
    downstream.onError { e -> logger.error(e) { "client (stdio) transport error" } }
    upstream.onError { e -> logger.error(e) { "suite (SSE) transport error" } }

    runBlocking {
        try {
            upstream.start() // connect to the suite first, so a client request never races ahead of it
        } catch (e: Throwable) {
            logger.error(e) { "cannot reach the KSL suite at $url" }
            System.err.println("ksl-bridge: cannot reach the KSL suite at $url — is it running? Start it from the KSL Server Manager.")
            runCatching { httpClient.close() }
            exitProcess(1)
        }
        try {
            downstream.start() // start reading the client's stdin
            done.join()
        } finally {
            runCatching { upstream.close() }
            runCatching { downstream.close() }
            runCatching { httpClient.close() }
        }
    }
    // WS1 lesson: force exit so a disconnected bridge cannot linger as an orphaned JVM.
    exitProcess(0)
}

private fun resolveUrl(args: Array<String>): String {
    val i = args.indexOf("--url")
    if (i >= 0 && i + 1 < args.size && args[i + 1].isNotBlank()) return args[i + 1]
    System.getenv("KSL_SUITE_URL")?.takeIf { it.isNotBlank() }?.let { return it }
    return DEFAULT_URL
}
