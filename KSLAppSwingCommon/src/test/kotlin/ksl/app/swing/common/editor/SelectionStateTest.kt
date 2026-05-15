package ksl.app.swing.common.editor

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelectionStateTest {

    private val items = listOf("a", "b", "c", "d", "e")

    @Test
    fun `initial state is empty with no anchor`() {
        val sel = SelectionState<String>()
        assertEquals(emptyList(), sel.selection.value)
        assertNull(sel.anchor)
    }

    @Test
    fun `select replaces selection and sets anchor`() {
        val sel = SelectionState<String>()
        sel.select("a")
        sel.select("c")
        assertEquals(listOf("c"), sel.selection.value)
        assertEquals("c", sel.anchor)
    }

    @Test
    fun `toggle adds and removes`() {
        val sel = SelectionState<String>()
        sel.toggle("a")
        sel.toggle("b")
        assertEquals(listOf("a", "b"), sel.selection.value)
        sel.toggle("a")
        assertEquals(listOf("b"), sel.selection.value)
        assertEquals("b", sel.anchor)
    }

    @Test
    fun `extend selects the contiguous range from anchor to target`() {
        val sel = SelectionState<String>()
        sel.select("b")
        sel.extend("d", items)
        assertEquals(listOf("b", "c", "d"), sel.selection.value)
        assertEquals("b", sel.anchor, "anchor should remain after extend")
    }

    @Test
    fun `extend in the reverse direction still produces the contiguous range`() {
        val sel = SelectionState<String>()
        sel.select("d")
        sel.extend("a", items)
        assertEquals(listOf("a", "b", "c", "d"), sel.selection.value)
    }

    @Test
    fun `extend with no anchor falls back to select`() {
        val sel = SelectionState<String>()
        sel.extend("c", items)
        assertEquals(listOf("c"), sel.selection.value)
        assertEquals("c", sel.anchor)
    }

    @Test
    fun `extend with target outside the ordered list falls back to select`() {
        val sel = SelectionState<String>()
        sel.select("b")
        sel.extend("z", items)
        assertEquals(listOf("z"), sel.selection.value)
        assertEquals("z", sel.anchor)
    }

    @Test
    fun `clear empties selection and anchor`() {
        val sel = SelectionState<String>()
        sel.select("a")
        sel.toggle("b")
        sel.clear()
        assertEquals(emptyList(), sel.selection.value)
        assertNull(sel.anchor)
    }

    @Test
    fun `set replaces selection wholesale and anchors on the last item`() {
        val sel = SelectionState<String>()
        sel.set(listOf("a", "b", "c"))
        assertEquals(listOf("a", "b", "c"), sel.selection.value)
        assertEquals("c", sel.anchor)
        sel.set(emptyList())
        assertNull(sel.anchor)
    }

    @Test
    fun `pruneRemoved drops items and re-anchors when the anchor is removed`() {
        val sel = SelectionState<String>()
        sel.set(listOf("a", "b", "c"))   // anchor = c
        sel.pruneRemoved(setOf("c"))
        assertEquals(listOf("a", "b"), sel.selection.value)
        assertEquals("b", sel.anchor)
    }

    @Test
    fun `pruneRemoved with no overlap leaves selection alone but may re-anchor`() {
        val sel = SelectionState<String>()
        sel.set(listOf("a", "b"))        // anchor = b
        sel.pruneRemoved(setOf("z"))     // unrelated
        assertEquals(listOf("a", "b"), sel.selection.value)
        assertEquals("b", sel.anchor)
    }

    @Test
    fun `isSelected reports membership correctly`() {
        val sel = SelectionState<String>()
        sel.select("a")
        assertTrue(sel.isSelected("a"))
        assertFalse(sel.isSelected("b"))
    }
}
