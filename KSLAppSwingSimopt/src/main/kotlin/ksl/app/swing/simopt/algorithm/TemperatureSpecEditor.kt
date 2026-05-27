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

import ksl.app.config.optimization.TemperatureSpec
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
 * Reusable sub-panel that edits a single [TemperatureSpec].
 *
 * Two substrate variants:
 *
 * - [TemperatureSpec.Fixed]: one field (`temperature`).
 * - [TemperatureSpec.AutoCalibrate]: two fields (`targetProbability`,
 *   `sampleSize`).
 *
 * Layout: a type combo at the top + a CardLayout body that swaps to
 * the matching variant's fields.  Both variants pre-populate with
 * the substrate's default values so the user always sees a complete
 * form on open.
 *
 * Same shape as `PenaltyFunctionEditor`: parents read [value] (null
 * when fields don't parse), call [setValue] to push a loaded spec,
 * read [validationMessage] for status text, and pass an [onChanged]
 * callback to commit live to the controller.
 */
class TemperatureSpecEditor(
    initial: TemperatureSpec = TemperatureSpec.AutoCalibrate(),
    private val onChanged: (TemperatureSpec?) -> Unit = {}
) : JPanel(BorderLayout()) {

    private enum class Kind(val label: String) {
        FIXED("Fixed"),
        AUTO_CALIBRATE("Auto-calibrate");
        override fun toString(): String = label
    }

    private val typeCombo: JComboBox<Kind> = JComboBox(
        DefaultComboBoxModel(Kind.entries.toTypedArray())
    )

    // Fixed-variant fields
    private val fixedTemperatureField = JTextField("100.0", 10)

    // AutoCalibrate-variant fields (substrate defaults: targetProbability=0.8, sampleSize=100)
    private val autoTargetProbField = JTextField("0.8", 10)
    private val autoSampleSizeField = JTextField("100", 10)

    private val cards = CardLayout()
    private val cardHost = JPanel(cards)

    @Volatile private var suppress = false

    init {
        // Build the two cards
        cardHost.add(buildFixedCard(), Kind.FIXED.name)
        cardHost.add(buildAutoCard(), Kind.AUTO_CALIBRATE.name)

        // Layout: combo at top, card body below
        val topRow = JPanel(GridBagLayout())
        topRow.add(JLabel("Strategy:"), gbc(0, 0))
        topRow.add(typeCombo, gbc(1, 0, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
        add(topRow, BorderLayout.NORTH)
        add(cardHost, BorderLayout.CENTER)

        // Initial population from the initial spec
        setValue(initial)

        // Wiring
        typeCombo.addActionListener {
            cards.show(cardHost, (typeCombo.selectedItem as Kind).name)
            if (!suppress) onChanged(value)
        }
        val docListener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) { if (!suppress) onChanged(value) }
            override fun removeUpdate(e: DocumentEvent?) { if (!suppress) onChanged(value) }
            override fun changedUpdate(e: DocumentEvent?) { if (!suppress) onChanged(value) }
        }
        fixedTemperatureField.document.addDocumentListener(docListener)
        autoTargetProbField.document.addDocumentListener(docListener)
        autoSampleSizeField.document.addDocumentListener(docListener)
    }

    private fun buildFixedCard(): JPanel = JPanel(GridBagLayout()).apply {
        add(JLabel("Temperature:"), gbc(0, 0))
        add(fixedTemperatureField, gbc(1, 0, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
    }

    private fun buildAutoCard(): JPanel = JPanel(GridBagLayout()).apply {
        add(JLabel("Target probability:"), gbc(0, 0))
        add(autoTargetProbField, gbc(1, 0, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
        add(JLabel("Sample size:"), gbc(0, 1))
        add(autoSampleSizeField, gbc(1, 1, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
    }

    /** Parse the current card's fields into a [TemperatureSpec].
     *  Returns `null` when any field is unparseable or fails the
     *  substrate's invariant. */
    val value: TemperatureSpec?
        get() {
            return when (typeCombo.selectedItem as Kind) {
                Kind.FIXED -> {
                    val t = fixedTemperatureField.text.trim().toDoubleOrNull()
                    if (t == null || !t.isFinite() || t <= 0.0) null
                    else try {
                        TemperatureSpec.Fixed(t)
                    } catch (_: IllegalArgumentException) { null }
                }
                Kind.AUTO_CALIBRATE -> {
                    val p = autoTargetProbField.text.trim().toDoubleOrNull()
                    val n = autoSampleSizeField.text.trim().toIntOrNull()
                    if (p == null || n == null) null
                    else if (!p.isFinite() || p <= 0.0 || p >= 1.0) null
                    else if (n <= 0) null
                    else try {
                        TemperatureSpec.AutoCalibrate(p, n)
                    } catch (_: IllegalArgumentException) { null }
                }
            }
        }

    /** Replace the editor's contents with [spec]. */
    fun setValue(spec: TemperatureSpec) {
        suppress = true
        try {
            when (spec) {
                is TemperatureSpec.Fixed -> {
                    typeCombo.selectedItem = Kind.FIXED
                    fixedTemperatureField.text = spec.temperature.toString()
                    cards.show(cardHost, Kind.FIXED.name)
                }
                is TemperatureSpec.AutoCalibrate -> {
                    typeCombo.selectedItem = Kind.AUTO_CALIBRATE
                    autoTargetProbField.text = spec.targetProbability.toString()
                    autoSampleSizeField.text = spec.sampleSize.toString()
                    cards.show(cardHost, Kind.AUTO_CALIBRATE.name)
                }
            }
        } finally { suppress = false }
    }

    /** Human-readable validation message for the current fields, or
     *  `null` when the editor is valid. */
    fun validationMessage(): String? {
        return when (typeCombo.selectedItem as Kind) {
            Kind.FIXED -> {
                val t = fixedTemperatureField.text.trim().toDoubleOrNull()
                    ?: return "Temperature must be a number"
                if (!t.isFinite() || t <= 0.0) return "Temperature must be > 0 and finite"
                null
            }
            Kind.AUTO_CALIBRATE -> {
                val p = autoTargetProbField.text.trim().toDoubleOrNull()
                    ?: return "Target probability must be a number"
                if (!p.isFinite() || p <= 0.0 || p >= 1.0)
                    return "Target probability must be strictly in (0, 1)"
                val n = autoSampleSizeField.text.trim().toIntOrNull()
                    ?: return "Sample size must be an integer"
                if (n <= 0) return "Sample size must be > 0"
                null
            }
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        typeCombo.isEnabled = enabled
        fixedTemperatureField.isEnabled = enabled
        autoTargetProbField.isEnabled = enabled
        autoSampleSizeField.isEnabled = enabled
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
