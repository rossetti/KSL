/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.app.swing.common.editor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A single reversible change pushed onto an [UndoStack].  The
 * caller is expected to perform the change *before* pushing the
 * operation; [undo] reverts that change, [redo] re-applies it.
 *
 * @property description human-readable label for menu items like
 *   *"Undo Add Scenario"*.  Free-form; the editor decides whether
 *   to prefix *"Undo "* / *"Redo "* in its menu rendering.
 * @property undo invoked when the user asks to undo this
 *   operation.
 * @property redo invoked when the user asks to redo it after a
 *   prior undo.
 */
data class UndoableOperation(
    val description: String,
    val undo: () -> Unit,
    val redo: () -> Unit
)

/**
 * Snapshot of [UndoStack] state for menu enablement.  Editors
 * collect [UndoStack.state] to drive *Undo* / *Redo* menu items.
 *
 * @property canUndo whether [UndoStack.undo] would succeed.
 * @property canRedo whether [UndoStack.redo] would succeed.
 * @property undoDescription description of the most recently
 *   pushed operation (the one [UndoStack.undo] would revert).
 * @property redoDescription description of the most recently
 *   undone operation (the one [UndoStack.redo] would re-apply).
 */
data class UndoState(
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val undoDescription: String? = null,
    val redoDescription: String? = null
)

/**
 * Per-document undo / redo stack used by the editor's master-pane
 * operations (Add, Clone, Remove, Reorder, Enable/Disable, Rename
 * per scenario workflow §6 — and any other reversible action).
 * Bounded by [limit] (default 20 per §6); pushing past the cap
 * discards the oldest entry.
 *
 * The stack is **caller-applies**: the editor performs the user's
 * change first, then [push]es an operation that knows how to undo
 * and redo it.  This keeps the stack decoupled from the editor's
 * data model — any reversible change shape works, not just
 * scenarios-list ops.
 *
 * Calling [push] clears the redo branch — a new edit after some
 * undo invalidates the future the user diverged from.  Calling
 * [clear] empties both stacks (typically on document close).
 *
 * Thread-safety: the underlying [MutableStateFlow] is safe for
 * concurrent reads, but mutating methods are not synchronised.
 * In practice the editor view-model is the sole mutator and
 * lives on the Swing EDT.
 */
class UndoStack(val limit: Int = DEFAULT_LIMIT) {

    init { require(limit > 0) { "limit must be positive; was $limit" } }

    private val undoStack: ArrayDeque<UndoableOperation> = ArrayDeque()
    private val redoStack: ArrayDeque<UndoableOperation> = ArrayDeque()

    private val myState: MutableStateFlow<UndoState> = MutableStateFlow(UndoState())

    /** Observable state for menu enablement and label binding. */
    val state: StateFlow<UndoState> = myState.asStateFlow()

    /**
     * Records [operation] on the undo stack.  The caller is expected
     * to have already applied the change.  Pushing always clears the
     * redo branch.  If the stack is at [limit], the oldest entry is
     * dropped to make room.
     */
    fun push(operation: UndoableOperation) {
        if (undoStack.size >= limit) undoStack.removeFirst()
        undoStack.addLast(operation)
        redoStack.clear()
        publish()
    }

    /**
     * Reverts the most recently pushed operation by invoking its
     * `undo` callback, moving it to the redo stack.  Returns `true`
     * when something was undone; `false` when the stack was empty.
     */
    fun undo(): Boolean {
        val op = undoStack.removeLastOrNull() ?: return false
        op.undo()
        redoStack.addLast(op)
        publish()
        return true
    }

    /**
     * Re-applies the most recently undone operation by invoking its
     * `redo` callback, moving it back to the undo stack.  Returns
     * `true` when something was redone; `false` when the redo stack
     * was empty.
     */
    fun redo(): Boolean {
        val op = redoStack.removeLastOrNull() ?: return false
        op.redo()
        undoStack.addLast(op)
        publish()
        return true
    }

    /** Empties both stacks.  Call on document close. */
    fun clear() {
        if (undoStack.isEmpty() && redoStack.isEmpty()) return
        undoStack.clear()
        redoStack.clear()
        publish()
    }

    /** Test-only: depth of the undo stack. */
    internal val undoDepthForTest: Int get() = undoStack.size

    /** Test-only: depth of the redo stack. */
    internal val redoDepthForTest: Int get() = redoStack.size

    private fun publish() {
        myState.value = UndoState(
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty(),
            undoDescription = undoStack.lastOrNull()?.description,
            redoDescription = redoStack.lastOrNull()?.description
        )
    }

    companion object {
        /** Default cap per scenario workflow §6. */
        const val DEFAULT_LIMIT: Int = 20
    }
}
