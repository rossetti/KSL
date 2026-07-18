package ksl.server.suite

import ksl.service.admin.CapabilityStatus
import ksl.service.admin.SuiteStatus
import ksl.service.usage.UsageSummary
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminConsoleTest {

    @Test
    @DisplayName("isLoopbackHost accepts loopback forms and rejects remote addresses")
    fun loopbackGuard() {
        assertTrue(AdminConsole.isLoopbackHost("127.0.0.1"))
        assertTrue(AdminConsole.isLoopbackHost("localhost"))
        assertTrue(AdminConsole.isLoopbackHost("::1"))
        assertTrue(AdminConsole.isLoopbackHost("0:0:0:0:0:0:0:1"))
        assertFalse(AdminConsole.isLoopbackHost("192.168.1.5"))
        assertFalse(AdminConsole.isLoopbackHost("10.0.0.3"))
        assertFalse(AdminConsole.isLoopbackHost("example.com"))
    }

    @Test
    @DisplayName("renders a status page with capability rows and top tools")
    fun rendersStatusPage() {
        val status = SuiteStatus(
            version = "1.4.0",
            capabilities = listOf(
                CapabilityStatus("sim", enabled = true, ready = true, detail = "8 bundles", callCount = 5),
                CapabilityStatus("code", enabled = false, ready = false, detail = "disabled", callCount = 0),
            ),
            served = 5,
            lastActivityMillis = null,
        )
        val usage = UsageSummary(total = 5, ok = 4, byTool = mapOf("run_model" to 5), byCapability = mapOf("sim" to 5))
        val html = AdminConsole.renderStatusHtml(status, usage)
        assertTrue("KSL MCP Suite" in html)
        assertTrue("1.4.0" in html)
        assertTrue("run_model" in html)
        assertTrue("sim" in html && "8 bundles" in html)
        assertTrue("/admin/events" in html) // the SSE hook the page subscribes to
    }
}
