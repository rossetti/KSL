package ksl.utilities.editor

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UndoStackTest {

    private fun op(
        label: String,
        undoCounter: IntArray = IntArray(1),
        redoCounter: IntArray = IntArray(1)
    ): UndoableOperation = UndoableOperation(
        description = label,
        undo = { undoCounter[0]++ },
        redo = { redoCounter[0]++ }
    )

    @Test
    fun `initial state is empty and cannot undo or redo`() {
        val stack = UndoStack()
        assertFalse(stack.state.value.canUndo)
        assertFalse(stack.state.value.canRedo)
        assertNull(stack.state.value.undoDescription)
        assertNull(stack.state.value.redoDescription)
        assertFalse(stack.undo())
        assertFalse(stack.redo())
    }

    @Test
    fun `push records the operation and exposes undo description`() {
        val stack = UndoStack()
        stack.push(op("Add Scenario"))
        assertTrue(stack.state.value.canUndo)
        assertFalse(stack.state.value.canRedo)
        assertEquals("Add Scenario", stack.state.value.undoDescription)
    }

    @Test
    fun `undo invokes the operation's undo callback`() {
        val undoCalls = IntArray(1)
        val redoCalls = IntArray(1)
        val stack = UndoStack()
        stack.push(op("op", undoCalls, redoCalls))
        assertTrue(stack.undo())
        assertEquals(1, undoCalls[0])
        assertEquals(0, redoCalls[0])
        assertFalse(stack.state.value.canUndo)
        assertTrue(stack.state.value.canRedo)
    }

    @Test
    fun `redo invokes the operation's redo callback`() {
        val undoCalls = IntArray(1)
        val redoCalls = IntArray(1)
        val stack = UndoStack()
        stack.push(op("op", undoCalls, redoCalls))
        stack.undo()
        assertTrue(stack.redo())
        assertEquals(1, undoCalls[0])
        assertEquals(1, redoCalls[0])
        assertTrue(stack.state.value.canUndo)
        assertFalse(stack.state.value.canRedo)
    }

    @Test
    fun `push after undo clears the redo branch`() {
        val stack = UndoStack()
        stack.push(op("first"))
        stack.undo()
        assertTrue(stack.state.value.canRedo)
        stack.push(op("second"))
        assertFalse(stack.state.value.canRedo, "new push should drop the redo branch")
        assertEquals("second", stack.state.value.undoDescription)
    }

    @Test
    fun `stack is bounded by the configured limit`() {
        val stack = UndoStack(limit = 3)
        for (i in 1..5) stack.push(op("op$i"))
        assertEquals(3, stack.undoDepthForTest, "stack should retain only the last 3 operations")
        assertEquals("op5", stack.state.value.undoDescription)
    }

    @Test
    fun `clear empties both stacks`() {
        val stack = UndoStack()
        stack.push(op("a"))
        stack.push(op("b"))
        stack.undo()
        stack.clear()
        assertEquals(0, stack.undoDepthForTest)
        assertEquals(0, stack.redoDepthForTest)
        assertFalse(stack.state.value.canUndo)
        assertFalse(stack.state.value.canRedo)
    }

    @Test
    fun `state flow emits a new snapshot on every mutation`() {
        val stack = UndoStack()
        val snapshots = mutableListOf<UndoState>()
        snapshots.add(stack.state.value)
        stack.push(op("a"));  snapshots.add(stack.state.value)
        stack.push(op("b"));  snapshots.add(stack.state.value)
        stack.undo();         snapshots.add(stack.state.value)
        stack.redo();         snapshots.add(stack.state.value)
        stack.clear();        snapshots.add(stack.state.value)
        assertEquals(6, snapshots.size)
        // initial → push a → push b → undo (canRedo) → redo → clear
        assertFalse(snapshots[0].canUndo)
        assertTrue(snapshots[1].canUndo)
        assertEquals("b", snapshots[2].undoDescription)
        assertTrue(snapshots[3].canRedo)
        assertFalse(snapshots[4].canRedo)
        assertFalse(snapshots[5].canUndo)
    }

    @Test
    fun `constructor rejects a non-positive limit`() {
        try {
            UndoStack(limit = 0)
            kotlin.test.fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
