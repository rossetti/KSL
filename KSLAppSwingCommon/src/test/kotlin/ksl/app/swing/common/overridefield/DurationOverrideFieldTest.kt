package ksl.app.swing.common.overridefield

import org.junit.jupiter.api.Test
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class DurationOverrideFieldTest {

    @Test
    fun `default state renders the placeholder using kotlin duration form`() {
        val field = onEdt { DurationOverrideField(modelDefault = 30.seconds) }
        onEdt {
            assertNull(field.value)
            assertEquals(true, field.internalTextField.text.contains("30"))
            assertEquals(true, field.internalTextField.text.contains("model default"))
        }
    }

    @Test
    fun `kotlin extended form is accepted`() {
        val seen = mutableListOf<Duration?>()
        val field = onEdt { DurationOverrideField(modelDefault = null, onValueChange = { seen.add(it) }) }
        onEdt {
            field.internalTextField.text = "5m"
            fireAction(field.internalTextField)
        }
        assertEquals(Duration.parse("5m"), field.value)
        assertEquals(listOf<Duration?>(Duration.parse("5m")), seen)
    }

    @Test
    fun `ISO-8601 form is accepted`() {
        val field = onEdt { DurationOverrideField(modelDefault = null) }
        onEdt {
            field.internalTextField.text = "PT30S"
            fireAction(field.internalTextField)
        }
        assertEquals(30.seconds, field.value)
    }

    @Test
    fun `blank text commits null`() {
        val field = onEdt { DurationOverrideField(modelDefault = 1.seconds) }
        onEdt { field.value = 5.seconds }
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
            DurationOverrideField(modelDefault = null, onParseError = { parseErrors++ })
        }
        onEdt { field.value = 10.seconds }
        onEdt {
            field.internalTextField.text = "not a duration"
            fireAction(field.internalTextField)
        }
        assertEquals(10.seconds, field.value)
        assertEquals(1, parseErrors)
    }

    private fun fireAction(textField: javax.swing.JTextField) {
        val ev = java.awt.event.ActionEvent(
            textField, java.awt.event.ActionEvent.ACTION_PERFORMED, textField.text
        )
        for (listener in textField.actionListeners) listener.actionPerformed(ev)
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
