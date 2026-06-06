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
import ksl.utilities.io.report.extensions.toFrequencyReport
import ksl.utilities.io.report.extensions.toHistogramReport
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel

/**
 *  Histograms & Frequencies tab.  Surfaces the in-simulation
 *  `ksl.modeling.variable.HistogramResponse` and
 *  `ksl.modeling.variable.IntegerFrequencyResponse` outputs — stored in the
 *  `HISTOGRAM` / `FREQUENCY` tables — which the within-replication tab does
 *  not cover (that tab histograms the per-replication averages of a
 *  `Response`; these are distributions of the underlying observations).
 *
 *  Pick an experiment, a kind (Histogram or Frequency), and a specific
 *  response (or "(all)"); *Generate & Open* drives the R3c entry points
 *  `KSLDatabase.toHistogramReport` / `toFrequencyReport`.  No analysis logic
 *  lives in the tab.
 */
class HistogramFrequencyPanel(
    private val controller: ResultsAppController,
    private val notifier: NotificationSink
) : JPanel(BorderLayout()) {

    private val experimentCombo = JComboBox<String>()
    private val kindCombo = JComboBox(arrayOf(KIND_HISTOGRAM, KIND_FREQUENCY))
    private val responseCombo = JComboBox<String>()
    private val formatChooser = FormatChooser()
    private val generateButton = JButton("Generate & Open")
    private val saveButton = JButton("Save Only")

    init {
        border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        add(buildControls(), BorderLayout.NORTH)

        experimentCombo.addActionListener { updateResponses() }
        kindCombo.addActionListener { updateResponses() }
        generateButton.addActionListener { generate(open = true) }
        saveButton.addActionListener { generate(open = false) }
        controller.addListener { reload() }
        reload()
    }

    private fun buildControls(): JPanel {
        val box = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        box.add(controlRow(JLabel("Experiment:"), experimentCombo, JLabel("Kind:"), kindCombo, JLabel("Response:"), responseCombo))
        box.add(controlRow(JLabel("Formats:"), formatChooser, Box.createHorizontalStrut(8), generateButton, saveButton))
        return box
    }

    private fun reload() {
        val names = controller.experiments().map { it.name }
        experimentCombo.model = DefaultComboBoxModel(names.toTypedArray())
        val enabled = names.isNotEmpty()
        generateButton.isEnabled = enabled
        saveButton.isEnabled = enabled
        updateResponses()
    }

    private fun updateResponses() {
        val expName = experimentCombo.selectedItem as? String
        val names = when {
            expName == null -> emptyList()
            kindCombo.selectedItem == KIND_FREQUENCY -> controller.frequencyResponseNames(expName)
            else -> controller.histogramResponseNames(expName)
        }
        responseCombo.model = DefaultComboBoxModel((listOf(ALL) + names).toTypedArray())
    }

    private fun generate(open: Boolean) {
        val db = controller.database ?: return
        val expName = experimentCombo.selectedItem as? String ?: return
        val selected = responseCombo.selectedItem as? String ?: return
        val all = selected == ALL
        val isFrequency = kindCombo.selectedItem == KIND_FREQUENCY

        val doc = if (isFrequency) {
            db.toFrequencyReport(expName, if (all) null else selected)
        } else {
            db.toHistogramReport(expName, if (all) null else selected)
        }
        val kindStem = if (isFrequency) "frequency" else "histogram"
        val stem = if (all) "$kindStem-$expName" else "$kindStem-$expName-$selected"
        deliverReport(controller, notifier, doc, stem, formatChooser.selectedFormats(), open)
    }

    private companion object {
        const val KIND_HISTOGRAM = "Histogram"
        const val KIND_FREQUENCY = "Frequency"
        const val ALL = "(all)"
    }
}
