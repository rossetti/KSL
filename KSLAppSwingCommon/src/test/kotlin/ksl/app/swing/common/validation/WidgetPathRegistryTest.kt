package ksl.app.swing.common.validation

import org.junit.jupiter.api.Test
import javax.swing.JLabel
import javax.swing.JTextField
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WidgetPathRegistryTest {

    @Test fun `register exposes the widget at its path`() {
        val reg = WidgetPathRegistry()
        val w = JTextField()
        reg.register("scenarios[0].x", w)
        assertEquals(listOf(w), reg.findAt("scenarios[0].x"))
        assertEquals(w, reg.findOne("scenarios[0].x"))
    }

    @Test fun `register is idempotent for the same pair`() {
        val reg = WidgetPathRegistry()
        val w = JTextField()
        reg.register("a", w)
        reg.register("a", w)
        assertEquals(1, reg.findAt("a").size)
    }

    @Test fun `multiple widgets at the same path are all returned`() {
        val reg = WidgetPathRegistry()
        val a = JTextField()
        val b = JTextField()
        reg.register("p", a)
        reg.register("p", b)
        assertEquals(listOf(a, b), reg.findAt("p"))
        assertEquals(b, reg.findOne("p"), "findOne returns the most-recently-registered")
    }

    @Test fun `unregister removes the widget from every path entry`() {
        val reg = WidgetPathRegistry()
        val w = JTextField()
        reg.register("a", w)
        reg.register("b", w)
        reg.unregister(w)
        assertTrue(reg.findAt("a").isEmpty())
        assertTrue(reg.findAt("b").isEmpty())
        assertEquals(emptyList(), reg.paths(), "empty entries should be dropped")
    }

    @Test fun `clear removes every entry`() {
        val reg = WidgetPathRegistry()
        reg.register("a", JTextField())
        reg.register("b", JLabel())
        reg.clear()
        assertEquals(emptyList(), reg.paths())
    }

    @Test fun `findAtOrBelow groups by exact path`() {
        val reg = WidgetPathRegistry()
        val a = JTextField()
        val b = JTextField()
        val c = JLabel()
        reg.register("scenarios[0]", a)
        reg.register("scenarios[0].x", b)
        reg.register("scenarios[1].y", c)
        val found = reg.findAtOrBelow("scenarios[0]")
        assertEquals(setOf("scenarios[0]", "scenarios[0].x"), found.keys)
        assertEquals(listOf(a), found["scenarios[0]"])
        assertEquals(listOf(b), found["scenarios[0].x"])
    }

    @Test fun `findOne returns null for unknown path`() {
        val reg = WidgetPathRegistry()
        assertNull(reg.findOne("unknown"))
    }
}
