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

package ksl.app.swing.simopt.problem

import ksl.app.config.optimization.PenaltyFunctionSpec
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
 * Reusable sub-panel that edits a single [PenaltyFunctionSpec].
 *
 * Both supported substrate variants — [PenaltyFunctionSpec.WithMemory]
 * and [PenaltyFunctionSpec.DynamicPolynomial] — carry the same three
 * numeric fields (basePenalty, iterationExponent, violationExponent),
 * so the editor uses one row of fields and a type combo to pick the
 * variant.  No `CardLayout` swap is needed.
 *
 * Used in two places:
 *   - The advanced disclosure on the Constraints step, for the
 *     problem-level defaults.
 *   - The "Override default penalty" disclosure inside each
 *     constraint dialog.
 *
 * The editor is purely view+state — it does not push edits to the
 * controller on every keystroke.  Parents read the current value with
 * [value] (or `null` when the fields don't parse), and either pass it
 * to a controller mutator on OK (dialog path) or push it on
 * focus-lost / Enter (disclosure path).  Parents may also observe
 * [validationMessage] to disable their OK button until the editor is
 * valid.
 *
 * @param initial initial spec to populate the fields with
 * @param onChanged callback fired on every field edit; useful for the
 *        disclosure path where the parent wants to update the
 *        controller continuously.  Receives the current parsed value
 *        (or `null` when fields don't parse).
 */
class PenaltyFunctionEditor(
    initial: PenaltyFunctionSpec = PenaltyFunctionSpec.DynamicPolynomial(),
    private val onChanged: (PenaltyFunctionSpec?) -> Unit = {}
) : JPanel(GridBagLayout()) {

    private enum class Kind(val label: String) {
        DYNAMIC_POLYNOMIAL("Dynamic polynomial"),
        WITH_MEMORY("With memory");
        override fun toString(): String = label
    }

    private val typeCombo: JComboBox<Kind> = JComboBox(
        DefaultComboBoxModel(Kind.entries.toTypedArray())
    )
    private val basePenaltyField = JTextField(10)
    private val iterationExpField = JTextField(10)
    private val violationExpField = JTextField(10)

    @Volatile private var suppress = false

    init {
        // Row 0: type combo
        add(JLabel("Type:"), gbc(0, 0))
        add(typeCombo, gbc(1, 0, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        // Rows 1-3: numeric fields
        add(JLabel("Base penalty (C):"), gbc(0, 1))
        add(basePenaltyField, gbc(1, 1, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
        add(JLabel("Iteration exponent (β):"), gbc(0, 2))
        add(iterationExpField, gbc(1, 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
        add(JLabel("Violation exponent (α):"), gbc(0, 3))
        add(violationExpField, gbc(1, 3, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        // Initial population
        setValue(initial)

        // Wire change listeners
        typeCombo.addActionListener { if (!suppress) onChanged(value) }
        val docListener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) { if (!suppress) onChanged(value) }
            override fun removeUpdate(e: DocumentEvent?) { if (!suppress) onChanged(value) }
            override fun changedUpdate(e: DocumentEvent?) { if (!suppress) onChanged(value) }
        }
        basePenaltyField.document.addDocumentListener(docListener)
        iterationExpField.document.addDocumentListener(docListener)
        violationExpField.document.addDocumentListener(docListener)
    }

    /** Parse the current fields into a [PenaltyFunctionSpec].  Returns
     *  `null` when any field is unparseable or fails the substrate's
     *  `init {}` invariant. */
    val value: PenaltyFunctionSpec?
        get() {
            val base = basePenaltyField.text.trim().toDoubleOrNull() ?: return null
            val iter = iterationExpField.text.trim().toDoubleOrNull() ?: return null
            val viol = violationExpField.text.trim().toDoubleOrNull() ?: return null
            if (!base.isFinite() || !iter.isFinite() || !viol.isFinite()) return null
            return try {
                when (typeCombo.selectedItem as Kind) {
                    Kind.DYNAMIC_POLYNOMIAL ->
                        PenaltyFunctionSpec.DynamicPolynomial(base, iter, viol)
                    Kind.WITH_MEMORY ->
                        PenaltyFunctionSpec.WithMemory(base, iter, viol)
                }
            } catch (_: IllegalArgumentException) {
                null
            }
        }

    /** Replace the editor's contents with [spec].  Suppresses
     *  [onChanged] callbacks during the population pass. */
    fun setValue(spec: PenaltyFunctionSpec) {
        suppress = true
        try {
            when (spec) {
                is PenaltyFunctionSpec.DynamicPolynomial -> {
                    typeCombo.selectedItem = Kind.DYNAMIC_POLYNOMIAL
                    basePenaltyField.text = spec.basePenalty.toString()
                    iterationExpField.text = spec.iterationExponent.toString()
                    violationExpField.text = spec.violationExponent.toString()
                }
                is PenaltyFunctionSpec.WithMemory -> {
                    typeCombo.selectedItem = Kind.WITH_MEMORY
                    basePenaltyField.text = spec.basePenalty.toString()
                    iterationExpField.text = spec.iterationExponent.toString()
                    violationExpField.text = spec.violationExponent.toString()
                }
            }
        } finally { suppress = false }
    }

    /** Human-readable validation message for the current field values,
     *  or `null` when the editor is valid.  Parents use this to set
     *  their OK button's enablement and status-line text. */
    fun validationMessage(): String? {
        val base = basePenaltyField.text.trim().toDoubleOrNull()
            ?: return "Base penalty must be a number"
        val iter = iterationExpField.text.trim().toDoubleOrNull()
            ?: return "Iteration exponent must be a number"
        val viol = violationExpField.text.trim().toDoubleOrNull()
            ?: return "Violation exponent must be a number"
        if (!base.isFinite() || base <= 0.0) return "Base penalty must be > 0 and finite"
        if (!iter.isFinite() || iter < 0.0) return "Iteration exponent must be ≥ 0 and finite"
        if (!viol.isFinite() || viol <= 0.0) return "Violation exponent must be > 0 and finite"
        return null
    }

    /** Set the entire panel's enablement.  Used by the constraint
     *  dialogs' "Override default penalty" checkbox to grey out the
     *  editor when the checkbox is unchecked. */
    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        typeCombo.isEnabled = enabled
        basePenaltyField.isEnabled = enabled
        iterationExpField.isEnabled = enabled
        violationExpField.isEnabled = enabled
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
