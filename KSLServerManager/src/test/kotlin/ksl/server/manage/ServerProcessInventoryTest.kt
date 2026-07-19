package ksl.server.manage

import ksl.server.manage.ServerProcessInventory.Health
import ksl.server.manage.ServerProcessInventory.Kind
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerProcessInventoryTest {

    @Test
    @DisplayName("classify maps KSL command lines to kinds, most-specific first")
    fun classifyMapsCommandLines() {
        assertEquals(Kind.SUITE, ServerProcessInventory.classify("java -jar /opt/ksl/ksl-suite-mcp.jar"))
        assertEquals(Kind.BRIDGE, ServerProcessInventory.classify("java -jar ksl-bridge.jar --url http://127.0.0.1:3001/"))
        assertEquals(Kind.CODE, ServerProcessInventory.classify("java -jar ksl-code-mcp.jar"))
        assertEquals(Kind.BOOK, ServerProcessInventory.classify("java -jar ksl-book-mcp.jar"))
        assertEquals(Kind.MODEL, ServerProcessInventory.classify("java -cp x ksl.server.mcp.LauncherKt --stdio"))
        assertNull(ServerProcessInventory.classify("java -jar unrelated-app.jar"))
    }

    @Test
    @DisplayName("health of a dead port is DOWN")
    fun healthOfDeadPortIsDown() {
        assertEquals(Health.DOWN, ServerProcessInventory.health("http://127.0.0.1:59997/health"))
    }

    @Test
    @DisplayName("findKslProcesses enumerates live processes without throwing")
    fun findKslProcessesSmoke() {
        ServerProcessInventory.findKslProcesses() // must not throw; content is environment-dependent
    }
}
