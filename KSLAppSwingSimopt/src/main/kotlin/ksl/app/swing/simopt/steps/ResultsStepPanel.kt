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

import ksl.app.swing.simopt.SimoptAppController
import ksl.app.swing.simopt.results.ResultsFilesCard
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel

/**
 *  *Results* step — Phase O8 (post-feedback simplification).
 *
 *  Every successful run writes a consistent set of artifacts into
 *  its `runOutputDir` (see
 *  [ksl.app.optimization.results.ResultsArtifactWriter]).
 *  This step shows the file list with per-artifact `[Open]` buttons
 *  and an `[Open folder]` shortcut; detailed inspection happens in
 *  the user's preferred tool (Excel for CSV, browser for HTML,
 *  image viewer for PNG, text editor for TOML).
 *
 *  Layout: intro paragraph at the top, the file list card pinned
 *  just below at its preferred height, empty space underneath.  No
 *  scroll pane — five-to-six file rows fit comfortably in any
 *  reasonable window size, and the previous `JScrollPane` wrapper
 *  was stretching the card to fill the viewport (leaving the
 *  titled border's interior padded with empty space above and
 *  below the buttons).
 */
class ResultsStepPanel(controller: SimoptAppController) : JPanel(BorderLayout()) {

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        // Intro at the very top.
        add(introLabel(), BorderLayout.NORTH)

        // Card sits at NORTH of an empty wrapper so it gets its
        // preferred height (no vertical stretch).  The wrapper's
        // CENTER stays empty, providing the breathing space below
        // the card without inflating the card's titled border.
        val wrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(ResultsFilesCard(controller), BorderLayout.NORTH)
        }
        add(wrapper, BorderLayout.CENTER)
    }

    private fun introLabel(): JLabel = JLabel(
        "<html><i>Every successful run writes a folder of artifacts " +
            "(summary, CSVs, convergence plot, HTML report).  Open them with " +
            "your preferred tool — the live results stay on the Execute step.</i></html>"
    ).apply {
        foreground = Color(0x55, 0x55, 0x55)
        font = font.deriveFont(Font.PLAIN, 12f)
        border = BorderFactory.createEmptyBorder(4, 8, 8, 8)
    }
}
