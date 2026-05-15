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

package ksl.app.swing.common.overridefield

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import ksl.app.swing.common.validation.SeverityIcon
import ksl.app.validation.FieldError
import ksl.app.validation.PathParser
import ksl.app.validation.ValidationFeedbackBus
import ksl.app.validation.ValidationSeverity
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Collapsible-section header for the per-scenario editor's
 * sections (Run Parameters, Control Overrides, RV Overrides, Model
 * Configuration Map).  Renders:
 *
 *  - A chevron (`▼` expanded / `▶` collapsed).
 *  - The section title.
 *  - The aggregate severity icon for every `FieldError` at or below
 *    [pathPrefix], plus a count summary.
 *
 * The header does **not** own the section's content panel — the
 * caller wires [onToggle] (and/or polls [isExpanded]) to show /
 * hide the content panel.  This keeps the header reusable for any
 * section layout.
 *
 * Click anywhere on the header (the title, the chevron, or empty
 * space — not the count region) to toggle.  Errors-win-over-warnings
 * in the icon per scenario workflow §4; the count tooltip lists
 * both.
 *
 * @param title text shown after the chevron.
 * @param pathPrefix path tree the header aggregates issues under.
 * @param bus source of validation state.
 * @param scope owns the flow subscription.
 * @param initiallyExpanded the header's initial expanded state; the
 *   caller is responsible for matching its content panel's
 *   visibility.
 * @param onToggle invoked after every toggle (programmatic or
 *   user-driven) with the new expanded state.
 */
class SectionHeaderWithStatus(
    title: String,
    private val pathPrefix: String,
    private val bus: ValidationFeedbackBus,
    scope: CoroutineScope,
    initiallyExpanded: Boolean = false,
    private val onToggle: (expanded: Boolean) -> Unit = {}
) : JPanel(BorderLayout()) {

    private val chevronLabel = JLabel(if (initiallyExpanded) CHEVRON_EXPANDED else CHEVRON_COLLAPSED)
    private val titleLabel = JLabel(title).apply {
        font = font.deriveFont(java.awt.Font.BOLD)
        border = BorderFactory.createEmptyBorder(0, 6, 0, 0)
    }
    private val statusLabel = JLabel().apply { isVisible = false }
    private val countsLabel = JLabel().apply {
        border = BorderFactory.createEmptyBorder(0, 8, 0, 4)
    }
    private var myExpanded: Boolean = initiallyExpanded

    /** Current expanded state.  Set via [setExpanded]. */
    val isExpanded: Boolean get() = myExpanded

    init {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        val left = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(chevronLabel, BorderLayout.WEST)
            add(titleLabel, BorderLayout.CENTER)
        }
        val right = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(countsLabel, BorderLayout.CENTER)
            add(statusLabel, BorderLayout.EAST)
        }
        add(left, BorderLayout.CENTER)
        add(right, BorderLayout.EAST)

        val click = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) setExpanded(!myExpanded)
            }
        }
        addMouseListener(click)
        chevronLabel.addMouseListener(click)
        titleLabel.addMouseListener(click)

        scope.launch(Dispatchers.Swing) {
            bus.result
                .map { it.allIssues.filter { issue -> PathParser.isAtOrBelow(pathPrefix, issue.path) } }
                .distinctUntilChanged()
                .onEach { apply(it) }
                .collect { /* no-op terminal */ }
        }
        apply(bus.issuesAtOrBelow(pathPrefix))
    }

    /** Programmatically toggles the header.  Fires [onToggle]. */
    fun setExpanded(value: Boolean) {
        if (value == myExpanded) return
        myExpanded = value
        chevronLabel.text = if (value) CHEVRON_EXPANDED else CHEVRON_COLLAPSED
        onToggle(value)
    }

    private fun apply(issues: List<FieldError>) {
        val errors = issues.count { it.severity == ValidationSeverity.ERROR }
        val warnings = issues.count { it.severity == ValidationSeverity.WARNING }
        if (errors + warnings == 0) {
            statusLabel.isVisible = false
            statusLabel.icon = null
            countsLabel.text = ""
            toolTipText = null
            return
        }
        val dominant = if (errors > 0) ValidationSeverity.ERROR else ValidationSeverity.WARNING
        statusLabel.icon = SeverityIcon(dominant)
        statusLabel.isVisible = true
        countsLabel.text = countsText(errors, warnings)
        toolTipText = countsText(errors, warnings)
    }

    private fun countsText(errors: Int, warnings: Int): String {
        val errPart = when (errors) { 0 -> null; 1 -> "1 error"; else -> "$errors errors" }
        val warnPart = when (warnings) { 0 -> null; 1 -> "1 warning"; else -> "$warnings warnings" }
        return listOfNotNull(errPart, warnPart).joinToString(", ")
    }

    /** Test-only: the rendered count label text (empty when clean). */
    internal val countsTextForTest: String get() = countsLabel.text

    /** Test-only: the status icon, or null when clean. */
    internal val statusIconForTest: javax.swing.Icon? get() = statusLabel.icon

    /** Test-only: simulate a user click on the title to toggle. */
    internal fun simulateClick() {
        val e = MouseEvent(
            this, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
            5, 5, 1, false, MouseEvent.BUTTON1
        )
        for (l in mouseListeners) l.mouseClicked(e)
    }

    companion object {
        private const val CHEVRON_EXPANDED = "▼"
        private const val CHEVRON_COLLAPSED = "▶"
    }
}
