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

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import ksl.service.config.HealthEndpoints
import ksl.service.config.ServerAuth

/**
 * The Streamable HTTP (SSE) transport for the KSL MCP server (strategic plan
 * §4.2): the same [KslMcpServer] tool surface, served over HTTP instead of
 * stdio, so an agent connects to a long-running server rather than launching a
 * subprocess.
 *
 * The MCP SDK's `mcp { }` installer wires the SSE plugin and the
 * GET (event stream) + POST (messages) routes; the block builds a fresh
 * `Server` per session, all delegating to the one shared [KslMcpTools] (and thus
 * the shared registry, JobManager, and run/fit services).
 */
object KslMcpHttpServer {

    /**
     * Creates (but does not start) an embedded CIO server exposing the MCP tool
     * surface. Call `.start(wait = true)` to run it, or `.start(wait = false)`
     * and `.stop()` to manage it (as the tests do).
     *
     * @param host the bind interface; defaults to `127.0.0.1` (localhost only,
     *        the local-trust model). The launcher passes `ServerConfig.bindHost()`.
     * @param port the listen port; 0 binds an ephemeral port (resolve it via
     *        `engine.resolvedConnectors()`).
     * @param ready a readiness probe for `GET /ready` (Phase 9 A4); the launcher
     *        flips it after the initial bundle scan. Defaults to always-ready.
     */
    fun create(
        tools: KslMcpTools,
        host: String = "127.0.0.1",
        port: Int = 3001,
        ready: () -> Boolean = { true },
        authToken: String? = null,
    ) = embeddedServer(CIO, host = host, port = port) {
        // Bearer-token gate (only when configured). Runs before routing/mcp so it
        // covers the SSE + message routes too; the probe paths stay public. An
        // MCP-HTTP client must then send `Authorization: Bearer <token>`.
        if (!authToken.isNullOrBlank()) {
            intercept(ApplicationCallPipeline.Plugins) {
                val path = call.request.path()
                if (!ServerAuth.isPublicPath(path) &&
                    !ServerAuth.isAuthorized(authToken, call.request.headers["Authorization"])
                ) {
                    call.respondText(
                        ServerAuth.unauthorizedJson(),
                        ContentType.Application.Json,
                        HttpStatusCode.Unauthorized,
                    )
                    finish()
                }
            }
        }
        // Plain health/readiness routes alongside the MCP SSE/messages routes.
        routing {
            get("/health") {
                call.respondText(HealthEndpoints.healthJson("ksl-mcp"), ContentType.Application.Json)
            }
            get("/ready") {
                val isReady = ready()
                call.respondText(
                    HealthEndpoints.readyJson(isReady),
                    ContentType.Application.Json,
                    if (isReady) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                )
            }
            get("/version") {
                call.respondText(HealthEndpoints.versionJson("ksl-mcp"), ContentType.Application.Json)
            }
        }
        mcp { KslMcpServer.build(tools) }
    }
}
