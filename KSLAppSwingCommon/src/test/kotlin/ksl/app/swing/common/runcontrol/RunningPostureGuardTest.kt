package ksl.app.swing.common.runcontrol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.Test
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunningPostureGuardTest {

    private fun makeAction(label: String = "x"): Action =
        object : AbstractAction(label) {
            override fun actionPerformed(e: ActionEvent) { /* no-op */ }
        }

    @Test
    fun `actions are disabled while running and re-enabled when running ends`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val running = MutableStateFlow(false)
            val guard = RunningPostureGuard(running, scope)
            val a = makeAction()
            val b = makeAction()
            onEdt { guard.register(a, b) }
            assertTrue(a.isEnabled)
            assertTrue(b.isEnabled)

            running.value = true
            onEdt { /* yield */ }
            assertFalse(a.isEnabled)
            assertFalse(b.isEnabled)

            running.value = false
            onEdt { /* yield */ }
            assertTrue(a.isEnabled)
            assertTrue(b.isEnabled)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `an action disabled before the run stays disabled after restoration`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val running = MutableStateFlow(false)
            val guard = RunningPostureGuard(running, scope)
            val a = makeAction().apply { isEnabled = false }
            onEdt { guard.register(a) }

            running.value = true
            onEdt { /* yield */ }
            assertFalse(a.isEnabled)

            running.value = false
            onEdt { /* yield */ }
            assertFalse(a.isEnabled, "action that was disabled before the run must remain disabled")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `an action registered during a running cycle is disabled immediately`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val running = MutableStateFlow(true)   // start in running posture
            val guard = RunningPostureGuard(running, scope)
            // Let the initial collection emit propagate.
            onEdt { /* yield */ }
            val a = makeAction()
            onEdt { guard.register(a) }
            assertFalse(a.isEnabled, "registration during running should disable immediately")

            running.value = false
            onEdt { /* yield */ }
            assertTrue(a.isEnabled, "should restore to the captured pre-running state")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `unregisterAll forgets every action without touching their state`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val running = MutableStateFlow(false)
            val guard = RunningPostureGuard(running, scope)
            val a = makeAction()
            onEdt { guard.register(a) }
            assertEquals(1, guard.registeredCountForTest())
            guard.unregisterAll()
            assertEquals(0, guard.registeredCountForTest())

            running.value = true
            onEdt { /* yield */ }
            assertTrue(a.isEnabled, "guard should no longer drive a's enabled state after unregisterAll")
        } finally {
            scope.cancel()
        }
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
