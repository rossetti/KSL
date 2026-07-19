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
    private val clients = listOf(AgentConfigurator.ClientState("Claude Desktop", present = true, path = "/x/claude.json"))

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
    @DisplayName("renders the six console regions with live data + the SSE hook")
    fun rendersConsole() {
        val html = AdminConsole.renderConsole(status, usage, activity, clients, loopback = true)
        assertTrue("KSL MCP Suite" in html && "1.4.0" in html)
        assertTrue("Capabilities" in html && "Clients" in html && "Activity" in html)
        assertTrue("Diagnostics" in html && "Lifecycle" in html)
        assertTrue("DEGRADED" in html, "code enabled but not ready => degraded")
        assertTrue("run_model" in html && "8 bundles" in html)
        assertTrue("Claude Desktop" in html && "configured" in html)
        assertTrue("/admin/events" in html)             // the live SSE hook
        assertTrue("Apply &amp; Restart" in html)        // loopback-only capability apply
        assertTrue("export.csv" in html)                 // usage export
    }

    @Test
    @DisplayName("without loopback the machine-local controls are hidden but the console still renders")
    fun hidesLocalControlsWhenRemote() {
        val html = AdminConsole.renderConsole(status, usage, activity, clients, loopback = false)
        assertFalse("Apply &amp; Restart" in html)
        assertFalse("Configure</button>" in html)
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
