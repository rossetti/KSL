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

package ksl.app.swing.common.validation

import ksl.app.validation.FieldError
import ksl.app.validation.ValidationFeedbackBus
import java.awt.event.ActionEvent
import javax.swing.AbstractAction

/**
 * Navigates between validation issues in path-order — the order they
 * appear in the bus's current [ksl.app.validation.ValidationResult]
 * (errors first, then warnings).  Wraps at both ends.  Keeps the
 * last-visited *path* as state so navigation is stable across
 * publish events; if that path is no longer present, navigation
 * falls back to the first / last issue in the new ordering.
 *
 * The action does not install keybindings on its own.  Callers
 * (typically the editor frame) bind it under whatever keystrokes the
 * app's keymap dictates — for example F8 / Shift+F8 per scenario
 * workflow §4.
 *
 * The action is enabled iff there is at least one issue at the
 * moment `actionPerformed` is invoked; the enabled flag is not
 * driven by the bus state because that would require a coroutine
 * scope just to update a boolean.  Callers that want enabled state
 * to track the bus can subscribe themselves.
 *
 * @param bus source of validation state.
 * @param registry resolves a path to a focusable widget.
 * @param direction navigation direction.
 * @param onMissingWidget optional callback when an issue exists but
 *   no widget is registered for its path — typical wiring is to log
 *   a debug message or surface the path in a banner detail row.
 */
class JumpToErrorAction(
    private val bus: ValidationFeedbackBus,
    private val registry: WidgetPathRegistry,
    private val direction: Direction,
    title: String = if (direction == Direction.NEXT) "Jump to Next Error" else "Jump to Previous Error",
    private val onMissingWidget: (FieldError) -> Unit = {}
) : AbstractAction(title) {

    enum class Direction { NEXT, PREVIOUS }

    private var lastVisitedPath: String? = null

    override fun actionPerformed(e: ActionEvent?) {
        val issues = bus.result.value.allIssues
        if (issues.isEmpty()) return

        val previousIndex = lastVisitedPath?.let { last ->
            issues.indexOfFirst { it.path == last }.takeIf { it >= 0 }
        }
        val targetIndex = when {
            previousIndex == null && direction == Direction.NEXT -> 0
            previousIndex == null && direction == Direction.PREVIOUS -> issues.lastIndex
            direction == Direction.NEXT -> (previousIndex!! + 1) % issues.size
            else -> (previousIndex!! - 1 + issues.size) % issues.size
        }
        val target = issues[targetIndex]
        lastVisitedPath = target.path
        if (!JumpUtil.jumpTo(registry, target.path)) {
            onMissingWidget(target)
        }
    }

    /**
     * Forgets the last-visited path so the next invocation starts
     * fresh.  Typically called when the document is closed and the
     * editor view-model resets per-document state.
     */
    fun reset() {
        lastVisitedPath = null
    }
}
