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

import ksl.agent.config.AgentConfigurator
import ksl.service.admin.SuiteStatus
import ksl.service.usage.UsageEvent
import ksl.service.usage.UsageSummary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The built-in web console the suite serves at `/admin` — the operator's answer to "is the KSL server
 * working, and if not, what do I do?", plus a usage-study surface. Server-rendered from the live
 * server state (via `InProcessAdminOperations`) and the local client-config; the header, capability
 * call-counts, and activity feed refresh live from the `/admin/events` SSE stream. Six workflow-driven
 * regions: status header, capabilities, clients, activity & usage, diagnostics, lifecycle.
 *
 * Rendering and the loopback check are pure functions (no HTTP), so they unit-test directly; the route
 * wiring in `KslSuiteMcpServer` is verified live at the phase gate.
 */
object AdminConsole {

    /** True when a request's remote address is the local loopback — the gate for machine-local ops. */
    fun isLoopbackHost(host: String): Boolean {
        val h = host.trim().lowercase()
        return h == "localhost" || h == "::1" || h == "0:0:0:0:0:0:0:1" || h.startsWith("127.")
    }

    /** The full operator console. [loopback] gates the machine-local action controls (client config, etc.). */
    fun renderConsole(
        status: SuiteStatus,
        usage: UsageSummary,
        activity: List<UsageEvent>,
        clients: List<AgentConfigurator.ClientState>,
        loopback: Boolean,
    ): String = buildString {
        append(PAGE_HEAD)
        append(statusHeader(status))
        append(capabilitiesSection(status, loopback))
        append(clientsSection(clients, loopback))
        append(activitySection(usage, activity))
        append(diagnosticsSection(status))
        append(lifecycleSection(loopback))
        append(script(loopback))
        append(PAGE_TAIL)
    }

    // ---- region 1: status header (W2 — the trust surface) ----
    private fun statusHeader(status: SuiteStatus): String {
        val degraded = status.capabilities.any { it.enabled && !it.ready }
        val lamp = if (degraded) "degraded" else "running"
        val stateText = if (degraded) "DEGRADED" else "RUNNING"
        val last = status.lastActivityMillis?.let { "last activity ${relTime(it)}" } ?: "no activity yet"
        return """
            <header class="hdr">
              <div class="lamp $lamp" id="lamp"></div>
              <div class="hdr-main">
                <div class="state" id="state">$stateText</div>
                <div class="id">KSL MCP Suite &middot; v${escape(status.version)}</div>
              </div>
              <div class="proc" id="proc"><b id="served">${status.served}</b> tool calls served &middot; <span id="last">$last</span></div>
            </header>
        """.trimIndent()
    }

    // ---- region 2: capabilities (W4 — the 1-of-3) ----
    private fun capabilitiesSection(status: SuiteStatus, loopback: Boolean): String {
        val rows = status.capabilities.joinToString("\n") { c ->
            val toggle = if (loopback)
                """<input type="checkbox" data-cap="${c.id}" ${if (c.enabled) "checked" else ""}>"""
            else if (c.enabled) "on" else "off"
            """
              <tr>
                <td>$toggle</td>
                <td class="cap">${escape(c.id)}</td>
                <td>${if (c.ready) "<span class='ok'>ready</span>" else "<span class='warn'>not ready</span>"}</td>
                <td class="detail">${escape(c.detail)}</td>
                <td class="num" data-calls="${c.id}">${c.callCount}</td>
              </tr>
            """.trimIndent()
        }
        val apply = if (loopback)
            """<div class="row"><button id="applyCaps">Apply &amp; Restart</button>
               <span class="hint">Toggling a surface takes effect after the launcher restarts the suite.</span></div>"""
        else ""
        return """
            <section>
              <h2>Capabilities</h2>
              <table>
                <tr><th></th><th>surface</th><th>readiness</th><th>detail</th><th>calls</th></tr>
                $rows
              </table>
              $apply
            </section>
        """.trimIndent()
    }

    // ---- region 3: clients (W1 — setup) ----
    private fun clientsSection(clients: List<AgentConfigurator.ClientState>, loopback: Boolean): String {
        val rows = if (clients.isEmpty()) {
            "<tr><td colspan='2' class='detail'>No coding agents detected on this machine.</td></tr>"
        } else {
            clients.joinToString("\n") { c ->
                val badge = if (c.present) "<span class='ok'>configured</span>" else "<span class='warn'>not configured</span>"
                "<tr><td class='cap'>${escape(c.agent)}</td><td>$badge <span class='detail'>${escape(c.path)}</span></td></tr>"
            }
        }
        val controls = if (loopback)
            """<div class="row">
                 <input id="bridgeCmd" placeholder="ksl-bridge command (path to the bridge launcher)">
                 <button id="cfgClient">Configure</button>
                 <button id="rmClient" class="secondary">Remove</button>
               </div>
               <div class="hint">Writes the one <code>ksl-suite</code> entry (launching the bridge at this suite's URL) into each detected agent.</div>"""
        else "<div class='hint'>Client configuration is available from the console on the server's own machine.</div>"
        return """
            <section>
              <h2>Clients</h2>
              <table>$rows</table>
              $controls
            </section>
        """.trimIndent()
    }

    // ---- region 4: activity & usage (W5 — the researcher surface) ----
    private fun activitySection(usage: UsageSummary, activity: List<UsageEvent>): String {
        val top = usage.byTool.entries.sortedByDescending { it.value }.take(8)
        val maxN = (top.maxOfOrNull { it.value } ?: 1).coerceAtLeast(1)
        val bars = top.joinToString("\n") { (tool, n) ->
            val pct = (n * 100 / maxN).coerceIn(2, 100)
            "<div class='bar-row'><span class='bar-label'>${escape(tool)}</span><span class='bar'><span style='width:${pct}%'></span></span><span class='num'>$n</span></div>"
        }.ifBlank { "<div class='detail'>(no tool calls recorded yet)</div>" }
        val rate = "%.1f".format(usage.successRate * 100)
        val feed = activity.take(20).joinToString("\n") { e ->
            val ok = if (e.ok) "<span class='ok'>ok</span>" else "<span class='err'>err</span>"
            "<tr><td class='detail'>${relTime(e.timestampMillis)}</td><td>${escape(e.capability)}</td><td class='cap'>${escape(e.tool)}</td><td class='num'>${e.durationMs}ms</td><td>$ok</td></tr>"
        }.ifBlank { "<tr><td colspan='5' class='detail'>(no recent activity)</td></tr>" }
        return """
            <section>
              <h2>Activity &amp; usage</h2>
              <div class="two-col">
                <div>
                  <h3>Live activity</h3>
                  <table id="feed"><tr><th>when</th><th>surface</th><th>tool</th><th>ms</th><th></th></tr>$feed</table>
                </div>
                <div>
                  <h3>Usage &middot; ${usage.total} calls, ${rate}% ok</h3>
                  $bars
                  <div class="row"><a class="btn" href="/admin/usage/export.csv">Export usage (CSV)</a></div>
                  <div class="hint">Data stays on this machine &mdash; nothing is transmitted.</div>
                </div>
              </div>
            </section>
        """.trimIndent()
    }

    // ---- region 5: diagnostics (W3 — troubleshooting) ----
    private fun diagnosticsSection(status: SuiteStatus): String {
        val caps = status.capabilities.joinToString(", ") { "${it.id}=${if (it.enabled) "on" else "off"}" }
        val diag = "KSL MCP Suite v${status.version} | capabilities: $caps | served: ${status.served}"
        return """
            <section>
              <h2>Diagnostics</h2>
              <pre id="diag">${escape(diag)}</pre>
              <div class="row"><button id="copyDiag" class="secondary">Copy diagnostics</button></div>
              <div class="hint">The full server log is under <code>~/.ksl/logs</code>.</div>
            </section>
        """.trimIndent()
    }

    // ---- region 6: lifecycle (W6 — background operation) ----
    private fun lifecycleSection(loopback: Boolean): String {
        val note = if (loopback)
            "This console is served by the suite itself; closing this tab leaves the server running. Start/stop and restart are owned by the launcher (locally) or the platform (when hosted)."
        else
            "Lifecycle (start/stop/restart) is owned by the host platform."
        return """
            <section>
              <h2>Lifecycle</h2>
              <div class="hint">$note</div>
            </section>
        """.trimIndent()
    }

    /** Usage export as CSV (one row per recorded call). */
    fun usageCsv(events: List<UsageEvent>): String = buildString {
        appendLine("timestampMillis,capability,tool,durationMs,ok,client")
        events.forEach { e ->
            appendLine("${e.timestampMillis},${csv(e.capability)},${csv(e.tool)},${e.durationMs},${e.ok},${csv(e.client ?: "")}")
        }
    }

    private fun csv(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    private fun relTime(millis: Long): String {
        val ageMs = System.currentTimeMillis() - millis
        return when {
            ageMs < 0 -> timeOf(millis)
            ageMs < 60_000 -> "${ageMs / 1000}s ago"
            ageMs < 3_600_000 -> "${ageMs / 60_000}m ago"
            ageMs < 86_400_000 -> "${ageMs / 3_600_000}h ago"
            else -> timeOf(millis)
        }
    }

    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneId.systemDefault())
    private fun timeOf(millis: Long): String = timeFmt.format(Instant.ofEpochMilli(millis))

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun script(loopback: Boolean): String = """
        <script>
          // Live status via SSE (the server sets retry, so EventSource re-polls on the server's cadence).
          const es = new EventSource('/admin/events');
          es.onmessage = (e) => {
            try {
              const s = JSON.parse(e.data);
              const degraded = (s.capabilities || []).some(c => c.enabled && !c.ready);
              document.getElementById('state').textContent = degraded ? 'DEGRADED' : 'RUNNING';
              document.getElementById('lamp').className = 'lamp ' + (degraded ? 'degraded' : 'running');
              document.getElementById('served').textContent = s.served;
              (s.capabilities || []).forEach(c => {
                const el = document.querySelector('[data-calls="' + c.id + '"]');
                if (el) el.textContent = c.callCount;
              });
            } catch (_) {}
          };
          es.onerror = () => { document.getElementById('lamp').className = 'lamp down'; document.getElementById('state').textContent = 'RECONNECTING'; };
          ${if (loopback) LOOPBACK_JS else ""}
        </script>
    """.trimIndent()

    private const val LOOPBACK_JS = """
          async function post(url) { const r = await fetch(url, {method:'POST'}); return r.text(); }
          const applyBtn = document.getElementById('applyCaps');
          if (applyBtn) applyBtn.onclick = async () => {
            const params = Array.from(document.querySelectorAll('[data-cap]'))
              .map(cb => cb.dataset.cap + '=' + cb.checked).join('&');
            alert(await post('/admin/config/capabilities?' + params));
          };
          const cfgBtn = document.getElementById('cfgClient');
          if (cfgBtn) cfgBtn.onclick = async () => {
            const bridge = document.getElementById('bridgeCmd').value.trim();
            if (!bridge) { alert('Enter the ksl-bridge command first.'); return; }
            alert(await post('/admin/config/client?bridge=' + encodeURIComponent(bridge)));
            location.reload();
          };
          const rmBtn = document.getElementById('rmClient');
          if (rmBtn) rmBtn.onclick = async () => { alert(await post('/admin/config/client/remove')); location.reload(); };
          const copyBtn = document.getElementById('copyDiag');
          if (copyBtn) copyBtn.onclick = () => navigator.clipboard.writeText(document.getElementById('diag').textContent);
    """

    private val PAGE_HEAD = """
        <!doctype html>
        <html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
        <title>KSL Server Manager</title>
        <style>
          :root { --bg:#f7f7f8; --card:#fff; --fg:#1a1a1a; --muted:#6b7280; --line:#e5e7eb; --accent:#2563eb; }
          @media (prefers-color-scheme: dark) { :root { --bg:#0f1115; --card:#181b21; --fg:#e6e6e6; --muted:#9aa0aa; --line:#2a2f39; --accent:#5b9bff; } }
          * { box-sizing: border-box; } body { margin:0; background:var(--bg); color:var(--fg);
            font:14px/1.5 -apple-system,Segoe UI,Roboto,sans-serif; padding:1rem; max-width:960px; margin:0 auto; }
          h1,h2,h3 { font-weight:600; } h2 { font-size:1rem; border-bottom:1px solid var(--line); padding-bottom:.3rem; }
          section { background:var(--card); border:1px solid var(--line); border-radius:10px; padding:1rem 1.2rem; margin:1rem 0; }
          .hdr { display:flex; align-items:center; gap:1rem; background:var(--card); border:1px solid var(--line);
            border-radius:10px; padding:1rem 1.2rem; }
          .hdr-main { flex:1; } .state { font-weight:700; letter-spacing:.05em; } .id { color:var(--muted); font-size:.85rem; }
          .proc { color:var(--muted); font-size:.85rem; text-align:right; }
          .lamp { width:14px; height:14px; border-radius:50%; background:var(--muted); box-shadow:0 0 0 3px rgba(0,0,0,.05); }
          .lamp.running { background:#16a34a; } .lamp.degraded { background:#d97706; } .lamp.down { background:#dc2626; }
          table { width:100%; border-collapse:collapse; } th,td { text-align:left; padding:.35rem .5rem; border-bottom:1px solid var(--line); }
          th { color:var(--muted); font-weight:500; font-size:.8rem; } .cap { font-family:ui-monospace,monospace; }
          .num { text-align:right; font-variant-numeric:tabular-nums; } .detail { color:var(--muted); font-size:.85rem; }
          .ok { color:#16a34a; } .warn { color:#d97706; } .err { color:#dc2626; }
          .row { display:flex; gap:.6rem; align-items:center; margin-top:.8rem; flex-wrap:wrap; }
          .hint { color:var(--muted); font-size:.82rem; margin-top:.5rem; }
          button,.btn { font:inherit; background:var(--accent); color:#fff; border:0; border-radius:6px; padding:.4rem .8rem;
            cursor:pointer; text-decoration:none; } button.secondary { background:transparent; color:var(--accent); border:1px solid var(--accent); }
          input { font:inherit; padding:.4rem .6rem; border:1px solid var(--line); border-radius:6px; background:var(--bg); color:var(--fg); flex:1; min-width:16rem; }
          pre { background:var(--bg); border:1px solid var(--line); border-radius:6px; padding:.6rem; overflow-x:auto; font-size:.82rem; }
          .two-col { display:grid; grid-template-columns:1fr 1fr; gap:1.2rem; } @media (max-width:680px){ .two-col{ grid-template-columns:1fr; } }
          .bar-row { display:flex; align-items:center; gap:.5rem; margin:.25rem 0; }
          .bar-label { font-family:ui-monospace,monospace; font-size:.8rem; width:9rem; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
          .bar { flex:1; height:.7rem; background:var(--line); border-radius:4px; overflow:hidden; }
          .bar > span { display:block; height:100%; background:var(--accent); }
        </style></head><body>
    """.trimIndent()

    private val PAGE_TAIL = "</body></html>"
}
