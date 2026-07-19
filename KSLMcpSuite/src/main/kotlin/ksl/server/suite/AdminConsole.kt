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
 * call-counts, activity feed, and usage bars refresh live from the `/admin/events` SSE stream (the feed
 * and bars re-fetch whenever the served count changes, so a tool call from any client appears without a
 * page reload). A manual Refresh button forces an immediate update.
 *
 * The regions are ordered by the operator's workflow: **Clients first**, then capabilities, activity &
 * usage, and diagnostics. The activity feed shows a bounded current-run window (last 10 of this run) —
 * the durable, all-time study log is the append-only `usage.jsonl` (CSV export). If the server stops,
 * the SSE drop is surfaced as a clear "stale page" banner rather than a page that silently looks live.
 *
 * Rendering and the loopback check are pure functions (no HTTP), so they unit-test directly; the route
 * wiring in `KslSuiteMcpServer` is verified live at the phase gate.
 */
object AdminConsole {

    /** How many recent events the live feed shows (a bounded current-run window). */
    private const val FEED_LIMIT = 10

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
        append(staleBanner())
        append(statusHeader(status))
        append(clientsSection(clients, loopback))     // first: setup is the prerequisite for everything else
        append(capabilitiesSection(status, loopback))
        append(activitySection(usage, activity))
        append(diagnosticsSection(status))
        append(script(loopback))
        append(PAGE_TAIL)
    }

    // A disconnected-state banner, hidden until the SSE stream drops and can't reconnect (server stopped).
    private fun staleBanner(): String =
        "<div id=\"stale\" class=\"stale\" hidden>&#9888; Server stopped &mdash; this page is stale. " +
            "Restart KSL Server (menu&#8209;bar icon), then reload this page.</div>"

    // ---- region 1: status header (the trust surface) ----
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

    // ---- region 2: clients (setup — comes FIRST; nothing else works until an assistant is connected) ----
    private fun clientsSection(clients: List<AgentConfigurator.ClientState>, loopback: Boolean): String {
        val anyConfigured = clients.any { it.present }
        val allConfigured = clients.isNotEmpty() && clients.all { it.present }
        val rows = if (clients.isEmpty()) {
            "<tr><td colspan='2' class='detail'>No coding assistant found on this machine (Claude Desktop or Codex).</td></tr>"
        } else {
            clients.joinToString("\n") { c ->
                val badge = if (c.present)
                    "<span class='ok' title='${escape(c.path)}'>connected</span>"
                else
                    "<span class='muted'>not connected</span>"
                "<tr><td class='cap'>${escape(c.agent)}</td><td>$badge</td></tr>"
            }
        }
        // A standing reminder once configured — the tools appear only after the assistant restarts.
        val connectedNote = if (anyConfigured)
            "<div class=\"hint\">&#10003; Connected &mdash; <b>restart Claude Desktop / Codex</b> so it loads the KSL tools.</div>"
        else ""
        val controls = when {
            !loopback ->
                "<div class='hint'>Assistant setup is available from the console on the server's own machine.</div>"
            clients.isEmpty() -> ""
            else -> buildString {
                append("<div class='row'>")
                if (!allConfigured) append(
                    "<button id=\"cfgClient\" title=\"Adds KSL to your assistant(s), pointed at this running server. " +
                        "Restart the assistant afterward so it loads the tools.\">Connect</button>",
                )
                if (anyConfigured) append(
                    "<button id=\"rmClient\" class=\"secondary\" title=\"Removes the KSL entry from your assistant(s).\">Disconnect</button>",
                )
                append("</div>")
                append(
                    "<details class=\"adv\"><summary>Advanced</summary>" +
                        "<div class=\"row\"><input id=\"bridgeCmd\" placeholder=\"bridge command (leave blank to auto-detect)\"></div>" +
                        "<div class=\"hint\">Only needed when running the suite from a dev jar that isn't installed beside the bridge.</div>" +
                        "</details>",
                )
            }
        }
        val emphasize = loopback && clients.isNotEmpty() && !anyConfigured
        val cls = if (emphasize) "cta" else ""
        val heading = if (emphasize) "Connect your assistant" else "Clients"
        val lead = if (emphasize)
            "<div class=\"hint\">One click adds KSL to your coding assistant &mdash; do this first.</div>"
        else ""
        return """
            <section class="$cls">
              <h2>$heading</h2>
              $lead
              <table>$rows</table>
              $connectedNote
              $controls
            </section>
        """.trimIndent()
    }

    // ---- region 3: capabilities (the 1-of-3) ----
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
            """<div class="row"><button id="applyCaps" title="Saves which surfaces are enabled. Takes effect when the server next restarts.">Apply &amp; Restart</button>
               <span class="hint">Turn a surface on or off, then restart the server (from the KSL Server menu-bar app) to apply.</span></div>"""
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

    // ---- region 4: activity & usage (the researcher surface) ----
    private fun activitySection(usage: UsageSummary, activity: List<UsageEvent>): String {
        val shown = activity.take(FEED_LIMIT)
        val top = usage.byTool.entries.sortedByDescending { it.value }.take(8)
        val maxN = (top.maxOfOrNull { it.value } ?: 1).coerceAtLeast(1)
        val bars = top.joinToString("\n") { (tool, n) ->
            val pct = (n * 100 / maxN).coerceIn(2, 100)
            "<div class='bar-row'><span class='bar-label'>${escape(tool)}</span><span class='bar'><span style='width:${pct}%'></span></span><span class='num'>$n</span></div>"
        }.ifBlank { "<div class='detail'>(no tool calls recorded yet)</div>" }
        val rate = "%.1f".format(usage.successRate * 100)
        val feed = shown.joinToString("\n") { e ->
            val ok = if (e.ok) "<span class='ok'>ok</span>" else "<span class='err'>err</span>"
            "<tr><td class='detail'>${relTime(e.timestampMillis)}</td><td>${escape(e.capability)}</td><td class='cap'>${escape(e.tool)}</td><td class='num'>${e.durationMs}ms</td><td>$ok</td></tr>"
        }.ifBlank { "<tr><td colspan='5' class='detail'>(no recent activity)</td></tr>" }
        return """
            <section>
              <h2>Activity &amp; usage</h2>
              <div class="two-col">
                <div>
                  <h3 id="feedTitle">Live activity &mdash; last ${shown.size} of ${usage.total}</h3>
                  <table id="feed"><tr><th>when</th><th>surface</th><th>tool</th><th>ms</th><th></th></tr>$feed</table>
                </div>
                <div>
                  <h3 id="usageTitle">Usage &middot; ${usage.total} calls, ${rate}% ok</h3>
                  <div id="usageBars">$bars</div>
                  <div class="row">
                    <a class="btn" href="/admin/usage/export.csv">Export usage (CSV)</a>
                    <button id="refreshUsage" class="secondary" title="Re-read the current-run usage now.">Refresh</button>
                  </div>
                  <div class="hint">Live current-run view; the full study is the append-only log. Nothing is transmitted off this machine.</div>
                </div>
              </div>
            </section>
        """.trimIndent()
    }

    // ---- region 5: diagnostics (troubleshooting) ----
    private fun diagnosticsSection(status: SuiteStatus): String {
        val caps = status.capabilities.joinToString(", ") { "${it.id}=${if (it.enabled) "on" else "off"}" }
        val diag = "KSL MCP Suite v${status.version} | capabilities: $caps | served: ${status.served}"
        return """
            <section>
              <h2>Diagnostics</h2>
              <div class="hint">A copy-paste summary for a bug report or support email.</div>
              <pre id="diag">${escape(diag)}</pre>
              <div class="row"><button id="copyDiag" class="secondary" title="Copies the version + capability summary for a bug report.">Copy diagnostics</button></div>
              <div class="hint">The full server log is under <code>~/.ksl/logs</code>.</div>
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

    // Vanilla JS. Client-side render of the feed + usage bars mirrors the server-side markup, so the SSE
    // tick (and the Refresh button) update those regions in place — no page reload. JS strings are
    // double-quoted and the HTML they build uses single-quoted attributes, so no escaping is needed.
    private fun script(loopback: Boolean): String = """
        <script>
          function esc(s){return String(s).replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;");}
          function relTime(ms){var a=Date.now()-ms;if(a<0)return new Date(ms).toLocaleTimeString();
            if(a<60000)return Math.floor(a/1000)+"s ago";if(a<3600000)return Math.floor(a/60000)+"m ago";
            if(a<86400000)return Math.floor(a/3600000)+"h ago";return new Date(ms).toLocaleString();}
          function renderFeed(events,total){
            var rows=events.map(function(e){return "<tr><td class='detail'>"+relTime(e.timestampMillis)+"</td><td>"+esc(e.capability)
              +"</td><td class='cap'>"+esc(e.tool)+"</td><td class='num'>"+e.durationMs+"ms</td><td>"
              +(e.ok?"<span class='ok'>ok</span>":"<span class='err'>err</span>")+"</td></tr>";}).join("")
              ||"<tr><td colspan='5' class='detail'>(no recent activity)</td></tr>";
            document.getElementById("feed").innerHTML="<tr><th>when</th><th>surface</th><th>tool</th><th>ms</th><th></th></tr>"+rows;
            var ft=document.getElementById("feedTitle");if(ft)ft.innerHTML="Live activity &mdash; last "+events.length+" of "+total;
          }
          function renderUsage(s){
            var total=s.total||0,ok=s.ok||0,rate=total?(100*ok/total).toFixed(1):"0.0";
            document.getElementById("usageTitle").innerHTML="Usage &middot; "+total+" calls, "+rate+"% ok";
            var t=s.byTool||{},top=Object.keys(t).map(function(k){return [k,t[k]];}).sort(function(a,b){return b[1]-a[1];}).slice(0,8);
            var maxN=Math.max.apply(null,[1].concat(top.map(function(x){return x[1];})));
            var bars=top.map(function(x){var pct=Math.min(100,Math.max(2,Math.round(x[1]*100/maxN)));
              return "<div class='bar-row'><span class='bar-label'>"+esc(x[0])+"</span><span class='bar'><span style='width:"+pct+"%'></span></span><span class='num'>"+x[1]+"</span></div>";}).join("")
              ||"<div class='detail'>(no tool calls recorded yet)</div>";
            document.getElementById("usageBars").innerHTML=bars;
          }
          var lastServed=-1;
          async function refreshUsage(){
            try{
              var r=await Promise.all([fetch("/admin/usage").then(function(x){return x.json();}),
                fetch("/admin/activity?limit=10").then(function(x){return x.json();})]);
              renderUsage(r[0]);renderFeed(r[1],r[0].total||0);lastServed=r[0].total||0;
            }catch(_){}
          }
          var staleTimer=null;
          function showStale(){var b=document.getElementById("stale");if(b)b.hidden=false;document.body.classList.add("disconnected");}
          function hideStale(){var b=document.getElementById("stale");if(b)b.hidden=true;document.body.classList.remove("disconnected");if(staleTimer){clearTimeout(staleTimer);staleTimer=null;}}
          var es=new EventSource("/admin/events");
          es.onmessage=function(e){
            hideStale();
            try{
              var s=JSON.parse(e.data);
              var degraded=(s.capabilities||[]).some(function(c){return c.enabled&&!c.ready;});
              document.getElementById("state").textContent=degraded?"DEGRADED":"RUNNING";
              document.getElementById("lamp").className="lamp "+(degraded?"degraded":"running");
              document.getElementById("served").textContent=s.served;
              (s.capabilities||[]).forEach(function(c){var el=document.querySelector('[data-calls="'+c.id+'"]');if(el)el.textContent=c.callCount;});
              if(s.served!==lastServed){lastServed=s.served;refreshUsage();}
            }catch(_){}
          };
          es.onerror=function(){document.getElementById("lamp").className="lamp down";document.getElementById("state").textContent="DISCONNECTED";if(!staleTimer)staleTimer=setTimeout(showStale,5000);};
          var rf=document.getElementById("refreshUsage");if(rf)rf.onclick=refreshUsage;
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
            // The bridge is auto-detected; only send an override when the Advanced field is filled in.
            const adv = document.getElementById('bridgeCmd');
            const b = adv && adv.value.trim();
            const q = b ? ('?bridge=' + encodeURIComponent(b)) : '';
            const res = await post('/admin/config/client' + q);
            alert(res + '\n\nNow RESTART Claude Desktop / Codex so it loads the KSL tools.');
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
          section.cta { border-color:var(--accent); box-shadow:0 0 0 2px rgba(37,99,235,.14); }
          .stale { position:sticky; top:.4rem; z-index:10; background:#dc2626; color:#fff; padding:.6rem 1rem;
            border-radius:8px; margin-bottom:1rem; font-weight:600; }
          body.disconnected .hdr, body.disconnected section { opacity:.55; }
          .hdr { display:flex; align-items:center; gap:1rem; background:var(--card); border:1px solid var(--line);
            border-radius:10px; padding:1rem 1.2rem; }
          .hdr-main { flex:1; } .state { font-weight:700; letter-spacing:.05em; } .id { color:var(--muted); font-size:.85rem; }
          .proc { color:var(--muted); font-size:.85rem; text-align:right; }
          .lamp { width:14px; height:14px; border-radius:50%; background:var(--muted); box-shadow:0 0 0 3px rgba(0,0,0,.05); }
          .lamp.running { background:#16a34a; } .lamp.degraded { background:#d97706; } .lamp.down { background:#dc2626; }
          table { width:100%; border-collapse:collapse; } th,td { text-align:left; padding:.35rem .5rem; border-bottom:1px solid var(--line); }
          th { color:var(--muted); font-weight:500; font-size:.8rem; } .cap { font-family:ui-monospace,monospace; }
          .num { text-align:right; font-variant-numeric:tabular-nums; } .detail { color:var(--muted); font-size:.85rem; }
          .ok { color:#16a34a; } .warn { color:#d97706; } .err { color:#dc2626; } .muted { color:var(--muted); }
          .row { display:flex; gap:.6rem; align-items:center; margin-top:.8rem; flex-wrap:wrap; }
          .hint { color:var(--muted); font-size:.82rem; margin-top:.5rem; }
          button,.btn { font:inherit; background:var(--accent); color:#fff; border:0; border-radius:6px; padding:.4rem .8rem;
            cursor:pointer; text-decoration:none; } button.secondary { background:transparent; color:var(--accent); border:1px solid var(--accent); }
          input { font:inherit; padding:.4rem .6rem; border:1px solid var(--line); border-radius:6px; background:var(--bg); color:var(--fg); flex:1; min-width:16rem; }
          details.adv { margin-top:.6rem; } details.adv > summary { color:var(--muted); font-size:.82rem; cursor:pointer; list-style:revert; }
          details.adv[open] > summary { margin-bottom:.2rem; }
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
