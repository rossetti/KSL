package ksl.app.swing.common.overridefield

import org.junit.jupiter.api.Test
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonControlValueFieldTest {

    @Test
    fun `default state renders the placeholder and disables Format`() {
        val field = onEdt { JsonControlValueField(modelDefault = "{}") }
        onEdt {
            assertNull(field.value)
            assertTrue(field.internalTextAreaForTest.text.contains("{}"))
            assertTrue(field.internalTextAreaForTest.text.contains("model default"))
            assertFalse(field.isFormatEnabledForTest)
        }
    }

    @Test
    fun `programmatic value enables Format and shows the text`() {
        val field = onEdt { JsonControlValueField(modelDefault = "{}") }
        onEdt { field.value = """{"x":1}""" }
        onEdt {
            assertEquals("""{"x":1}""", field.internalTextAreaForTest.text)
            assertTrue(field.isFormatEnabledForTest)
        }
    }

    @Test
    fun `Format reformats valid JSON to pretty form`() {
        val field = onEdt { JsonControlValueField(modelDefault = null) }
        onEdt {
            field.value = """{"a":1,"b":[2,3]}"""
        }
        onEdt { field.simulateFormat() }
        onEdt {
            val pretty = field.value
            assertNotNull(pretty)
            assertTrue(pretty!!.contains("\n"), "pretty form should include newlines: <$pretty>")
            assertTrue(pretty.contains("\"a\""))
            assertTrue(pretty.contains("\"b\""))
        }
    }

    @Test
    fun `Format on invalid JSON fires onParseError and leaves text unchanged`() {
        var parseErrors = 0
        val field = onEdt {
            JsonControlValueField(modelDefault = null, onParseError = { parseErrors++ })
        }
        onEdt {
            field.internalTextAreaForTest.text = "{not valid"
            field.internalTextAreaForTest.transferFocusBackward()  // trigger focus-lost → commit
        }
        // Commit may also happen on Format invocation itself; either way we need
        // the value populated for the format call.  Set programmatically to be sure.
        onEdt { field.value = "{not valid" }
        onEdt { field.simulateFormat() }
        assertEquals(1, parseErrors)
        assertEquals("{not valid", field.value, "text must be unchanged on parse failure")
    }

    @Test
    fun `Clear button resets to null and disables Format`() {
        val field = onEdt { JsonControlValueField(modelDefault = "{}") }
        onEdt { field.value = """{"x":1}""" }
        onEdt { field.simulateClear() }
        onEdt {
            assertNull(field.value)
            assertFalse(field.isFormatEnabledForTest)
        }
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
