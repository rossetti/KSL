package ksl.app.swing.common.overridefield

import org.junit.jupiter.api.Test
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntegerOverrideFieldTest {

    @Test
    fun `default state leaves field empty, hides clear button, surfaces default via tooltip`() {
        val field = onEdt { IntegerOverrideField(modelDefault = 30) }
        onEdt {
            assertNull(field.value)
            assertEquals("", field.internalTextField.text)
            assertFalse(clearButton(field).isVisible)
            // Tooltip on the text field discloses the default value.
            val tip = field.internalTextField.toolTipText.orEmpty()
            assertTrue(tip.contains("30"), "field tooltip should surface the default; got '$tip'")
            assertTrue(tip.startsWith("Default"), "field tooltip should say 'Default: …'; got '$tip'")
        }
    }

    @Test
    fun `null modelDefault leaves field empty and tooltip says unavailable`() {
        val field = onEdt { IntegerOverrideField(modelDefault = null) }
        onEdt {
            assertEquals("", field.internalTextField.text)
            val tip = field.internalTextField.toolTipText.orEmpty()
            assertTrue(tip.contains("unavailable", ignoreCase = true), "got '$tip'")
        }
    }

    @Test
    fun `clear button tooltip references the model default`() {
        val field = onEdt { IntegerOverrideField(modelDefault = 30) }
        onEdt {
            // Tooltip is set on the button regardless of visibility,
            // so the helper-driven assertion is reliable even with
            // the button hidden in default state.
            val tip = clearButton(field).toolTipText.orEmpty()
            assertTrue(tip.contains("30"), "got '$tip'")
            assertTrue(tip.startsWith("Reset to default"), "got '$tip'")
        }
    }

    @Test
    fun `programmatic value set fires onValueChange once and shows the clear button`() {
        val seen = mutableListOf<Int?>()
        val field = onEdt { IntegerOverrideField(modelDefault = 30, onValueChange = { seen.add(it) }) }
        onEdt { field.value = 42 }
        onEdt {
            assertEquals(42, field.value)
            assertEquals("42", field.internalTextField.text)
            assertTrue(clearButton(field).isVisible)
        }
        assertEquals(listOf<Int?>(42), seen)
    }

    @Test
    fun `typing a valid integer and pressing Enter commits the value`() {
        val seen = mutableListOf<Int?>()
        val field = onEdt { IntegerOverrideField(modelDefault = 30, onValueChange = { seen.add(it) }) }
        onEdt {
            field.internalTextField.text = "100"
            fireAction(field.internalTextField)  // fires the ActionListener -> commitFromText
        }
        assertEquals(100, field.value)
        assertEquals(listOf<Int?>(100), seen)
    }

    @Test
    fun `blank text commits null`() {
        val seen = mutableListOf<Int?>()
        val field = onEdt { IntegerOverrideField(modelDefault = 30, onValueChange = { seen.add(it) }) }
        onEdt { field.value = 50 }
        seen.clear()
        onEdt {
            field.internalTextField.text = ""
            fireAction(field.internalTextField)
        }
        assertNull(field.value)
        assertEquals(listOf<Int?>(null), seen)
    }

    @Test
    fun `garbage text reverts and fires onParseError`() {
        var parseErrors = 0
        val field = onEdt {
            IntegerOverrideField(modelDefault = 30, onParseError = { parseErrors++ })
        }
        onEdt { field.value = 50 }
        onEdt {
            field.internalTextField.text = "not a number"
            fireAction(field.internalTextField)
        }
        assertEquals(50, field.value, "value should revert to the previous committed override")
        assertEquals(1, parseErrors)
    }

    @Test
    fun `clear button resets value to null and fires the callback`() {
        val seen = mutableListOf<Int?>()
        val field = onEdt { IntegerOverrideField(modelDefault = 30, onValueChange = { seen.add(it) }) }
        onEdt { field.value = 100 }
        seen.clear()
        onEdt { clearButton(field).doClick() }
        assertNull(field.value)
        assertEquals(listOf<Int?>(null), seen)
    }

    private fun fireAction(textField: javax.swing.JTextField) {
        val ev = java.awt.event.ActionEvent(
            textField, java.awt.event.ActionEvent.ACTION_PERFORMED, textField.text
        )
        for (listener in textField.actionListeners) listener.actionPerformed(ev)
    }

    private fun clearButton(field: IntegerOverrideField): javax.swing.JButton {
        for (c in field.components) if (c is javax.swing.JButton) return c
        error("clear button not found")
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
