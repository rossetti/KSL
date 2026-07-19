package ksl.server.suite

import ksl.agent.config.AgentConfigurator
import ksl.service.admin.CapabilityStatus
import ksl.service.admin.SuiteStatus
import ksl.service.usage.UsageEvent
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
}
