package ksl.app.swing.common.overridefield

import org.junit.jupiter.api.Test
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BooleanTriStateOverrideFieldTest {

    @Test
    fun `default state has Default selected`() {
        val field = onEdt { BooleanTriStateOverrideField() }
        onEdt {
            assertNull(field.value)
            val (def, yes, no) = field.buttons()
            assertTrue(def.isSelected)
            assertEquals(false, yes.isSelected)
            assertEquals(false, no.isSelected)
        }
    }

    @Test
    fun `clicking Yes commits true`() {
        val seen = mutableListOf<Boolean?>()
        val field = onEdt { BooleanTriStateOverrideField(onValueChange = { seen.add(it) }) }
        onEdt { field.buttons().second.doClick() }
        assertEquals(true, field.value)
        assertEquals(listOf<Boolean?>(true), seen)
    }

    @Test
    fun `clicking No commits false`() {
        val seen = mutableListOf<Boolean?>()
        val field = onEdt { BooleanTriStateOverrideField(onValueChange = { seen.add(it) }) }
        onEdt { field.buttons().third.doClick() }
        assertEquals(false, field.value)
        assertEquals(listOf<Boolean?>(false), seen)
    }

    @Test
    fun `clicking Default after override commits null`() {
        val seen = mutableListOf<Boolean?>()
        val field = onEdt { BooleanTriStateOverrideField(onValueChange = { seen.add(it) }) }
        onEdt { field.buttons().second.doClick() }   // Yes
        seen.clear()
        onEdt { field.buttons().first.doClick() }    // Default
        assertNull(field.value)
        assertEquals(listOf<Boolean?>(null), seen)
    }

    @Test
    fun `programmatic value set selects the matching button and fires callback`() {
        val seen = mutableListOf<Boolean?>()
        val field = onEdt { BooleanTriStateOverrideField(onValueChange = { seen.add(it) }) }
        onEdt { field.value = true }
        onEdt {
            val (def, yes, no) = field.buttons()
            assertEquals(false, def.isSelected)
            assertTrue(yes.isSelected)
            assertEquals(false, no.isSelected)
        }
        assertEquals(listOf<Boolean?>(true), seen)
    }

    @Test
    fun `setting value to current state does not fire callback`() {
        val seen = mutableListOf<Boolean?>()
        val field = onEdt { BooleanTriStateOverrideField(onValueChange = { seen.add(it) }) }
        onEdt { field.value = null }
        assertEquals(emptyList(), seen)
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
