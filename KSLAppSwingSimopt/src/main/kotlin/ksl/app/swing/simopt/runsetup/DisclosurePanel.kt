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

package ksl.app.swing.simopt.runsetup

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.event.MouseInputAdapter

/**
 * Reusable collapsible-disclosure widget.
 *
 * Renders a single clickable header row containing a `▸` / `▾`
 * twistie, a bold title, and a dimmer summary line that describes
 * the current state when the body is collapsed.  Clicking anywhere
 * on the header toggles the body's visibility.
 *
 * Used on the Run Setup step to keep advanced editors
 * (`EvaluationSettingsPanel`, `TrackingPanel`) out of the way for
 * users who are happy with the defaults — the summary line tells
 * them whether anything needs their attention before they click
 * Next.
 *
 * @param title bold header text (e.g. "Evaluation settings")
 * @param body the editor panel placed underneath the header; its
 *        visibility toggles with the disclosure state
 * @param initiallyExpanded `false` by default — collapsed disclosures
 *        are the whole point of the widget
 */
class DisclosurePanel(
    private val title: String,
    private val body: JPanel,
    initiallyExpanded: Boolean = false
) : JPanel(BorderLayout()) {

    private val twistieLabel = JLabel(if (initiallyExpanded) "▾" else "▸").apply {
        foreground = Color(0x33, 0x55, 0x88)
        font = font.deriveFont(Font.PLAIN, 13f)
    }
    private val titleLabel = JLabel(title).apply {
        font = font.deriveFont(Font.BOLD, 13f)
    }
    private val summaryLabel = JLabel(" ").apply {
        foreground = Color(0x77, 0x77, 0x77)
        font = font.deriveFont(Font.PLAIN, 11f)
    }
    private var expanded: Boolean = initiallyExpanded

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Color(0xDD, 0xDD, 0xDD)),
            BorderFactory.createEmptyBorder(2, 6, 2, 6)
        )

        val header = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            isOpaque = false
            add(twistieLabel)
            add(titleLabel)
            add(summaryLabel)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Click to ${if (expanded) "collapse" else "expand"}"
        }
        // Wire clicks anywhere on the header (the panel + each child label)
        // to toggle.  Required because FlowLayout doesn't propagate clicks
        // from child labels to the parent JPanel by default.
        val toggleListener = object : MouseInputAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent?) { toggle() }
        }
        header.addMouseListener(toggleListener)
        twistieLabel.addMouseListener(toggleListener)
        titleLabel.addMouseListener(toggleListener)
        summaryLabel.addMouseListener(toggleListener)

        add(header, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
        body.isVisible = expanded
    }

    /** Update the dimmer one-line summary text shown next to the
     *  title when the body is collapsed.  Safe to call at any time;
     *  parents typically call from a StateFlow collector so the
     *  summary tracks the editor's current state. */
    fun setSummary(text: String) {
        summaryLabel.text = if (text.isBlank()) " " else "— $text"
    }

    fun isExpanded(): Boolean = expanded

    fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        twistieLabel.text = if (expanded) "▾" else "▸"
        body.isVisible = expanded
        revalidate()
        repaint()
    }

    private fun toggle() = setExpanded(!expanded)
}
