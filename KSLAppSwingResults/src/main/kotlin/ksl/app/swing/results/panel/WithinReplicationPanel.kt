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
import ksl.utilities.io.report.dsl.report
import ksl.utilities.io.report.extensions.dbResponseHistogram
import ksl.utilities.io.report.extensions.dbResponseNormality
import ksl.utilities.io.report.extensions.dbResponseObservations
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
 *  Within-replication diagnostics tab.  For one (experiment, response)
 *  pair it builds a report from the raw per-replication observations:
 *  a histogram, an observations (run-order) plot, and normal Q-Q / P-P
 *  plots — any subset the user toggles on.
 *
 *  The report is composed from the R3 substrate extensions
 *  ([dbResponseHistogram], [dbResponseObservations],
 *  [dbResponseNormality]); the tab carries no analysis logic.  Output
 *  goes to the controller's workspace-derived directory and (for
 *  *Generate & Open*) opens in the browser.
 */
class WithinReplicationPanel(
    private val controller: ResultsAppController,
    private val notifier: NotificationSink
) : JPanel(BorderLayout()) {

    private val experimentCombo = JComboBox<String>()
    private val responseCombo = JComboBox<String>()
    private val confLevelField = JTextField("0.95", 5)
    private val histogramBox = JCheckBox("Histogram", true)
    private val observationsBox = JCheckBox("Observations", true)
    private val normalityBox = JCheckBox("Normality (Q-Q / P-P)", true)
    private val formatChooser = FormatChooser()
    private val generateButton = JButton("Generate & Open")
    private val saveButton = JButton("Save Only")

    init {
        border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        add(buildControls(), BorderLayout.NORTH)

        experimentCombo.addActionListener { updateResponses() }
        generateButton.addActionListener { generate(open = true) }
        saveButton.addActionListener { generate(open = false) }
        controller.addListener { reload() }
        reload()
    }

    private fun buildControls(): JPanel {
        val box = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        box.add(controlRow(JLabel("Experiment:"), experimentCombo, JLabel("Response:"), responseCombo))
        box.add(controlRow(JLabel("Confidence level:"), confLevelField, histogramBox, observationsBox, normalityBox))
        box.add(controlRow(JLabel("Formats:"), formatChooser, Box.createHorizontalStrut(8), generateButton, saveButton))
        return box
    }

    private fun reload() {
        val names = controller.experiments().map { it.name }
        experimentCombo.model = DefaultComboBoxModel(names.toTypedArray())
        // Button + combo state is driven by whether the selected experiment
        // records any responses — see updateResponses().
        updateResponses()
    }

    private fun updateResponses() {
        val expName = experimentCombo.selectedItem as? String
        val responses = if (expName == null) emptyList()
        else controller.experiments().firstOrNull { it.name == expName }?.responses?.map { it.name } ?: emptyList()
        if (responses.isEmpty()) {
            // Nothing to diagnose — don't let the user generate an empty report.
            responseCombo.model = DefaultComboBoxModel(arrayOf(NO_RESPONSES))
            responseCombo.isEnabled = false
            generateButton.isEnabled = false
            saveButton.isEnabled = false
        } else {
            responseCombo.model = DefaultComboBoxModel(responses.toTypedArray())
            responseCombo.isEnabled = true
            generateButton.isEnabled = true
            saveButton.isEnabled = true
        }
    }

    private fun generate(open: Boolean) {
        val db = controller.database ?: return
        val expName = experimentCombo.selectedItem as? String ?: return
        val response = responseCombo.selectedItem as? String
            ?: run { notifier.warn("Pick a response."); return }
        if (response == NO_RESPONSES) {
            notifier.warn("Experiment \"$expName\" records no responses.")
            return
        }
        val level = parseConfidenceLevel() ?: return
        if (!histogramBox.isSelected && !observationsBox.isSelected && !normalityBox.isSelected) {
            notifier.warn("Select at least one diagnostic.")
            return
        }
        val doc = report("Within-Replication — $response ($expName)") {
            if (histogramBox.isSelected) dbResponseHistogram(db, expName, response, confidenceLevel = level, showPlot = true)
            if (observationsBox.isSelected) dbResponseObservations(db, expName, response)
            if (normalityBox.isSelected) dbResponseNormality(db, expName, response)
        }
        deliverReport(controller, notifier, doc, "within-replication-$expName-$response", formatChooser.selectedFormats(), open)
    }

    private fun parseConfidenceLevel(): Double? {
        val value = confLevelField.text.trim().toDoubleOrNull()
        if (value == null || value <= 0.0 || value >= 1.0) {
            notifier.warn("Confidence level must be a number in (0, 1).")
            return null
        }
        return value
    }

    private companion object {
        const val NO_RESPONSES = "(no responses)"
    }
}
