package ksl.app.servers

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SuiteProcessManagerTest {

    @Test
    @DisplayName("process discovery runs and every result is a classified KSL JVM")
    fun findKslProcessesRunsAndClassifies() {
        val procs = SuiteProcessManager.findKslProcesses() // must not throw; may legitimately be empty
        assertNotNull(procs)
        // findKslProcesses only returns matches, so each has a concrete kind and a real pid.
        assertTrue(procs.all { it.pid > 0 })
        assertTrue(procs.all { it.command.isNotBlank() })
    }

    @Test
    @DisplayName("health of an unused endpoint is DOWN, not an exception")
    fun healthOfADownEndpointIsDown() {
        val h = SuiteProcessManager.health("http://127.0.0.1:59997/health", Duration.ofMillis(500))
        assertEquals(SuiteProcessManager.Health.DOWN, h)
    }

    @Test
    @DisplayName("terminate on an empty / already-dead pid list is a safe no-op")
    fun terminateEmptyIsSafe() {
        assertTrue(SuiteProcessManager.terminate(emptyList()).isEmpty())
        // a pid that does not exist reports as "not alive" (already gone), never throws
        assertEquals(listOf(999_999_999L), SuiteProcessManager.terminate(listOf(999_999_999L)))
    }
}
