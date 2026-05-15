package ksl.app.swing.common.overridefield

import org.junit.jupiter.api.Test
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StringControlValueFieldTest {

    @Test
    fun `default state renders the placeholder text-field mode`() {
        val field = onEdt { StringControlValueField(modelDefault = "ROUND_ROBIN") }
        onEdt {
            assertFalse(field.isComboBoxForTest())
            assertNull(field.value)
            assertTrue(field.textComponentForTest().text.contains("ROUND_ROBIN"))
            assertTrue(field.textComponentForTest().text.contains("model default"))
        }
    }

    @Test
    fun `allowedValues triggers combo-box mode and populates items`() {
        val field = onEdt {
            StringControlValueField(modelDefault = "A", allowedValues = listOf("A", "B", "C"))
        }
        onEdt {
            assertTrue(field.isComboBoxForTest())
            assertEquals(listOf("A", "B", "C"), field.comboItemsForTest())
        }
    }

    @Test
    fun `typing a value and pressing Enter commits the value`() {
        val seen = mutableListOf<String?>()
        val field = onEdt { StringControlValueField(modelDefault = "X", onValueChange = { seen.add(it) }) }
        onEdt {
            field.textComponentForTest().text = "hello"
            fireAction(field.textComponentForTest())
        }
        assertEquals("hello", field.value)
        assertEquals(listOf<String?>("hello"), seen)
    }

    @Test
    fun `blank text commits null`() {
        val seen = mutableListOf<String?>()
        val field = onEdt { StringControlValueField(modelDefault = "X", onValueChange = { seen.add(it) }) }
        onEdt { field.value = "first" }
        seen.clear()
        onEdt {
            field.textComponentForTest().text = ""
            fireAction(field.textComponentForTest())
        }
        assertNull(field.value)
        assertEquals(listOf<String?>(null), seen)
    }

    @Test
    fun `clear button resets value to null`() {
        val field = onEdt { StringControlValueField(modelDefault = "X") }
        onEdt { field.value = "hello" }
        onEdt { clearButton(field).doClick() }
        assertNull(field.value)
    }

    @Test
    fun `out-of-list value is accepted in combo-box mode (no internal validation)`() {
        val seen = mutableListOf<String?>()
        val field = onEdt {
            StringControlValueField(
                modelDefault = "A",
                allowedValues = listOf("A", "B", "C"),
                onValueChange = { seen.add(it) }
            )
        }
        onEdt {
            field.textComponentForTest().text = "not_in_list"
            fireAction(field.textComponentForTest())
        }
        assertEquals("not_in_list", field.value, "widget should accept the value; validation surfaces externally")
    }

    private fun clearButton(field: StringControlValueField): javax.swing.JButton {
        for (c in field.components) if (c is javax.swing.JButton) return c
        error("clear button not found")
    }

    private fun fireAction(textField: javax.swing.JTextField) {
        val ev = java.awt.event.ActionEvent(textField, java.awt.event.ActionEvent.ACTION_PERFORMED, textField.text)
        for (listener in textField.actionListeners) listener.actionPerformed(ev)
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
