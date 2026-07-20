package ksl.server.suite

import ksl.agent.config.AgentConfigurator
import ksl.service.admin.CapabilityStatus
import ksl.service.admin.SuiteStatus
import ksl.service.usage.UsageEvent
import ksl.service.usage.UsageLevel
import ksl.service.usage.UsageSummary
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminConsoleTest {

    private val status = SuiteStatus(
        version = "1.4.0",
        capabilities = listOf(
            CapabilityStatus("sim", enabled = true, ready = true, detail = "8 bundles", callCount = 5),
            CapabilityStatus("code", enabled = true, ready = false, detail = "index not found", callCount = 0),
        ),
        served = 5,
        lastActivityMillis = null,
    )
    private val usage = UsageSummary(total = 5, ok = 4, byTool = mapOf("run_model" to 5), byCapability = mapOf("sim" to 5))
    private val activity = listOf(UsageEvent("run_model", "sim", System.currentTimeMillis(), 120L, ok = true))
    private val connected = listOf(AgentConfigurator.ClientState("Claude Desktop", present = true, path = "/x/claude.json"))
    private val unconfigured = listOf(AgentConfigurator.ClientState("Claude Desktop", present = false, path = "/x/claude.json"))

    @Test
    @DisplayName("isLoopbackHost accepts loopback forms and rejects remote addresses")
    fun loopbackGuard() {
        assertTrue(AdminConsole.isLoopbackHost("127.0.0.1"))
        assertTrue(AdminConsole.isLoopbackHost("localhost"))
        assertTrue(AdminConsole.isLoopbackHost("::1"))
        assertTrue(AdminConsole.isLoopbackHost("0:0:0:0:0:0:0:1"))
        assertFalse(AdminConsole.isLoopbackHost("192.168.1.5"))
        assertFalse(AdminConsole.isLoopbackHost("example.com"))
    }

    @Test
    @DisplayName("renders the console regions with live data + the SSE hook")
    fun rendersConsole() {
        val html = AdminConsole.renderConsole(status, usage, activity, connected, loopback = true)
        assertTrue("KSL MCP Suite" in html && "1.4.0" in html)
        assertTrue("Capabilities" in html && "Clients" in html && "Activity" in html && "Diagnostics" in html)
        assertTrue("DEGRADED" in html, "code enabled but not ready => degraded")
        assertTrue("run_model" in html && "8 bundles" in html)
        assertTrue("Claude Desktop" in html && ">connected<" in html)
        assertTrue("/admin/events" in html)             // the live SSE hook
        assertTrue("Apply &amp; Restart" in html)        // loopback-only capability apply
        assertTrue("export.csv" in html)                 // usage export
    }

    @Test
    @DisplayName("Clients (setup) renders before Capabilities — the workflow order")
    fun clientsComeFirst() {
        val html = AdminConsole.renderConsole(status, usage, activity, connected, loopback = true)
        assertTrue(html.indexOf("<h2>Clients") < html.indexOf("<h2>Capabilities"))
    }

    @Test
    @DisplayName("no implementation leakage: no visible bridge-path field or standing body prose")
    fun noBridgeLeakage() {
        val html = AdminConsole.renderConsole(status, usage, activity, connected, loopback = true)
        // the old console showed a required "ksl-bridge command (path to the bridge launcher)" input and
        // a "Writes the one ksl-suite entry ..." paragraph — both are gone from the default view
        assertFalse("path to the bridge launcher" in html)
        assertFalse("Writes the one" in html)
        // explanations moved to button tooltips; the override lives behind an Advanced disclosure
        assertTrue("title=\"Removes the KSL entry" in html)
        assertTrue(">Advanced</summary>" in html)
    }

    @Test
    @DisplayName("when nothing is configured, Clients is emphasized with a one-click Connect and no bridge path")
    fun emphasizesClientsWhenUnconfigured() {
        val html = AdminConsole.renderConsole(status, usage, activity, unconfigured, loopback = true)
        assertTrue("Connect your assistant" in html)      // emphasized heading
        assertTrue("class=\"cta\"" in html)               // CTA styling on the section
        assertTrue("Connect</button>" in html)            // the one-click action
        assertTrue(">not connected<" in html)             // per-agent state
        assertTrue("title=\"Adds KSL to your assistant" in html)  // the what-does-this-do tooltip
        assertFalse("path to the bridge launcher" in html)
    }

    @Test
    @DisplayName("live-usage hooks, refresh, the Last-N-of-X label, the stale banner, and the restart reminder are present")
    fun liveAffordances() {
        val html = AdminConsole.renderConsole(status, usage, activity, connected, loopback = true)
        assertTrue("id=\"feedTitle\"" in html && "id=\"usageBars\"" in html) // client re-render targets
        assertTrue("id=\"refreshUsage\"" in html)                            // manual refresh button
        assertTrue("last 1 of 5" in html)                                    // "Last N of X" (1 shown, 5 total)
        assertTrue("id=\"stale\"" in html)                                   // stale-page banner element
        assertTrue("refresh" in html.lowercase())
        assertTrue("restart your assistant" in html)                         // connected restart reminder
    }

    @Test
    @DisplayName("the Usage study panel shows the opt-out levels + disclosure and reflects the active level")
    fun usageStudyPanel() {
        val html = AdminConsole.renderConsole(
            status, usage, activity, connected, loopback = true,
            usageLevel = UsageLevel.COUNTS, usageDir = "/x/usage",
        )
        assertTrue("Usage study" in html)
        assertTrue("data-level=\"off\"" in html && "data-level=\"counts\"" in html && "data-level=\"full\"" in html)
        assertTrue("lvl active\" data-level=\"counts\"" in html)  // active reflects the current level
        assertTrue("no search text" in html)                      // COUNTS disclosure
        assertTrue("usage.jsonl" in html)                         // the file location is disclosed
        assertTrue("/admin/config/usage" in html)                 // the opt-out endpoint the JS posts to
    }

    @Test
    @DisplayName("without loopback the machine-local controls are hidden but the console still renders")
    fun hidesLocalControlsWhenRemote() {
        val html = AdminConsole.renderConsole(status, usage, activity, connected, loopback = false)
        assertFalse("Apply &amp; Restart" in html)
        assertFalse("Disconnect</button>" in html)
        assertFalse("bridgeCmd" in html)                  // no client-setup controls at all when remote
        assertTrue("KSL MCP Suite" in html)
    }

    @Test
    @DisplayName("usageCsv emits a header and one row per event, quoting fields with commas")
    fun usageCsv() {
        val events = listOf(
            UsageEvent("run_model", "sim", 1000L, 120L, ok = true, client = "claude"),
            UsageEvent("search, code", "code", 2000L, 15L, ok = false),
        )
        val lines = AdminConsole.usageCsv(events).trim().lines()
        assertTrue(lines[0].startsWith("timestampMillis,capability,tool"))
        assertTrue(lines.size == 3)
        assertTrue(lines[1].contains("run_model") && lines[1].contains("claude"))
        assertTrue(lines[2].contains("\"search, code\""))
    }

    @Test
    @DisplayName("usageCsv includes the v2 columns; usageJsonl is one object per event; the panel offers the hand-off")
    fun exportAndHandoff() {
        val events = listOf(
            UsageEvent(
                tool = "search_code", capability = "code", timestampMillis = 1000L, durationMs = 12L, ok = true,
                client = "codex", query = "seize", resultCount = 3, topScore = 4.2,
            ),
        )
        assertTrue(AdminConsole.usageCsv(events).lines()[0].endsWith("query,paramsDigest,intent"))  // rich columns
        val jsonl = AdminConsole.usageJsonl(events)
        assertTrue(jsonl.lines().size == 1)
        assertTrue("\"query\":\"seize\"" in jsonl && "\"resultCount\":3" in jsonl && "\"client\":\"codex\"" in jsonl)

        val html = AdminConsole.renderConsole(status, usage, activity, connected, loopback = true)
        assertTrue("/admin/usage/export.jsonl" in html && "export.csv" in html && "id=\"revealUsage\"" in html)
        assertTrue("Hand this file to your instructor" in html)
    }
}
