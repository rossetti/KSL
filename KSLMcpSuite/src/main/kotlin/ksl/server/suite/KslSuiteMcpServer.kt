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

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.serialization.json.Json
import ksl.agent.config.AgentConfigurator
import ksl.agent.config.LaunchSpec
import ksl.service.admin.ServerAdminOperations
import ksl.service.admin.SuiteStatus
import ksl.service.config.BuildInfo
import ksl.service.config.HealthEndpoints
import ksl.service.config.ServerAuth

/**
 * The KSL MCP Suite serving helper: ONE long-running HTTP MCP server that aggregates a set of
 * [McpToolCapability] surfaces (simulation, textbook search, source-code search) on a single MCP
 * endpoint, so a client configures one server and gets every enabled tool. The heavy per-capability
 * state is constructed once in the composition root and captured by each capability; the SDK's
 * `mcp { }` installer builds a fresh aggregated `Server` per SSE session, all delegating to that
 * shared state.
 *
 * This unifies the ktor wiring that previously lived separately in `KslMcpHttpServer` (the
 * simulation server) and this suite: content negotiation, the `ServerAuth` intercept, `/health`
 * `/ready` `/version`, and the `mcp { }` SSE endpoint. Registering the tools is delegated to the
 * capabilities, so the aggregator no longer knows the individual tool surfaces.
 *
 * Transport note (D9): this serves the SDK's SSE transport (proven, multi-session). Streamable HTTP
 * is a deferred refinement — the bridge connects via the client-side `mcpSse` transport.
 */
object KslSuiteMcpServer {

    const val SUITE_NAME: String = "ksl-suite-mcp"

    /**
     * Builds one MCP `Server` carrying the union of the given capabilities' tools. Tool names are
     * disjoint across surfaces, so registering all of them on one server never collides. The server
     * instructions are the suite preamble followed by each enabled capability's own guidance, so a
     * server running only a subset advertises only those surfaces.
     */
    fun buildAggregatedServer(capabilities: List<McpToolCapability>): Server {
        val server = Server(
            serverInfo = Implementation(name = SUITE_NAME, version = BuildInfo.version),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true),
                    prompts = ServerCapabilities.Prompts(listChanged = false),
                ),
            ),
            instructions = instructionsFor(capabilities),
        )
        capabilities.forEach { it.registerTools(server) }
        return server
    }

    /**
     * Creates (but does not start) an embedded CIO server exposing the aggregated MCP surface over
     * SSE, plus `/health` `/ready` `/version`. The `mcp { }` block builds a fresh aggregated server
     * per session over the shared capability state passed in here.
     */
    fun create(
        capabilities: List<McpToolCapability>,
        adminOps: ServerAdminOperations? = null,
        host: String = "127.0.0.1",
        port: Int = 3001,
        ready: () -> Boolean = { true },
        authToken: String? = null,
    ) = embeddedServer(CIO, host = host, port = port) {
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
        routing {
            get("/health") {
                call.respondText(HealthEndpoints.healthJson(SUITE_NAME), ContentType.Application.Json)
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
                call.respondText(HealthEndpoints.versionJson(SUITE_NAME), ContentType.Application.Json)
            }
            if (adminOps != null) {
                // Live server status: per-capability readiness + processing totals. Gated by the
                // auth intercept when a token is set; open on the local-trust default.
                get("/status") {
                    call.respondText(
                        adminJson.encodeToString(SuiteStatus.serializer(), adminOps.status()),
                        ContentType.Application.Json,
                    )
                }
                // The built-in web console (Phase-B7 skeleton; full UX in Phase E).
                get("/admin") {
                    call.respondText(
                        AdminConsole.renderStatusHtml(adminOps.status(), adminOps.usageSummary()),
                        ContentType.Text.Html,
                    )
                }
                get("/admin/events") {
                    // One SSE event per connection; the browser's EventSource reconnects for updates
                    // (Phase E turns this into a persistent push stream).
                    val data = "data: " +
                        adminJson.encodeToString(SuiteStatus.serializer(), adminOps.status()) + "\n\n"
                    call.respondText(data, ContentType.parse("text/event-stream"))
                }
                // Machine-local op: configure the local coding-agent client. Reachable ONLY over the
                // loopback interface (absent in a hosted deployment, where students configure their
                // own client to the URL).
                post("/admin/config/client") {
                    if (!AdminConsole.isLoopbackHost(call.request.local.remoteHost)) {
                        call.respondText("Local-only endpoint.", ContentType.Text.Plain, HttpStatusCode.Forbidden)
                        return@post
                    }
                    val bridge = call.request.queryParameters["bridge"]
                    if (bridge.isNullOrBlank()) {
                        call.respondText("bridge is required", ContentType.Text.Plain, HttpStatusCode.BadRequest)
                        return@post
                    }
                    val url = call.request.queryParameters["url"] ?: "http://127.0.0.1:$port/"
                    val results = AgentConfigurator.configure(SetupCli.SUITE_KEY, LaunchSpec(bridge, listOf("--url", url)))
                    val body = if (results.isEmpty()) "No coding agents detected."
                    else results.joinToString("\n") { "${it.agent}: ${it.action} -> ${it.path}" }
                    call.respondText(body, ContentType.Text.Plain)
                }
            }
        }
        mcp { buildAggregatedServer(capabilities) }
    }

    private val adminJson = Json { encodeDefaults = true }

    /** Suite preamble + each enabled capability's own routing guidance. */
    private fun instructionsFor(capabilities: List<McpToolCapability>): String = buildString {
        append(SUITE_PREAMBLE)
        capabilities.forEach { c ->
            c.instructions?.let {
                append("\n\n")
                append(it)
            }
        }
    }

    private const val SUITE_PREAMBLE =
        "This is the KSL MCP Suite for a simulation course using the Kotlin Simulation Library (KSL). " +
            "It exposes the capabilities below on one server; route by the user's intent."
}
