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

package ksl.app.swing.results.panel

import ksl.app.notification.NotificationSink
import ksl.app.swing.results.ResultsAppController
import ksl.utilities.io.report.extensions.toReport
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

/**
 *  Experiment-summary tab.  Renders the stored single-experiment report
 *  for one experiment — across-replication statistics plus any stored
 *  histograms and frequency distributions — via the existing
 *  `KSLDatabase.toReport(expName, …)` entry point.  No analysis logic
 *  lives in the tab.
 */
class ExperimentSummaryPanel(
    private val controller: ResultsAppController,
    private val notifier: NotificationSink
) : JPanel(BorderLayout()) {

    private val experimentCombo = JComboBox<String>()
    private val confLevelField = JTextField("0.95", 5)
    private val plotsBox = JCheckBox("Include plots", true)
    private val formatChooser = FormatChooser()
    private val generateButton = JButton("Generate & Open")
    private val saveButton = JButton("Save Only")

    init {
        border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        add(buildControls(), BorderLayout.NORTH)

        generateButton.addActionListener { generate(open = true) }
        saveButton.addActionListener { generate(open = false) }
        controller.addListener { reload() }
        reload()
    }

    private fun buildControls(): JPanel {
        val box = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        box.add(controlRow(JLabel("Experiment:"), experimentCombo, JLabel("Confidence level:"), confLevelField, plotsBox))
        box.add(controlRow(JLabel("Formats:"), formatChooser, Box.createHorizontalStrut(8), generateButton, saveButton))
        return box
    }

    private fun reload() {
        val names = controller.experiments().map { it.name }
        experimentCombo.model = DefaultComboBoxModel(names.toTypedArray())
        val enabled = names.isNotEmpty()
        generateButton.isEnabled = enabled
        saveButton.isEnabled = enabled
    }

    private fun generate(open: Boolean) {
        val db = controller.database ?: return
        val expName = experimentCombo.selectedItem as? String ?: return
        val level = parseConfidenceLevel() ?: return
        val doc = db.toReport(
            expName = expName,
            title = "Experiment Summary — $expName",
            confidenceLevel = level,
            showPlots = plotsBox.isSelected
        )
        deliverReport(controller, notifier, doc, "experiment-summary-$expName", formatChooser.selectedFormats(), open)
    }

    private fun parseConfidenceLevel(): Double? {
        val value = confLevelField.text.trim().toDoubleOrNull()
        if (value == null || value <= 0.0 || value >= 1.0) {
            notifier.warn("Confidence level must be a number in (0, 1).")
            return null
        }
        return value
    }
}
