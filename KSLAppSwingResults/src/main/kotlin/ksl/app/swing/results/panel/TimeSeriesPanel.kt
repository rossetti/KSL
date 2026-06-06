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
import ksl.utilities.io.report.extensions.dbTimeSeriesAcrossReplication
import ksl.utilities.io.report.extensions.dbTimeSeriesPerReplication
import ksl.utilities.io.report.extensions.dbTimeSeriesResponses
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
 *  Time-series tab.  For an experiment it renders the stored
 *  per-period values either for a single chosen time-series response
 *  (a per-replication overlay and/or an across-replication summary) or
 *  for every time-series response at once.
 *
 *  Composed from the R3 substrate extensions
 *  ([dbTimeSeriesPerReplication], [dbTimeSeriesAcrossReplication],
 *  [dbTimeSeriesResponses]); the tab carries no analysis logic.
 */
class TimeSeriesPanel(
    private val controller: ResultsAppController,
    private val notifier: NotificationSink
) : JPanel(BorderLayout()) {

    private val experimentCombo = JComboBox<String>()
    private val responseCombo = JComboBox<String>()
    private val confLevelField = JTextField("0.95", 5)
    private val overlayBox = JCheckBox("Per-replication overlay", true)
    private val summaryBox = JCheckBox("Across-replication summary", true)
    private val formatChooser = FormatChooser()
    private val generateButton = JButton("Generate & Open")
    private val saveButton = JButton("Save Only")

    init {
        border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        add(buildControls(), BorderLayout.NORTH)

        experimentCombo.addActionListener { updateResponses() }
        responseCombo.addActionListener { updateToggleEnabled() }
        generateButton.addActionListener { generate(open = true) }
        saveButton.addActionListener { generate(open = false) }
        controller.addListener { reload() }
        reload()
    }

    private fun buildControls(): JPanel {
        val box = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        box.add(controlRow(JLabel("Experiment:"), experimentCombo, JLabel("Response:"), responseCombo))
        box.add(controlRow(JLabel("Confidence level:"), confLevelField, overlayBox, summaryBox))
        box.add(controlRow(JLabel("Formats:"), formatChooser, Box.createHorizontalStrut(8), generateButton, saveButton))
        return box
    }

    private fun reload() {
        val names = controller.experiments().map { it.name }
        experimentCombo.model = DefaultComboBoxModel(names.toTypedArray())
        // Button + combo state is driven by whether the selected experiment
        // actually has time-series data — see updateResponses().
        updateResponses()
    }

    private fun updateResponses() {
        val expName = experimentCombo.selectedItem as? String
        val tsNames = if (expName == null) emptyList() else controller.timeSeriesResponseNames(expName).sorted()
        if (tsNames.isEmpty()) {
            // Nothing to report — don't offer "(all)" over an empty set.
            responseCombo.model = DefaultComboBoxModel(arrayOf(NO_RESPONSES))
            responseCombo.isEnabled = false
            generateButton.isEnabled = false
            saveButton.isEnabled = false
        } else {
            responseCombo.model = DefaultComboBoxModel((listOf(ALL_RESPONSES) + tsNames).toTypedArray())
            responseCombo.isEnabled = true
            generateButton.isEnabled = true
            saveButton.isEnabled = true
        }
        updateToggleEnabled()
    }

    /** The per-rep / across-rep toggles only apply to a single chosen
     *  response; for "all responses" the report is across-rep per
     *  response, so the toggles are disabled. */
    private fun updateToggleEnabled() {
        val selected = responseCombo.selectedItem as? String
        val singleResponse = selected != null && selected != ALL_RESPONSES && selected != NO_RESPONSES
        overlayBox.isEnabled = singleResponse
        summaryBox.isEnabled = singleResponse
    }

    private fun generate(open: Boolean) {
        val db = controller.database ?: return
        val expName = experimentCombo.selectedItem as? String ?: return
        val selected = responseCombo.selectedItem as? String ?: return
        if (selected == NO_RESPONSES) {
            notifier.warn("Experiment \"$expName\" has no time-series data.")
            return
        }
        val level = parseConfidenceLevel() ?: return
        val allResponses = selected == ALL_RESPONSES

        if (!allResponses && !overlayBox.isSelected && !summaryBox.isSelected) {
            notifier.warn("Select per-replication overlay and/or across-replication summary.")
            return
        }

        val title = if (allResponses) "Time Series — $expName" else "Time Series — $selected ($expName)"
        val stem = if (allResponses) "time-series-$expName" else "time-series-$expName-$selected"
        val doc = report(title) {
            if (allResponses) {
                dbTimeSeriesResponses(db, expName, confidenceLevel = level, showPlots = true)
            } else {
                if (overlayBox.isSelected) dbTimeSeriesPerReplication(db, expName, selected, showPlot = true)
                if (summaryBox.isSelected) dbTimeSeriesAcrossReplication(db, expName, selected, confidenceLevel = level, showPlot = true)
            }
        }
        deliverReport(controller, notifier, doc, stem, formatChooser.selectedFormats(), open)
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
        const val ALL_RESPONSES = "(all responses)"
        const val NO_RESPONSES = "(no time-series responses)"
    }
}
