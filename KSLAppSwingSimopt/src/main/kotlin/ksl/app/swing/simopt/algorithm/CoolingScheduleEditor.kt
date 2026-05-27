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

package ksl.app.swing.simopt.algorithm

import ksl.app.config.optimization.CoolingScheduleSpec
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Reusable sub-panel that edits a single [CoolingScheduleSpec].
 *
 * Three substrate variants:
 *
 * - [CoolingScheduleSpec.Linear]: `initialTemperature`,
 *   `stoppingTemperature`, `maxIterations`.
 * - [CoolingScheduleSpec.Exponential]: `initialTemperature`,
 *   `coolingRate` (in (0,1)).
 * - [CoolingScheduleSpec.Logarithmic]: `initialTemperature`.
 *
 * Same layout pattern as `TemperatureSpecEditor`: type combo at the
 * top + CardLayout body.  All variants pre-populate with sensible
 * substrate-aligned defaults.
 */
class CoolingScheduleEditor(
    initial: CoolingScheduleSpec = CoolingScheduleSpec.Exponential(initialTemperature = 100.0),
    private val onChanged: (CoolingScheduleSpec?) -> Unit = {}
) : JPanel(BorderLayout()) {

    private enum class Kind(val label: String) {
        LINEAR("Linear"),
        EXPONENTIAL("Exponential"),
        LOGARITHMIC("Logarithmic");
        override fun toString(): String = label
    }

    private val typeCombo: JComboBox<Kind> = JComboBox(
        DefaultComboBoxModel(Kind.entries.toTypedArray())
    )

    // Linear-variant fields
    private val linInitialField = JTextField("100.0", 10)
    private val linStoppingField = JTextField("1.0", 10)
    private val linMaxIterField = JTextField("100", 10)

    // Exponential-variant fields (substrate default coolingRate = 0.95)
    private val expInitialField = JTextField("100.0", 10)
    private val expRateField = JTextField("0.95", 10)

    // Logarithmic-variant fields
    private val logInitialField = JTextField("100.0", 10)

    private val cards = CardLayout()
    private val cardHost = JPanel(cards)

    @Volatile private var suppress = false

    init {
        cardHost.add(buildLinearCard(), Kind.LINEAR.name)
        cardHost.add(buildExponentialCard(), Kind.EXPONENTIAL.name)
        cardHost.add(buildLogarithmicCard(), Kind.LOGARITHMIC.name)

        val topRow = JPanel(GridBagLayout())
        topRow.add(JLabel("Schedule:"), gbc(0, 0))
        topRow.add(typeCombo, gbc(1, 0, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
        add(topRow, BorderLayout.NORTH)
        add(cardHost, BorderLayout.CENTER)

        setValue(initial)

        typeCombo.addActionListener {
            cards.show(cardHost, (typeCombo.selectedItem as Kind).name)
            if (!suppress) onChanged(value)
        }
        val docListener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) { if (!suppress) onChanged(value) }
            override fun removeUpdate(e: DocumentEvent?) { if (!suppress) onChanged(value) }
            override fun changedUpdate(e: DocumentEvent?) { if (!suppress) onChanged(value) }
        }
        linInitialField.document.addDocumentListener(docListener)
        linStoppingField.document.addDocumentListener(docListener)
        linMaxIterField.document.addDocumentListener(docListener)
        expInitialField.document.addDocumentListener(docListener)
        expRateField.document.addDocumentListener(docListener)
        logInitialField.document.addDocumentListener(docListener)
    }

    private fun buildLinearCard(): JPanel = JPanel(GridBagLayout()).apply {
        add(JLabel("Initial temperature:"), gbc(0, 0))
        add(linInitialField, gbc(1, 0, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
        add(JLabel("Stopping temperature:"), gbc(0, 1))
        add(linStoppingField, gbc(1, 1, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
        add(JLabel("Max iterations:"), gbc(0, 2))
        add(linMaxIterField, gbc(1, 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
    }

    private fun buildExponentialCard(): JPanel = JPanel(GridBagLayout()).apply {
        add(JLabel("Initial temperature:"), gbc(0, 0))
        add(expInitialField, gbc(1, 0, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
        add(JLabel("Cooling rate (0, 1):"), gbc(0, 1))
        add(expRateField, gbc(1, 1, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
    }

    private fun buildLogarithmicCard(): JPanel = JPanel(GridBagLayout()).apply {
        add(JLabel("Initial temperature:"), gbc(0, 0))
        add(logInitialField, gbc(1, 0, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
    }

    /** Parse the current card's fields.  Returns `null` when any
     *  field is unparseable or fails the substrate invariant. */
    val value: CoolingScheduleSpec?
        get() {
            return when (typeCombo.selectedItem as Kind) {
                Kind.LINEAR -> {
                    val ti = linInitialField.text.trim().toDoubleOrNull()
                    val ts = linStoppingField.text.trim().toDoubleOrNull()
                    val n = linMaxIterField.text.trim().toIntOrNull()
                    if (ti == null || ts == null || n == null) null
                    else try {
                        CoolingScheduleSpec.Linear(ti, ts, n)
                    } catch (_: IllegalArgumentException) { null }
                }
                Kind.EXPONENTIAL -> {
                    val ti = expInitialField.text.trim().toDoubleOrNull()
                    val r = expRateField.text.trim().toDoubleOrNull()
                    if (ti == null || r == null) null
                    else try {
                        CoolingScheduleSpec.Exponential(ti, r)
                    } catch (_: IllegalArgumentException) { null }
                }
                Kind.LOGARITHMIC -> {
                    val ti = logInitialField.text.trim().toDoubleOrNull()
                    if (ti == null) null
                    else try {
                        CoolingScheduleSpec.Logarithmic(ti)
                    } catch (_: IllegalArgumentException) { null }
                }
            }
        }

    /** Replace the editor's contents with [spec]. */
    fun setValue(spec: CoolingScheduleSpec) {
        suppress = true
        try {
            when (spec) {
                is CoolingScheduleSpec.Linear -> {
                    typeCombo.selectedItem = Kind.LINEAR
                    linInitialField.text = spec.initialTemperature.toString()
                    linStoppingField.text = spec.stoppingTemperature.toString()
                    linMaxIterField.text = spec.maxIterations.toString()
                    cards.show(cardHost, Kind.LINEAR.name)
                }
                is CoolingScheduleSpec.Exponential -> {
                    typeCombo.selectedItem = Kind.EXPONENTIAL
                    expInitialField.text = spec.initialTemperature.toString()
                    expRateField.text = spec.coolingRate.toString()
                    cards.show(cardHost, Kind.EXPONENTIAL.name)
                }
                is CoolingScheduleSpec.Logarithmic -> {
                    typeCombo.selectedItem = Kind.LOGARITHMIC
                    logInitialField.text = spec.initialTemperature.toString()
                    cards.show(cardHost, Kind.LOGARITHMIC.name)
                }
            }
        } finally { suppress = false }
    }

    fun validationMessage(): String? {
        return when (typeCombo.selectedItem as Kind) {
            Kind.LINEAR -> {
                val ti = linInitialField.text.trim().toDoubleOrNull()
                    ?: return "Initial temperature must be a number"
                if (!ti.isFinite() || ti <= 0.0) return "Initial temperature must be > 0 and finite"
                val ts = linStoppingField.text.trim().toDoubleOrNull()
                    ?: return "Stopping temperature must be a number"
                if (!ts.isFinite() || ts <= 0.0) return "Stopping temperature must be > 0 and finite"
                if (ts >= ti) return "Stopping must be strictly less than initial temperature"
                val n = linMaxIterField.text.trim().toIntOrNull()
                    ?: return "Max iterations must be an integer"
                if (n <= 0) return "Max iterations must be > 0"
                null
            }
            Kind.EXPONENTIAL -> {
                val ti = expInitialField.text.trim().toDoubleOrNull()
                    ?: return "Initial temperature must be a number"
                if (!ti.isFinite() || ti <= 0.0) return "Initial temperature must be > 0 and finite"
                val r = expRateField.text.trim().toDoubleOrNull()
                    ?: return "Cooling rate must be a number"
                if (r <= 0.0 || r >= 1.0) return "Cooling rate must be strictly in (0, 1)"
                null
            }
            Kind.LOGARITHMIC -> {
                val ti = logInitialField.text.trim().toDoubleOrNull()
                    ?: return "Initial temperature must be a number"
                if (!ti.isFinite() || ti <= 0.0) return "Initial temperature must be > 0 and finite"
                null
            }
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        typeCombo.isEnabled = enabled
        linInitialField.isEnabled = enabled
        linStoppingField.isEnabled = enabled
        linMaxIterField.isEnabled = enabled
        expInitialField.isEnabled = enabled
        expRateField.isEnabled = enabled
        logInitialField.isEnabled = enabled
    }

    private fun gbc(
        col: Int,
        row: Int,
        weightx: Double = 0.0,
        fill: Int = GridBagConstraints.NONE
    ): GridBagConstraints = GridBagConstraints().apply {
        this.gridx = col
        this.gridy = row
        this.weightx = weightx
        this.anchor = if (col == 0) GridBagConstraints.WEST else GridBagConstraints.CENTER
        this.fill = fill
        this.insets = Insets(2, 4, 2, 4)
    }
}
