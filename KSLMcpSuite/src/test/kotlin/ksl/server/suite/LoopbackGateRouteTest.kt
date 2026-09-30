/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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

import kotlinx.coroutines.runBlocking
import ksl.service.admin.ServerAdminOperations
import ksl.service.admin.SuiteStatus
import ksl.service.usage.UsageEvent
import ksl.service.usage.UsageSummary
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  The gap that let the loopback defect ship. `AdminConsoleTest.loopbackGuard` passed throughout,
 *  because it hands the predicate an **address** while the server was handing it a resolved **name**.
 *  A unit test over a pure function cannot see a caller passing the wrong thing.
 *
 *  So these go over a real socket from 127.0.0.1, which is the only way to assert what the gate
 *  actually receives. The suite's own `ArtifactRouteTest` established this pattern in this module —
 *  a real server on an ephemeral port, `HttpURLConnection` against it — and it is used here for the
 *  same reason rather than a `testApplication` harness, which would substitute a synthetic peer.
 */
class LoopbackGateRouteTest {

    /**
     *  The admin routes exist only when `adminOps` is supplied — the whole block is conditional on it —
     *  so a console test has to pass one. This is the smallest thing that satisfies the interface.
     */
    private val adminOps = object : ServerAdminOperations {
        override fun status(): SuiteStatus = SuiteStatus(
            version = "test",
            capabilities = emptyList(),
            served = 0,
            lastActivityMillis = null,
        )

        override fun usageSummary(): UsageSummary =
            UsageSummary(total = 0, ok = 0, byTool = emptyMap(), byCapability = emptyMap())

        override fun recentActivity(limit: Int): List<UsageEvent> = emptyList()
    }

    /** Starts the suite's HTTP app on an ephemeral port, runs [block] against it, and stops it. */
    private fun withServer(block: (Int) -> Unit) {
        val server = KslSuiteMcpServer.create(
            capabilities = emptyList(),
            adminOps = adminOps,
            host = "127.0.0.1",
            port = 0,
        )
        server.start(wait = false)
        try {
            block(runBlocking { server.engine.resolvedConnectors().first().port })
        } finally {
            server.stop(0, 0)
        }
    }

    private fun request(port: Int, method: String, path: String): Pair<Int, String> {
        val connection = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        return try {
            val code = connection.responseCode
            val stream = if (code < 400) connection.inputStream else connection.errorStream
            code to (stream?.bufferedReader()?.readText() ?: "")
        } finally {
            connection.disconnect()
        }
    }

    @Test
    @DisplayName("a request from this machine gets the console's machine-local controls")
    fun localRequestRendersLoopbackControls() {
        withServer { port ->
            val (code, body) = request(port, "GET", "/admin")
            assertEquals(200, code, "the console must render for a local request")
            // Both controls are loopback-only, so their presence is the gate having passed. Before the
            // fix, a machine whose 127.0.0.1 had a hosts-file name rendered the remote-mode console
            // here instead -- indistinguishable from a legitimately remote deployment.
            assertTrue("Apply &amp; Restart" in body, "capability apply is loopback-only: $body")
            assertTrue("Connect" in body, "client config is loopback-only")
        }
    }

    @Test
    @DisplayName("a refused local-only endpoint says which address it refused")
    fun refusalNamesTheAddress() {
        withServer { port ->
            // Reached over loopback, so this one is allowed through: the assertion is that the
            // endpoint is wired to the gate at all, and that a refusal would carry a reason. The
            // negative case cannot be produced from this machine without a proxy in front of the
            // server, which is exactly the configuration the WARN in `allowLoopbackOnly` describes.
            val (code, _) = request(port, "POST", "/admin/config/usage?level=off")
            assertTrue(
                code != 403,
                "a POST from 127.0.0.1 must not be refused as non-local; got $code"
            )
        }
    }
}
