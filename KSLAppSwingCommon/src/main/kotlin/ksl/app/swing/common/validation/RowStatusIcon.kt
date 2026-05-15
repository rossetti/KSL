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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import ksl.app.validation.FieldError
import ksl.app.validation.PathParser
import ksl.app.validation.ValidationFeedbackBus
import ksl.app.validation.ValidationSeverity
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JLabel

/**
 * Aggregating status icon for a row: a scenario row at
 * `scenarios[3]`, a bundle-ref row at `bundleRefs[1]`, or any other
 * dotted/bracketed path prefix.  Shows the severity icon for the
 * worst issue at or below the prefix (errors win); hides itself when
 * the row is clean.  Tooltip shows the error / warning counts.
 *
 * Construct with a [pathPrefix], a [ValidationFeedbackBus] to
 * subscribe to, and a [CoroutineScope] that owns the subscription's
 * lifetime.  An optional click handler receives the first matching
 * [FieldError] when the icon is clicked — typically wired to a
 * "jump to error" navigation action by the caller.
 *
 * @param pathPrefix the path this row aggregates errors under.
 * @param bus source of validation state.
 * @param scope owns the flow subscription.
 * @param onClick invoked with the first matching issue, or null when
 *   no issue is present and the user clicks anyway (rare — only
 *   reachable if a click lands in the gap between a state update and
 *   the icon's hide).
 */
class RowStatusIcon(
    private val pathPrefix: String,
    private val bus: ValidationFeedbackBus,
    scope: CoroutineScope,
    private val onClick: (FieldError?) -> Unit = {}
) : JLabel() {

    init {
        isVisible = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) onClick(bus.issuesAtOrBelow(pathPrefix).firstOrNull())
            }
        })

        scope.launch(Dispatchers.Swing) {
            bus.result
                .map { it.allIssues.filter { issue -> PathParser.isAtOrBelow(pathPrefix, issue.path) } }
                .distinctUntilChanged()
                .onEach { issues -> apply(issues) }
                .collect { /* no-op terminal */ }
        }
        apply(bus.issuesAtOrBelow(pathPrefix))
    }

    private fun apply(issues: List<FieldError>) {
        val errors = issues.count { it.severity == ValidationSeverity.ERROR }
        val warnings = issues.count { it.severity == ValidationSeverity.WARNING }
        if (errors + warnings == 0) {
            isVisible = false
            icon = null
            toolTipText = null
            return
        }
        val dominant = if (errors > 0) ValidationSeverity.ERROR else ValidationSeverity.WARNING
        icon = SeverityIcon(dominant)
        isVisible = true
        toolTipText = countsTooltip(errors, warnings)
    }

    private fun countsTooltip(errors: Int, warnings: Int): String {
        val errPart = when (errors) { 0 -> null; 1 -> "1 error"; else -> "$errors errors" }
        val warnPart = when (warnings) { 0 -> null; 1 -> "1 warning"; else -> "$warnings warnings" }
        return listOfNotNull(errPart, warnPart).joinToString(", ")
    }
}
