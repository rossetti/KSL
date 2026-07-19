package ksl.server.manage

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import ksl.service.admin.CapabilityStatus
import ksl.service.admin.SuiteStatus
import ksl.service.usage.UsageEvent
import ksl.service.usage.UsageSummary
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the HTTP admin client parses exactly the DTOs the suite serves, by standing up a canned
 * server that emits the same @Serializable payloads over /status, /admin/usage, /admin/activity.
 */
class HttpAdminOperationsTest {

    private val json = Json { encodeDefaults = true }

    private fun respond(ex: HttpExchange, body: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    @Test
    @DisplayName("reads and parses status, usage, and recent activity from a live /admin surface")
    fun parsesAdminSurface() {
        val status = SuiteStatus(
            version = "1.4.0",
            capabilities = listOf(CapabilityStatus("code", enabled = true, ready = true, detail = "index: 4041 declarations", callCount = 5)),
            served = 5,
            lastActivityMillis = 123L,
        )
        val usage = UsageSummary(total = 5, ok = 4, byTool = mapOf("search_code" to 5), byCapability = mapOf("code" to 5), lastActivityMillis = 123L)
        val activity = listOf(UsageEvent("search_code", "code", 123L, 42L, ok = true))

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/status") { respond(it, json.encodeToString(SuiteStatus.serializer(), status)) }
        server.createContext("/admin/usage") { respond(it, json.encodeToString(UsageSummary.serializer(), usage)) }
        server.createContext("/admin/activity") { respond(it, json.encodeToString(ListSerializer(UsageEvent.serializer()), activity)) }
        server.start()
        try {
            val ops = HttpAdminOperations("http://127.0.0.1:${server.address.port}")

            val s = ops.status()
            assertEquals("1.4.0", s.version)
            assertEquals(5, s.served)
            assertEquals("code", s.capabilities.single().id)
            assertTrue(s.capabilities.single().ready)

            val u = ops.usageSummary()
            assertEquals(5, u.total)
            assertEquals(5, u.byTool["search_code"])

            val a = ops.recentActivity(10)
            assertEquals(1, a.size)
            assertEquals("search_code", a.single().tool)
        } finally {
            server.stop(0)
        }
    }
}
