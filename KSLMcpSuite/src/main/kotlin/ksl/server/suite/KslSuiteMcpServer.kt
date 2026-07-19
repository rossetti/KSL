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
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import ksl.agent.config.AgentConfigurator
import ksl.service.admin.ServerAdminOperations
import ksl.service.admin.SuiteStatus
import ksl.service.usage.UsageEvent
import ksl.service.usage.UsageSummary
import ksl.service.config.CapabilitiesConfig
import ksl.service.config.HealthEndpoints
import ksl.service.config.ServerAuth
import ksl.service.config.ServerConfig
import ksl.service.config.ServerConfigToml

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
            serverInfo = Implementation(name = SUITE_NAME, version = SuiteBuildInfo.version),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true),
                    prompts = ServerCapabilities.Prompts(listChanged = false),
                ),
            ),
            instructions = instructionsFor(capabilities),
        )
        // One session context per aggregated server (= per SSE session), so every recorded call of this
        // connection shares a sessionId. Client attribution is a follow-up (SDK clientInfo at initialize).
        val session = ksl.service.usage.ToolCallSession(sessionId = java.util.UUID.randomUUID().toString().take(8))
        capabilities.forEach { it.registerTools(server, session) }
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
        usage: UsageControl? = null,
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
                call.respondText(HealthEndpoints.healthJson(SUITE_NAME, SuiteBuildInfo.version), ContentType.Application.Json)
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
                call.respondText(HealthEndpoints.versionJson(SUITE_NAME, SuiteBuildInfo.version), ContentType.Application.Json)
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
                // The built-in web console (Phase E): the six operator regions, server-rendered.
                get("/admin") {
                    val loopback = AdminConsole.isLoopbackHost(call.request.local.remoteHost)
                    call.respondText(
                        AdminConsole.renderConsole(
                            status = adminOps.status(),
                            usage = adminOps.usageSummary(),
                            activity = adminOps.recentActivity(10),
                            clients = AgentConfigurator.state(SetupCli.SUITE_KEY),
                            loopback = loopback,
                            usageLevel = usage?.level?.invoke() ?: ksl.service.usage.UsageLevel.FULL,
                            usageDir = usage?.dir,
                        ),
                        ContentType.Text.Html,
                    )
                }
                get("/admin/events") {
                    // Persistent SSE: push a status snapshot every 2s until the client disconnects (a
                    // write failure breaks the loop). `retry:` sets the browser's reconnect delay.
                    call.respondTextWriter(ContentType.parse("text/event-stream")) {
                        write("retry: 2000\n\n")
                        try {
                            while (true) {
                                write("data: " + adminJson.encodeToString(SuiteStatus.serializer(), adminOps.status()) + "\n\n")
                                flush()
                                delay(2000)
                            }
                        } catch (_: Exception) {
                            // client disconnected — end the stream
                        }
                    }
                }
                // Usage aggregate + recent activity for an external UI / CLI (the built-in console
                // reads them in-process). Same DTOs as ServerAdminOperations, so the KSLServerManager
                // HttpAdminOperations parses them directly.
                get("/admin/usage") {
                    call.respondText(
                        adminJson.encodeToString(UsageSummary.serializer(), adminOps.usageSummary()),
                        ContentType.Application.Json,
                    )
                }
                get("/admin/activity") {
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                    call.respondText(
                        adminJson.encodeToString(ListSerializer(UsageEvent.serializer()), adminOps.recentActivity(limit)),
                        ContentType.Application.Json,
                    )
                }
                // Machine-local op: configure the local coding-agent client with one click. The bridge
                // command is auto-detected next to the suite jar (SetupCli), so no path is needed; an
                // optional `bridge` override backs the console's Advanced field for a dev jar. Reachable
                // ONLY over loopback (absent when hosted, where students configure their own client).
                post("/admin/config/client") {
                    if (!AdminConsole.isLoopbackHost(call.request.local.remoteHost)) {
                        call.respondText("Local-only endpoint.", ContentType.Text.Plain, HttpStatusCode.Forbidden)
                        return@post
                    }
                    val bridge = call.request.queryParameters["bridge"]  // null/blank → auto-detect the bundled bridge
                    val url = call.request.queryParameters["url"] ?: "http://127.0.0.1:$port/"
                    val results = try {
                        SetupCli.configure(bridge, url)
                    } catch (e: IllegalStateException) {
                        call.respondText(
                            (e.message ?: "could not configure the client") +
                                "\nRun the suite from its installed launcher, or set the bridge under Advanced.",
                            ContentType.Text.Plain,
                            HttpStatusCode.BadRequest,
                        )
                        return@post
                    }
                    val body = if (results.isEmpty()) "No coding agents detected."
                    else results.joinToString("\n") { "${it.agent}: ${it.action} -> ${it.path}" }
                    call.respondText(body, ContentType.Text.Plain)
                }
                post("/admin/config/client/remove") {
                    if (!AdminConsole.isLoopbackHost(call.request.local.remoteHost)) {
                        call.respondText("Local-only endpoint.", ContentType.Text.Plain, HttpStatusCode.Forbidden)
                        return@post
                    }
                    val results = SetupCli.remove()
                    val body = if (results.isEmpty()) "No coding agents detected."
                    else results.joinToString("\n") { "${it.agent}: ${it.action} -> ${it.path}" }
                    call.respondText(body, ContentType.Text.Plain)
                }
                // Machine-local op: write the [capabilities] flags to the config file. Takes effect on
                // the next (launcher/host) restart — a server can't cleanly restart itself.
                post("/admin/config/capabilities") {
                    if (!AdminConsole.isLoopbackHost(call.request.local.remoteHost)) {
                        call.respondText("Local-only endpoint.", ContentType.Text.Plain, HttpStatusCode.Forbidden)
                        return@post
                    }
                    val q = call.request.queryParameters
                    val cfg = ServerConfig.load()
                    val updated = cfg.copy(
                        capabilities = CapabilitiesConfig(
                            sim = q["sim"]?.toBooleanStrictOrNull() ?: cfg.capabilities.sim,
                            book = q["book"]?.toBooleanStrictOrNull() ?: cfg.capabilities.book,
                            code = q["code"]?.toBooleanStrictOrNull() ?: cfg.capabilities.code,
                        ),
                    )
                    val file = ServerConfig.activeConfigFile()  // same file load() read; don't clobber ~/.ksl when redirected
                    java.nio.file.Files.createDirectories(file.parent)
                    java.nio.file.Files.writeString(file, ServerConfigToml.encode(updated))
                    call.respondText(
                        "Saved: sim=${updated.capabilities.sim}, book=${updated.capabilities.book}, " +
                            "code=${updated.capabilities.code}. Restart the suite (via the launcher) to apply.",
                        ContentType.Text.Plain,
                    )
                }
                // Usage export as CSV (download). Local, read-only, no PII.
                // Machine-local op: set the usage-study detail level (off/counts/full) LIVE and persist it
                // — the student's opt-out. Loopback-only, like the other config writes.
                post("/admin/config/usage") {
                    if (!AdminConsole.isLoopbackHost(call.request.local.remoteHost)) {
                        call.respondText("Local-only endpoint.", ContentType.Text.Plain, HttpStatusCode.Forbidden)
                        return@post
                    }
                    val level = ksl.service.usage.UsageLevel.fromString(call.request.queryParameters["level"])
                    usage?.setLevel?.invoke(level)
                    call.respondText("Usage study set to ${level.name.lowercase()}.", ContentType.Text.Plain)
                }
                get("/admin/usage/export.csv") {
                    call.response.headers.append("Content-Disposition", "attachment; filename=\"ksl-usage.csv\"")
                    // The durable all-time log, not the console's bounded current-run view.
                    call.respondText(AdminConsole.usageCsv(usage?.exportAll?.invoke() ?: emptyList()), ContentType.parse("text/csv"))
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
