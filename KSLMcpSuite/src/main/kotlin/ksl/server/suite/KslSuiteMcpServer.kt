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
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import ksl.book.mcp.BookMcpServer
import ksl.book.mcp.BookSearch
import ksl.book.mcp.BookStore
import ksl.code.mcp.CodeMcpServer
import ksl.code.mcp.CodeSearch
import ksl.code.mcp.CodeStore
import ksl.server.mcp.KslMcpServer
import ksl.server.mcp.KslMcpTools
import ksl.service.config.HealthEndpoints
import ksl.service.config.ServerAuth

/**
 * The KSL MCP Suite: ONE long-running HTTP MCP server that aggregates all three KSL tool surfaces —
 * the simulation server (run / experiment / optimize / fit), the source-code search server, and the
 * textbook search server — on a single MCP endpoint, so a client configures one server and gets
 * every tool. The heavy per-surface state (the bundle registry + run services, and the two Lucene
 * indexes) is constructed once and shared; the SDK's `mcp { }` installer builds a fresh aggregated
 * `Server` per SSE session, all delegating to that shared state.
 *
 * Transport note (D9): this serves the SDK's SSE transport (proven, multi-session). Streamable HTTP
 * is a deferred refinement — the Phase-4 bridge connects via the client-side `mcpSse` transport.
 */
object KslSuiteMcpServer {

    const val SUITE_NAME: String = "ksl-suite-mcp"

    /**
     * Builds one MCP `Server` carrying the union of all three tool surfaces. Tool names are disjoint
     * across the surfaces (the simulation `run_*`/`get_started`/…, code `search_code`/…, textbook
     * `search_textbook`/…), so registering all three on one server never collides. The aggregated
     * capabilities advertise both tools and prompts, because the simulation surface registers guided
     * prompts (`KslMcpServer.registerKslTools`) while book/code register tools only.
     */
    fun buildAggregatedServer(
        kslTools: KslMcpTools,
        bookStore: BookStore,
        bookSearch: BookSearch,
        codeStore: CodeStore,
        codeSearch: CodeSearch,
    ): Server {
        val server = Server(
            serverInfo = Implementation(name = SUITE_NAME, version = ksl.service.config.BuildInfo.version),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true),
                    prompts = ServerCapabilities.Prompts(listChanged = false),
                ),
            ),
            instructions = SUITE_INSTRUCTIONS,
        )
        KslMcpServer.registerKslTools(server, kslTools)
        BookMcpServer.registerBookTools(server, bookStore, bookSearch)
        CodeMcpServer.registerCodeTools(server, codeStore, codeSearch)
        return server
    }

    /**
     * Creates (but does not start) an embedded CIO server exposing the aggregated MCP surface over
     * SSE, plus `/health` `/ready` `/version`. Mirrors `ksl.server.mcp.KslMcpHttpServer`; the `mcp { }`
     * block builds a fresh aggregated server per session over the shared state passed in here.
     */
    fun create(
        kslTools: KslMcpTools,
        bookStore: BookStore,
        bookSearch: BookSearch,
        codeStore: CodeStore,
        codeSearch: CodeSearch,
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
        }
        mcp { buildAggregatedServer(kslTools, bookStore, bookSearch, codeStore, codeSearch) }
    }

    // Connect-time orientation for all three surfaces, injected into the model's context on connect.
    private const val SUITE_INSTRUCTIONS =
        "This is the KSL MCP Suite for a simulation course using the Kotlin Simulation Library (KSL). " +
            "It exposes three capabilities on one server; route by the user's intent:\n" +
            "1. RUN and analyze simulation models — single runs, scenario comparisons, designed " +
            "experiments, simulation-optimization, and distribution fitting (tools: run_model, " +
            "run_experiment, run_optimization, fit_dataset, and more). If the user is unsure what to " +
            "do, call get_started, which returns the live model catalog and routes to a workflow.\n" +
            "2. SEARCH the KSL SOURCE CODE and API — for ANY question about a KSL class, function, or " +
            "API, call search_code FIRST, then get_class and get_example, and cite the returned source " +
            "URLs. Do not invent KSL names or signatures; verify them here.\n" +
            "3. SEARCH the KSL TEXTBOOK — for ANY simulation concept, method, or homework question, " +
            "call search_textbook FIRST (before general knowledge or web search), then get_section and " +
            "cite the section URLs."
}
