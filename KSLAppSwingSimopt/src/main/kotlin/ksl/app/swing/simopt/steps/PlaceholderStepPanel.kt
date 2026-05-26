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

package ksl.app.swing.simopt.steps

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Shared base for the six Phase-O2 step-panel shells.
 *
 * Renders a centred title + a one-paragraph summary + a small pill
 * that names the future phase that will replace this placeholder.
 * Once a real step panel lands (Phases O3 onward), the corresponding
 * subclass is rewritten with the proper authoring UI; this base
 * class can be deleted when no placeholders remain.
 *
 * @param title human-readable step title (matches `Step.title`)
 * @param summary one-paragraph description of what this step does
 * @param pendingPhase short label naming the phase that will replace
 *        this shell (e.g. "Phase O3")
 */
open class PlaceholderStepPanel(
    private val title: String,
    private val summary: String,
    private val pendingPhase: String
) : JPanel(BorderLayout()) {

    init {
        border = BorderFactory.createEmptyBorder(40, 60, 40, 60)

        val column = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        val titleLabel = JLabel(title, SwingConstants.CENTER).apply {
            font = font.deriveFont(Font.BOLD, 22f)
            alignmentX = CENTER_ALIGNMENT
        }
        val summaryLabel = JLabel(
            "<html><body style='width: 480px; text-align: center;'>$summary</body></html>",
            SwingConstants.CENTER
        ).apply {
            foreground = Color(0x55, 0x55, 0x55)
            alignmentX = CENTER_ALIGNMENT
        }
        val phaseBadge = JLabel("Coming in $pendingPhase", SwingConstants.CENTER).apply {
            font = font.deriveFont(Font.PLAIN, 12f)
            foreground = Color(0x4D, 0x4D, 0x4D)
            isOpaque = true
            background = Color(0xEE, 0xF2, 0xF8)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color(0xC2, 0xD0, 0xE6)),
                BorderFactory.createEmptyBorder(4, 14, 4, 14)
            )
            alignmentX = CENTER_ALIGNMENT
        }

        column.add(titleLabel)
        column.add(Box.createVerticalStrut(12))
        column.add(summaryLabel)
        column.add(Box.createVerticalStrut(20))
        column.add(phaseBadge)

        add(column, BorderLayout.CENTER)
    }
}
