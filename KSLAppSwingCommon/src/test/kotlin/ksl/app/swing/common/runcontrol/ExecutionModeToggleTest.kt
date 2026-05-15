package ksl.app.swing.common.runcontrol

import ksl.app.config.ExecutionMode
import org.junit.jupiter.api.Test
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExecutionModeToggleTest {

    @Test
    fun `default initial mode selects the Sequential button`() {
        val toggle = onEdt { ExecutionModeToggle() }
        onEdt {
            assertEquals(ExecutionMode.SEQUENTIAL, toggle.mode)
            val (seq, conc) = toggle.buttonsForTest()
            assertTrue(seq.isSelected)
            assertFalse(conc.isSelected)
        }
    }

    @Test
    fun `initialMode set to CONCURRENT selects the Concurrent button`() {
        val toggle = onEdt { ExecutionModeToggle(initialMode = ExecutionMode.CONCURRENT) }
        onEdt {
            assertEquals(ExecutionMode.CONCURRENT, toggle.mode)
            val (seq, conc) = toggle.buttonsForTest()
            assertFalse(seq.isSelected)
            assertTrue(conc.isSelected)
        }
    }

    @Test
    fun `clicking Concurrent commits CONCURRENT and fires the callback`() {
        val seen = mutableListOf<ExecutionMode>()
        val toggle = onEdt { ExecutionModeToggle(onValueChange = { seen.add(it) }) }
        onEdt { toggle.buttonsForTest().second.doClick() }
        assertEquals(ExecutionMode.CONCURRENT, toggle.mode)
        assertEquals(listOf(ExecutionMode.CONCURRENT), seen)
    }

    @Test
    fun `clicking Sequential after Concurrent commits SEQUENTIAL`() {
        val seen = mutableListOf<ExecutionMode>()
        val toggle = onEdt {
            ExecutionModeToggle(initialMode = ExecutionMode.CONCURRENT, onValueChange = { seen.add(it) })
        }
        onEdt { toggle.buttonsForTest().first.doClick() }
        assertEquals(ExecutionMode.SEQUENTIAL, toggle.mode)
        assertEquals(listOf(ExecutionMode.SEQUENTIAL), seen)
    }

    @Test
    fun `programmatic mode setter selects the matching button and fires callback`() {
        val seen = mutableListOf<ExecutionMode>()
        val toggle = onEdt { ExecutionModeToggle(onValueChange = { seen.add(it) }) }
        onEdt { toggle.mode = ExecutionMode.CONCURRENT }
        onEdt {
            val (seq, conc) = toggle.buttonsForTest()
            assertFalse(seq.isSelected)
            assertTrue(conc.isSelected)
        }
        assertEquals(listOf(ExecutionMode.CONCURRENT), seen)
    }

    @Test
    fun `setting mode to the current value is a no-op`() {
        val seen = mutableListOf<ExecutionMode>()
        val toggle = onEdt { ExecutionModeToggle(onValueChange = { seen.add(it) }) }
        onEdt { toggle.mode = ExecutionMode.SEQUENTIAL }
        assertEquals(emptyList(), seen, "setting the current value should not fire the callback")
    }

    @Test
    fun `setEnabled propagates to both buttons`() {
        val toggle = onEdt { ExecutionModeToggle() }
        onEdt { toggle.isEnabled = false }
        onEdt {
            val (seq, conc) = toggle.buttonsForTest()
            assertFalse(seq.isEnabled)
            assertFalse(conc.isEnabled)
        }
        onEdt { toggle.isEnabled = true }
        onEdt {
            val (seq, conc) = toggle.buttonsForTest()
            assertTrue(seq.isEnabled)
            assertTrue(conc.isEnabled)
        }
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
