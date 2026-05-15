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
import ksl.app.validation.ValidationFeedbackBus
import ksl.app.validation.ValidationSeverity
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.border.Border

/**
 * Field-level decorator: wraps a `JComponent` in a small panel that
 * paints a 2-px coloured outline around the field and a severity icon
 * on its right edge when the bound path has issues.  Tooltips on both
 * the outline and the icon list every message at the path with a
 * `code:` footnote.
 *
 * Wrap pattern (preferred):
 * ```kotlin
 * val wrapped: JComponent = FieldErrorMarker.attach(
 *     component = myField,
 *     path = "scenarios[3].runOverrides.lengthOfReplication",
 *     bus = bus,
 *     scope = editorScope,
 *     registry = registry
 * )
 * parent.add(wrapped)
 * ```
 *
 * The wrap is a `JPanel(BorderLayout)` carrying the original field at
 * `CENTER` and a placeholder icon at `EAST` (visible only when
 * issues are present).  Adding the marker to a layout adds the
 * wrapped panel, not the bare field; callers that don't want the
 * inline icon can use [decorateBorder] instead and skip the wrap.
 *
 * Subscription lifecycle: the supplied [CoroutineScope] owns the
 * flow collection.  Cancel the scope to detach the marker.
 *
 * Errors-win-over-warnings per scenario workflow §4: a field with one
 * error and three warnings shows the error border and icon; the
 * tooltip lists all four messages with their per-line severity icon.
 */
object FieldErrorMarker {

    /** Pixel thickness of the error/warning outline. */
    const val OUTLINE_THICKNESS: Int = 2

    /**
     * Wraps [component] in a marker panel.  Subscribes to [bus] for
     * issues at exactly [path]; registers the wrapped component under
     * [path] in [registry] when one is supplied.
     *
     * @return the wrapped panel to add to the parent layout instead
     *   of the bare [component].
     */
    fun attach(
        component: JComponent,
        path: String,
        bus: ValidationFeedbackBus,
        scope: CoroutineScope,
        registry: WidgetPathRegistry? = null
    ): JComponent {
        val iconLabel = JLabel().apply {
            border = BorderFactory.createEmptyBorder(0, 4, 0, 0)
            isVisible = false
        }
        val wrapper = object : JPanel(BorderLayout()) {
            override fun setToolTipText(text: String?) {
                super.setToolTipText(text)
                component.toolTipText = text
                iconLabel.toolTipText = text
            }
        }
        wrapper.add(component, BorderLayout.CENTER)
        wrapper.add(iconLabel, BorderLayout.EAST)

        val originalBorder: Border? = component.border
        scope.launch(Dispatchers.Swing) {
            bus.result
                .map { it.allIssues.filter { issue -> issue.path == path } }
                .distinctUntilChanged()
                .onEach { issues -> apply(issues, component, iconLabel, wrapper, originalBorder) }
                .collect { /* no-op terminal */ }
        }
        apply(bus.issuesAtPath(path), component, iconLabel, wrapper, originalBorder)

        registry?.register(path, wrapper)
        return wrapper
    }

    /**
     * Decorates [component]'s border + tooltip in place without
     * wrapping it.  No severity icon.  Subscription semantics match
     * [attach].
     */
    fun decorateBorder(
        component: JComponent,
        path: String,
        bus: ValidationFeedbackBus,
        scope: CoroutineScope
    ) {
        val originalBorder: Border? = component.border
        scope.launch(Dispatchers.Swing) {
            bus.result
                .map { it.allIssues.filter { issue -> issue.path == path } }
                .distinctUntilChanged()
                .onEach { issues -> applyBorderOnly(issues, component, originalBorder) }
                .collect { /* no-op terminal */ }
        }
        applyBorderOnly(bus.issuesAtPath(path), component, originalBorder)
    }

    private fun apply(
        issues: List<FieldError>,
        component: JComponent,
        iconLabel: JLabel,
        wrapper: JPanel,
        originalBorder: Border?
    ) {
        applyBorderOnly(issues, component, originalBorder)
        val dominant = dominantSeverity(issues)
        if (dominant == null) {
            iconLabel.isVisible = false
            iconLabel.icon = null
            wrapper.toolTipText = null
            return
        }
        iconLabel.icon = SeverityIcon(dominant)
        iconLabel.isVisible = true
        val tooltip = buildTooltip(issues)
        wrapper.toolTipText = tooltip
    }

    private fun applyBorderOnly(
        issues: List<FieldError>,
        component: JComponent,
        originalBorder: Border?
    ) {
        val dominant = dominantSeverity(issues)
        if (dominant == null) {
            component.border = originalBorder
            return
        }
        val color = when (dominant) {
            ValidationSeverity.ERROR -> SeverityIcon.ERROR_COLOR
            ValidationSeverity.WARNING -> SeverityIcon.WARNING_COLOR
        }
        component.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(color, OUTLINE_THICKNESS),
            originalBorder ?: BorderFactory.createEmptyBorder()
        )
    }

    private fun dominantSeverity(issues: List<FieldError>): ValidationSeverity? {
        if (issues.isEmpty()) return null
        return if (issues.any { it.severity == ValidationSeverity.ERROR }) ValidationSeverity.ERROR
        else ValidationSeverity.WARNING
    }

    private fun buildTooltip(issues: List<FieldError>): String {
        if (issues.isEmpty()) return ""
        val sb = StringBuilder("<html>")
        for (issue in issues) {
            val marker = if (issue.severity == ValidationSeverity.ERROR) "&#9650;" else "&#9675;"
            val color = if (issue.severity == ValidationSeverity.ERROR) "#C62828" else "#EF6C00"
            sb.append("<div><span style='color:$color'>$marker</span> ")
                .append(escape(issue.message))
                .append(" <span style='color:#777777;font-style:italic'>code: ")
                .append(escape(issue.code))
                .append("</span></div>")
        }
        sb.append("</html>")
        return sb.toString()
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
}
