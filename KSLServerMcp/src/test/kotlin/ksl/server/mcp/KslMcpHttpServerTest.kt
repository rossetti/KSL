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

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import ksl.service.capability.run.BundleRegistry
import ksl.service.config.BuildInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * End-to-end proof of the Streamable HTTP (SSE) transport: an embedded server is
 * started on an ephemeral port and driven by the MCP SDK's *own client* over
 * HTTP — listing the tool surface and invoking `list_bundles`. This exercises
 * the real MCP handshake (initialize → list tools → call tool) across the wire,
 * which the stdio cut could not.
 */
class KslMcpHttpServerTest {

    @Test
    fun `serves the tool surface over the HTTP transport`() = runBlocking {
        val registry = BundleRegistry.fromClasspath()
        val tools = KslMcpTools(registry)
        val server = KslMcpHttpServer.create(tools, host = "127.0.0.1", port = 0)
        server.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port

        val httpClient = HttpClient(CIO) { install(SSE) }
        val client = Client(Implementation(name = "ksl-test-client", version = "1.0.0"))
        try {
            client.connect(SseClientTransport(httpClient, "http://127.0.0.1:$port"))

            val toolNames = client.listTools().tools.map { it.name }.toSet()
            assertTrue(
                toolNames.containsAll(setOf("list_bundles", "describe_model", "run_model", "fit_dataset")),
                "tools served over HTTP: $toolNames",
            )

            val result = client.callTool("list_bundles", emptyMap())
            val text = (result?.content?.firstOrNull() as? TextContent)?.text ?: ""
            assertTrue("ksl.examples.mm1" in text, "list_bundles over HTTP returned: $text")
        } finally {
            httpClient.close()
            server.stop(250, 500)
            tools.close()
            registry.close()
        }
    }

    @Test
    fun `serves health and readiness endpoints`() = runBlocking {
        val registry = BundleRegistry.fromClasspath()
        val tools = KslMcpTools(registry)
        val ready = java.util.concurrent.atomic.AtomicBoolean(true)
        val server = KslMcpHttpServer.create(tools, host = "127.0.0.1", port = 0, ready = ready::get)
        server.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val http = HttpClient(CIO)
        try {
            val health = http.get("http://127.0.0.1:$port/health").bodyAsText().replace(" ", "")
            assertTrue("\"status\":\"UP\"" in health, "health: $health")
            assertTrue("ksl-mcp" in health, "health should name the service")
            val readyBody = http.get("http://127.0.0.1:$port/ready").bodyAsText().replace(" ", "")
            assertTrue("\"ready\":true" in readyBody, "ready: $readyBody")
            // /version reports the build version (A7); "dev" when run from classes.
            val versionBody = http.get("http://127.0.0.1:$port/version").bodyAsText().replace(" ", "")
            assertTrue("\"version\":\"${BuildInfo.version}\"" in versionBody, "version: $versionBody")
            assertTrue("ksl-mcp" in versionBody, "version should name the service")
        } finally {
            http.close()
            server.stop(250, 500)
            tools.close()
            registry.close()
        }
    }

    @Test
    fun `bearer token gate protects routes while probes stay public`() = runBlocking {
        val registry = BundleRegistry.fromClasspath()
        val tools = KslMcpTools(registry)
        val token = "lab-key-xyz"
        val server = KslMcpHttpServer.create(tools, host = "127.0.0.1", port = 0, ready = { true }, authToken = token)
        server.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val http = HttpClient(CIO)
        try {
            // The probe path stays public even with auth on.
            assertEquals(HttpStatusCode.OK, http.get("http://127.0.0.1:$port/health").status, "/health is public")
            // The gate runs before routing, so any non-public path is 401 without a
            // token, and passes the gate (not 401) with the right token.
            assertEquals(
                HttpStatusCode.Unauthorized,
                http.get("http://127.0.0.1:$port/protected-check").status,
                "no token -> 401",
            )
            val withToken = http.get("http://127.0.0.1:$port/protected-check") {
                header("Authorization", "Bearer $token")
            }.status
            assertNotEquals(HttpStatusCode.Unauthorized, withToken, "valid token passes the gate")
        } finally {
            http.close()
            server.stop(250, 500)
            tools.close()
            registry.close()
        }
    }
}
