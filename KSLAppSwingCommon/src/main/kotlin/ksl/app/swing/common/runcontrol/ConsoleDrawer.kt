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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import ksl.app.session.RunEvent
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Collapsible bottom-of-window drawer that hosts a [ConsoleLogPanel].
 *
 * IDE-style "console drawer": a thin always-visible header strip shows
 * a toggle button and a per-run event-count summary
 * (`N INFO · N WARN · N ERR`).  Clicking the toggle expands the drawer
 * to [expandedHeight] pixels of console body; clicking again collapses
 * back to the header.
 *
 * The drawer is intended for `BorderLayout.SOUTH` (or equivalent) of the
 * main frame.  It owns the console body internally but does not own the
 * underlying [SharedFlow] — counts are computed from a parallel
 * subscription, so the embedded [ConsoleLogPanel] continues to render
 * lines independently.
 *
 * Counts reset to zero on every `RunEvent.*Started` event so the user
 * always sees the *current* run's tally, not a cumulative one across
 * the session.
 *
 * @param eventFlow source of run events, shared with the embedded console.
 * @param scope owns the count-subscription job.
 * @param console the [ConsoleLogPanel] to embed as the drawer's body.
 *   The drawer takes layout ownership; do not parent it elsewhere.
 * @param initiallyExpanded whether the drawer starts open.  Default: false.
 * @param expandedHeight pixel height of the console body when expanded.
 *   Default: 220px.
 */
class ConsoleDrawer(
    eventFlow: SharedFlow<RunEvent>,
    scope: CoroutineScope,
    private val console: ConsoleLogPanel,
    initiallyExpanded: Boolean = false,
    private val expandedHeight: Int = 220
) : JPanel(BorderLayout()) {

    private var expanded: Boolean = initiallyExpanded
    private var infoCount: Int = 0
    private var warnCount: Int = 0
    private var errCount: Int = 0

    private val toggleButton: JButton = JButton(toggleLabel()).apply {
        isFocusable = false
        margin = java.awt.Insets(2, 10, 2, 10)
        addActionListener { setExpanded(!expanded) }
    }
    private val countsLabel: JLabel = JLabel(countsText()).apply {
        border = BorderFactory.createEmptyBorder(0, 6, 0, 0)
    }
    private val header: JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 1, 0, Color(0xCC, 0xCC, 0xCC)),
            BorderFactory.createEmptyBorder(2, 8, 2, 8)
        )
        add(toggleButton)
        add(Box.createHorizontalStrut(12))
        add(countsLabel)
        add(Box.createHorizontalGlue())
    }
    private val body: JPanel = JPanel(BorderLayout()).apply {
        add(console, BorderLayout.CENTER)
        isVisible = initiallyExpanded
    }

    init {
        add(header, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)

        scope.launch(Dispatchers.Swing) {
            eventFlow.collect { ev ->
                if (isRunStart(ev)) resetCounts()
                when (ConsoleLogPanel.severityOf(ev)) {
                    ConsoleSeverity.INFO -> infoCount++
                    ConsoleSeverity.WARNING -> warnCount++
                    ConsoleSeverity.ERROR -> errCount++
                }
                refreshCountsLabel()
            }
        }
    }

    /** Programmatic open/close.  EDT-only. */
    fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        body.isVisible = expanded
        toggleButton.text = toggleLabel()
        revalidate()
        repaint()
    }

    /** Whether the drawer body is currently expanded. */
    fun isExpanded(): Boolean = expanded

    override fun getPreferredSize(): Dimension {
        val parent = super.getPreferredSize()
        val headerH = header.preferredSize.height
        return Dimension(parent.width, if (expanded) headerH + expandedHeight else headerH)
    }

    override fun getMinimumSize(): Dimension {
        val headerH = header.preferredSize.height
        return Dimension(0, headerH)
    }

    override fun getMaximumSize(): Dimension {
        val headerH = header.preferredSize.height
        return Dimension(Int.MAX_VALUE, if (expanded) headerH + expandedHeight else headerH)
    }

    private fun resetCounts() {
        infoCount = 0
        warnCount = 0
        errCount = 0
    }

    private fun refreshCountsLabel() {
        countsLabel.text = countsText()
        // Tint label red when errors present, orange when warnings present,
        // else default.  Provides a glance signal of "something interesting
        // happened" without expanding the drawer.
        countsLabel.foreground = when {
            errCount > 0 -> COLOR_ERR
            warnCount > 0 -> COLOR_WARN
            else -> COLOR_DEFAULT
        }
        countsLabel.font = if (errCount > 0 || warnCount > 0)
            countsLabel.font.deriveFont(Font.BOLD)
        else countsLabel.font.deriveFont(Font.PLAIN)
    }

    private fun countsText(): String =
        "$infoCount INFO · $warnCount WARN · $errCount ERR"

    private fun toggleLabel(): String =
        if (expanded) "▾ Console" else "▴ Console"

    private fun isRunStart(ev: RunEvent): Boolean = ev is RunEvent.Started

    companion object {
        private val COLOR_DEFAULT: Color = Color(0x55, 0x55, 0x55)
        private val COLOR_WARN: Color = Color(0xE6, 0x5C, 0x00)
        private val COLOR_ERR: Color = Color(0xC6, 0x28, 0x28)
    }
}
