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

import ksl.service.admin.SuiteStatus
import ksl.service.usage.UsageSummary

/**
 * The built-in web console the suite serves at `/admin`. This is the Phase-B7 SKELETON — a minimal
 * server-rendered status page + the pure logic behind the routes (rendering, the loopback guard) —
 * so the plumbing (route, `/admin/events` SSE, a command POST, the loopback guard) is testable now.
 * The full six-region operator UX is Phase E; it can adopt kotlinx.html then.
 *
 * Rendering and the loopback check are pure functions so they unit-test without an HTTP server; the
 * route wiring in `KslSuiteMcpServer` is verified live at the phase gate.
 */
object AdminConsole {

    /** True when a request's remote address is the local loopback — the gate for machine-local ops. */
    fun isLoopbackHost(host: String): Boolean {
        val h = host.trim().lowercase()
        return h == "localhost" || h == "::1" || h == "0:0:0:0:0:0:0:1" || h.startsWith("127.")
    }

    /** A minimal status page: identity, per-capability readiness, and the top tools by call count. */
    fun renderStatusHtml(status: SuiteStatus, usage: UsageSummary): String {
        val rows = status.capabilities.joinToString("\n") { c ->
            "        <tr><td>${c.id}</td><td>${if (c.enabled) "on" else "off"}</td>" +
                "<td>${if (c.ready) "ready" else "&mdash;"}</td><td>${escape(c.detail)}</td><td>${c.callCount}</td></tr>"
        }
        val topTools = usage.byTool.entries
            .sortedByDescending { it.value }
            .take(10)
            .joinToString("\n") { "        <li>${escape(it.key)}: ${it.value}</li>" }
            .ifBlank { "        <li>(no tool calls recorded yet)</li>" }
        val rate = "%.1f".format(usage.successRate * 100)
        return """
            <!doctype html>
            <html lang="en">
            <head><meta charset="utf-8"><title>KSL Server Manager</title></head>
            <body>
            <h1>KSL MCP Suite</h1>
            <p>version ${escape(status.version)} &middot; ${status.served} tool calls served &middot; success ${rate}%</p>
            <h2>Capabilities</h2>
            <table border="1" cellpadding="4">
                <tr><th>surface</th><th>enabled</th><th>ready</th><th>detail</th><th>calls</th></tr>
$rows
            </table>
            <h2>Top tools</h2>
            <ul>
$topTools
            </ul>
            <p><small>Data stays on this machine.</small></p>
            <script>
              const es = new EventSource('/admin/events');
              es.onmessage = (e) => console.log('status', e.data);
            </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
