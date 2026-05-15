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

package ksl.app.swing.common.runcontrol

import ksl.app.config.ExecutionMode
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JPanel
import javax.swing.JToggleButton

/**
 * Segmented two-button control for
 * `ksl.app.config.RunConfiguration.executionMode`.  Renders
 * *Sequential | Concurrent* per scenario workflow §10; clicking
 * either segment commits that [ExecutionMode] via [onValueChange].
 *
 * **Run-in-flight disable.**  Per §10 the document's execution mode
 * is fixed at submit time; the toggle must be disabled while a run
 * is active.  Callers achieve this by passing the widget to a
 * `RunningPostureGuard` indirection (the guard works against
 * `Action`s, not components, so use a sample edit action or wire
 * `setEnabled(false)` from the editor's run-state flow directly).
 * The widget's [setEnabled] propagates to both internal buttons.
 *
 * @param initialMode the mode to render selected on construction.
 * @param onValueChange invoked whenever the user picks a different
 *   mode (programmatic [mode] assignment also fires it when the
 *   value actually changes).
 */
class ExecutionModeToggle(
    initialMode: ExecutionMode = ExecutionMode.SEQUENTIAL,
    private val onValueChange: (ExecutionMode) -> Unit = {}
) : JPanel() {

    private val sequentialBtn = JToggleButton("Sequential").apply { isFocusable = false }
    private val concurrentBtn = JToggleButton("Concurrent").apply { isFocusable = false }
    private val group = ButtonGroup()

    private var myMode: ExecutionMode = initialMode
    private var suppressCallback: Boolean = false

    /** Current execution mode. */
    var mode: ExecutionMode
        get() = myMode
        set(next) {
            if (next == myMode) return
            myMode = next
            suppressCallback = true
            try { selectButtonFor(next) } finally { suppressCallback = false }
            onValueChange(next)
        }

    init {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        group.add(sequentialBtn)
        group.add(concurrentBtn)
        add(sequentialBtn)
        add(concurrentBtn)
        selectButtonFor(initialMode)

        sequentialBtn.addActionListener { commit(ExecutionMode.SEQUENTIAL) }
        concurrentBtn.addActionListener { commit(ExecutionMode.CONCURRENT) }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        sequentialBtn.isEnabled = enabled
        concurrentBtn.isEnabled = enabled
    }

    private fun commit(next: ExecutionMode) {
        if (suppressCallback) return
        if (next == myMode) return
        myMode = next
        onValueChange(next)
    }

    private fun selectButtonFor(mode: ExecutionMode) {
        when (mode) {
            ExecutionMode.SEQUENTIAL -> sequentialBtn.isSelected = true
            ExecutionMode.CONCURRENT -> concurrentBtn.isSelected = true
        }
    }

    /** Test-only access to the two segment buttons. */
    internal fun buttonsForTest(): Pair<JToggleButton, JToggleButton> = sequentialBtn to concurrentBtn
}
