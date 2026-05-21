package ksl.app.swing.common.overridefield

import org.junit.jupiter.api.Test
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DoubleOverrideFieldTest {

    @Test
    fun `default state leaves field empty and surfaces default via tooltip`() {
        val field = onEdt { DoubleOverrideField(modelDefault = 500.0) }
        onEdt {
            assertNull(field.value)
            assertEquals("", field.internalTextField.text)
            val tip = field.internalTextField.toolTipText.orEmpty()
            assertEquals(true, tip.contains("500"), "got '$tip'")
            assertEquals(true, tip.startsWith("Default"), "got '$tip'")
        }
    }

    @Test
    fun `typing a valid double and pressing Enter commits the value`() {
        val seen = mutableListOf<Double?>()
        val field = onEdt { DoubleOverrideField(modelDefault = 500.0, onValueChange = { seen.add(it) }) }
        onEdt {
            field.internalTextField.text = "0.5"
            fireAction(field.internalTextField)
        }
        assertEquals(0.5, field.value)
        assertEquals(listOf<Double?>(0.5), seen)
    }

    @Test
    fun `blank text commits null`() {
        val field = onEdt { DoubleOverrideField(modelDefault = 500.0) }
        onEdt { field.value = 1.5 }
        onEdt {
            field.internalTextField.text = ""
            fireAction(field.internalTextField)
        }
        assertNull(field.value)
    }

    @Test
    fun `garbage text reverts and fires onParseError`() {
        var parseErrors = 0
        val field = onEdt {
            DoubleOverrideField(modelDefault = 1.0, onParseError = { parseErrors++ })
        }
        onEdt { field.value = 7.0 }
        onEdt {
            field.internalTextField.text = "xyz"
            fireAction(field.internalTextField)
        }
        assertEquals(7.0, field.value)
        assertEquals(1, parseErrors)
    }

    @Test
    fun `clear button resets to null`() {
        val field = onEdt { DoubleOverrideField(modelDefault = 1.0) }
        onEdt { field.value = 2.5 }
        onEdt { clearButton(field).doClick() }
        assertNull(field.value)
    }

    private fun fireAction(textField: javax.swing.JTextField) {
        val ev = java.awt.event.ActionEvent(
            textField, java.awt.event.ActionEvent.ACTION_PERFORMED, textField.text
        )
        for (listener in textField.actionListeners) listener.actionPerformed(ev)
    }

    private fun clearButton(field: DoubleOverrideField): javax.swing.JButton {
        for (c in field.components) if (c is javax.swing.JButton) return c
        error("clear button not found")
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
