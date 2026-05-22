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

package ksl.app.swing.scenario

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dialog
import java.awt.Dimension
import java.awt.Window
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 *  Modal dialog for picking which scenarios to include in an on-
 *  demand report, plus the report style.  Returned from
 *  [ReportsTabPanel]'s consolidated "Scenario Reports…" button.
 *
 *  The dialog opens with every supplied scenario name checked and
 *  the "Summary" style selected — the most common workflow is "give
 *  me the consolidated summary for everything I just ran."  *Check
 *  All* / *Uncheck All* let the user invert quickly; the *Generate*
 *  button disables when nothing is checked.
 *
 *  Result on success is a [Result.Generate] carrying the picked
 *  names (preserving the order they were supplied in) and the chosen
 *  [Style].  Cancel / window-close yields [Result.Cancelled].
 */
object ScenarioReportDialog {

    /** Report style chosen by the user in the dialog. */
    enum class Style {
        /**
         *  One consolidated document covering every picked scenario.
         *  Uses [ScenarioReports.renderScenarioSummaries] with
         *  `scenarioNames = pickedNames`.
         */
        SUMMARY,

        /**
         *  One full per-scenario deep-dive document per picked
         *  scenario.  Each invocation calls
         *  [ScenarioReports.renderScenarioSummary] for one
         *  scenario; the caller batches them and suppresses the
         *  browser-open for all but the first HTML to avoid N
         *  simultaneous browser tabs.
         */
        FULL_PER_SCENARIO
    }

    /** Outcome of the dialog interaction. */
    sealed class Result {
        data class Generate(val pickedNames: List<String>, val style: Style) : Result()
        object Cancelled : Result()
    }

    /**
     *  Show the dialog over [parent] with one row per [scenarioNames]
     *  entry.  Blocks until the user clicks Generate / Cancel /
     *  closes the dialog window.
     */
    fun showDialog(
        parent: Component,
        scenarioNames: List<String>
    ): Result {
        // Resolve the owning Window — prefer parent itself when it's
        // already a Window, otherwise walk up.  Matches the pattern
        // used by ChooseResponseDialog and the analysis dialogs.
        val owner: Window = (parent as? Window)
            ?: SwingUtilities.getWindowAncestor(parent)
            ?: return Result.Cancelled
        val dialog = PickerDialog(owner, scenarioNames)
        dialog.isVisible = true
        return dialog.outcome
    }

    private class PickerDialog(
        owner: Window,
        private val scenarioNames: List<String>
    ) : JDialog(owner, "Scenario Reports", Dialog.ModalityType.APPLICATION_MODAL) {

        var outcome: Result = Result.Cancelled
            private set

        private val checkBoxes: List<JCheckBox> = scenarioNames.map { name ->
            JCheckBox(name, true).apply { isFocusable = false }
        }

        private val summaryRadio = JRadioButton(
            "Summary — one document covering every picked scenario",
            true
        )
        private val fullRadio = JRadioButton(
            "Full per-scenario report — one document per picked scenario"
        )

        private val statusLabel = JLabel(" ").apply {
            border = BorderFactory.createEmptyBorder(4, 12, 4, 12)
            foreground = Color(0x33, 0x77, 0x33)
        }

        private val generateButton = JButton("Generate")
        private val cancelButton = JButton("Cancel")

        init {
            defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
            preferredSize = Dimension(560, 460)
            contentPane.layout = BorderLayout()
            contentPane.add(buildHeader(), BorderLayout.NORTH)
            contentPane.add(buildBody(), BorderLayout.CENTER)
            contentPane.add(buildButtons(), BorderLayout.SOUTH)
            rootPane.defaultButton = generateButton

            // Subscribe each checkbox to the status / Generate-enabled
            // refresh.  Cheap to rebuild on every click.
            for (cb in checkBoxes) {
                cb.addActionListener { refreshStatus() }
            }
            ButtonGroup().apply { add(summaryRadio); add(fullRadio) }

            cancelButton.addActionListener { dispose() }
            generateButton.addActionListener { onGenerate() }

            refreshStatus()
            pack()
            setLocationRelativeTo(owner)
        }

        private fun buildHeader(): JComponent = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Color(0xCC, 0xCC, 0xCC)),
                BorderFactory.createEmptyBorder(8, 12, 6, 12)
            )
            add(JLabel("Pick the scenarios to include and the report style.").apply {
                alignmentX = Component.LEFT_ALIGNMENT
            })
            add(statusLabel.apply { alignmentX = Component.LEFT_ALIGNMENT })
        }

        private fun buildBody(): JComponent {
            val listPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                background = java.awt.Color.WHITE
                border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
            }
            for (cb in checkBoxes) {
                cb.alignmentX = Component.LEFT_ALIGNMENT
                cb.background = java.awt.Color.WHITE
                listPanel.add(cb)
            }
            val scroll = JScrollPane(listPanel).apply {
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createEmptyBorder(0, 12, 8, 12),
                    BorderFactory.createLineBorder(Color(0xCC, 0xCC, 0xCC))
                )
            }
            val toggles = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                border = BorderFactory.createEmptyBorder(0, 12, 8, 12)
                add(JButton("Check All").apply {
                    addActionListener { setAllChecked(true); refreshStatus() }
                })
                add(Box.createHorizontalStrut(8))
                add(JButton("Uncheck All").apply {
                    addActionListener { setAllChecked(false); refreshStatus() }
                })
                add(Box.createHorizontalGlue())
            }
            val styles = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createEmptyBorder(4, 12, 8, 12),
                    BorderFactory.createTitledBorder("Report style")
                )
                summaryRadio.alignmentX = Component.LEFT_ALIGNMENT
                fullRadio.alignmentX = Component.LEFT_ALIGNMENT
                add(summaryRadio)
                add(fullRadio)
            }
            return JPanel(BorderLayout()).apply {
                add(JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    add(scroll)
                    add(toggles)
                }, BorderLayout.CENTER)
                add(styles, BorderLayout.SOUTH)
            }
        }

        private fun buildButtons(): JComponent = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Color(0xCC, 0xCC, 0xCC)),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)
            )
            add(Box.createHorizontalGlue())
            add(cancelButton)
            add(Box.createHorizontalStrut(8))
            add(generateButton)
        }

        private fun setAllChecked(checked: Boolean) {
            for (cb in checkBoxes) cb.isSelected = checked
        }

        private fun refreshStatus() {
            val n = checkBoxes.count { it.isSelected }
            generateButton.isEnabled = n > 0
            if (n == 0) {
                statusLabel.text = "Pick at least one scenario."
                statusLabel.foreground = Color(0x99, 0x55, 0x00)
            } else {
                statusLabel.text = "Ready — $n of ${checkBoxes.size} scenario${if (checkBoxes.size == 1) "" else "s"} selected."
                statusLabel.foreground = Color(0x33, 0x77, 0x33)
            }
        }

        private fun onGenerate() {
            val picked = scenarioNames.filterIndexed { i, _ -> checkBoxes[i].isSelected }
            if (picked.isEmpty()) return
            val style = if (summaryRadio.isSelected) Style.SUMMARY else Style.FULL_PER_SCENARIO
            outcome = Result.Generate(picked, style)
            dispose()
        }
    }
}
