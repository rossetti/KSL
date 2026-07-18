package ksl.app.servers

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class ServerManagerControllerTest {

    @Test
    fun statusReportRunsAndCoversSuiteAndProcesses() {
        val report = ServerManagerController("Test").statusReport()
        assertTrue("Suite:" in report)
        assertTrue("KSL MCP processes" in report)
    }

    @Test
    fun configureWithoutBridgeCommandReportsClearly() {
        val c = ServerManagerController("Test", bridgeCommand = null)
        assertTrue("Cannot configure" in c.configureClients())
    }

    @Test
    fun startWithoutJarReportsClearly() {
        // No suite jar set and (assumed) none already running on the default port.
        val c = ServerManagerController("Test", suiteJar = null)
        val msg = c.startSuite()
        assertTrue("Cannot start" in msg || "already running" in msg)
    }
}
