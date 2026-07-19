package ksl.server.manage

import kotlinx.coroutines.runBlocking
import ksl.agent.config.AGENT_CONFIG_HOME_PROPERTY
import ksl.server.manage.ServerProcessInventory.Health
import ksl.service.admin.ServerAdminOperations
import ksl.service.admin.SuiteStatus
import ksl.service.usage.UsageEvent
import ksl.service.usage.UsageSummary
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerManagerControllerTest {

    // An admin surface that must never be reached (health is DOWN, so refresh won't call it).
    private object UnreachableAdmin : ServerAdminOperations {
        override fun status(): SuiteStatus = error("suite is down")
        override fun usageSummary(): UsageSummary = error("suite is down")
        override fun recentActivity(limit: Int): List<UsageEvent> = error("suite is down")
    }

    @AfterEach
    fun clearRedirect() {
        System.clearProperty(AGENT_CONFIG_HOME_PROPERTY)
    }

    @Test
    @DisplayName("refresh reflects a down suite as DOWN + null status, not an error")
    fun refreshDownSuite() {
        val controller = ServerManagerController(
            adminOps = UnreachableAdmin,
            healthUrl = "http://127.0.0.1:59998/health",
        )
        try {
            runBlocking { controller.refresh() }
            assertEquals(Health.DOWN, controller.health.value)
            assertNull(controller.status.value)
        } finally {
            controller.close()
        }
    }

    @Test
    @DisplayName("configureClient then removeClient round-trips the clients state under the sandbox")
    fun configureRemoveClientRoundTrip(@TempDir tmp: Path) {
        System.setProperty(AGENT_CONFIG_HOME_PROPERTY, tmp.toString())
        File(tmp.toFile(), "Claude").mkdirs()
        val controller = ServerManagerController(adminOps = UnreachableAdmin, healthUrl = "http://127.0.0.1:59998/health")
        try {
            val configured = controller.configureClient("ksl-bridge", "http://127.0.0.1:3001/")
            assertEquals(1, configured.size)

            runBlocking { controller.refresh() }
            assertTrue(controller.clients.value.single { it.agent == "Claude Desktop" }.present)

            controller.removeClient()
            runBlocking { controller.refresh() }
            assertFalse(controller.clients.value.single { it.agent == "Claude Desktop" }.present)
        } finally {
            controller.close()
        }
    }
}
