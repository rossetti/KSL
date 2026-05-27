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

import ksl.app.config.optimization.InequalityType
import ksl.app.config.optimization.LinearConstraintSpec
import ksl.app.config.optimization.OptimizationInputSpec
import ksl.app.config.optimization.PenaltyFunctionSpec
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Window
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Modal dialog for adding or editing one linear constraint over the
 * problem's decision variables.
 *
 * Layout:
 *   - One coefficient field per declared decision variable (vertical
 *     grid in a `JScrollPane` so the dialog stays a reasonable height
 *     for models with many inputs).  Defaults: 0.0 (excluded).
 *   - Inequality radio (≤ / ≥).
 *   - RHS field.
 *   - "Override default penalty function" checkbox + embedded
 *     [PenaltyFunctionEditor] disclosure.
 *
 * Returns a [LinearConstraintSpec] on OK, or `null` on Cancel /
 * window-close.
 *
 * OK is enabled only when every coefficient and the RHS parses as
 * finite, AND (when the override checkbox is checked) the penalty
 * editor is also valid.
 *
 * @param owner parent window
 * @param declaredInputs the document's currently-declared decision
 *        variables; one coefficient row per entry
 * @param defaultLinearPenalty the problem-level default; shown as a
 *        muted label and offered when the override checkbox is first
 *        ticked
 * @param mode Add (fresh entry) or Edit (pre-populated)
 */
class LinearConstraintDialog(
    owner: Window?,
    private val declaredInputs: List<OptimizationInputSpec>,
    private val defaultLinearPenalty: PenaltyFunctionSpec,
    private val mode: Mode
) : JDialog(owner, dialogTitleFor(mode), ModalityType.APPLICATION_MODAL) {

    private var result: LinearConstraintSpec? = null

    // Coefficient fields keyed by input name.
    private val coefficientFields: Map<String, JTextField> =
        declaredInputs.associate { it.name to JTextField(10) }

    private val leqRadio = JRadioButton("≤  (less than)")
    private val geqRadio = JRadioButton("≥  (greater than)")
    private val rhsField = JTextField(12)

    private val overrideCheckbox = JCheckBox("Override default penalty function")
    private val penaltyEditor = PenaltyFunctionEditor(
        initial = (mode as? Mode.Edit)?.spec?.penaltyFunction ?: defaultLinearPenalty,
        onChanged = { refreshOkEnablement() }
    )

    private val statusLabel = JLabel(" ").apply {
        font = font.deriveFont(Font.PLAIN, 12f)
    }
    private val okButton = JButton("OK").apply { isEnabled = false }
    private val cancelButton = JButton("Cancel")

    init {
        ButtonGroup().apply { add(leqRadio); add(geqRadio) }
        leqRadio.isSelected = true

        layout = BorderLayout()
        contentPane.add(buildCenter(), BorderLayout.CENTER)
        contentPane.add(buildButtonRow(), BorderLayout.SOUTH)

        wireFieldValidators()
        wireOverrideCheckbox()
        wireButtons()

        // Pre-populate from Edit mode.
        when (mode) {
            is Mode.Add -> {
                // Defaults: coefficient = 0.0, inequality = ≤, RHS = empty.
                coefficientFields.values.forEach { it.text = "0.0" }
                rhsField.text = "0.0"
                overrideCheckbox.isSelected = false
                penaltyEditor.isEnabled = false
            }
            is Mode.Edit -> {
                val spec = mode.spec
                coefficientFields.forEach { (name, field) ->
                    val v = spec.coefficients[name] ?: 0.0
                    field.text = v.toString()
                }
                if (spec.inequalityType == InequalityType.GREATER_THAN) geqRadio.isSelected = true
                else leqRadio.isSelected = true
                rhsField.text = spec.rhsValue.toString()
                val override = spec.penaltyFunction != null
                overrideCheckbox.isSelected = override
                penaltyEditor.isEnabled = override
                if (override) penaltyEditor.setValue(spec.penaltyFunction!!)
            }
        }
        refreshOkEnablement()

        pack()
        minimumSize = Dimension(560, 460)
        setLocationRelativeTo(owner)
    }

    fun showDialog(): LinearConstraintSpec? {
        isVisible = true
        return result
    }

    // ── Layout builders ──────────────────────────────────────────────────

    private fun buildCenter(): JPanel = JPanel(GridBagLayout()).apply {
        border = BorderFactory.createEmptyBorder(10, 14, 10, 14)

        // Coefficients section
        add(JLabel("Coefficients (one per declared decision variable):").apply {
            font = font.deriveFont(Font.BOLD)
        }, gbc(0, 0, width = 2, anchor = GridBagConstraints.WEST))

        val coeffPanel = JPanel(GridBagLayout())
        if (coefficientFields.isEmpty()) {
            coeffPanel.add(
                JLabel("(no decision variables declared — add at least one on the Problem step)").apply {
                    foreground = Color(0xB5, 0x40, 0x40)
                },
                gbc(0, 0)
            )
        } else {
            declaredInputs.forEachIndexed { row, input ->
                coeffPanel.add(JLabel(input.name + "  "),
                    gbc(0, row, anchor = GridBagConstraints.WEST, insets = Insets(2, 4, 2, 8)))
                coeffPanel.add(coefficientFields.getValue(input.name),
                    gbc(1, row, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
            }
        }
        val coeffScroll = JScrollPane(
            coeffPanel,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        ).apply { preferredSize = Dimension(440, 160) }
        add(coeffScroll, gbc(0, 1, width = 2, weightx = 1.0, weighty = 1.0,
            fill = GridBagConstraints.BOTH, insets = Insets(4, 4, 12, 4)))

        // Inequality row
        add(JLabel("Inequality:"), gbc(0, 2, anchor = GridBagConstraints.WEST))
        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(leqRadio); add(geqRadio)
        }, gbc(1, 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        // RHS row
        add(JLabel("RHS:"), gbc(0, 3, anchor = GridBagConstraints.WEST))
        add(rhsField, gbc(1, 3, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        // Override penalty section
        add(overrideCheckbox, gbc(0, 4, width = 2, anchor = GridBagConstraints.WEST,
            insets = Insets(10, 4, 2, 4)))
        add(penaltyEditor.apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Per-constraint penalty"),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)
            )
        }, gbc(0, 5, width = 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL,
            insets = Insets(2, 14, 2, 4)))
    }

    private fun buildButtonRow(): JPanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, Color(0xE6, 0xE6, 0xE6)),
            BorderFactory.createEmptyBorder(8, 14, 8, 14)
        )
        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(statusLabel) }, BorderLayout.WEST)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            add(cancelButton); add(okButton)
        }, BorderLayout.EAST)
    }

    // ── Wiring ───────────────────────────────────────────────────────────

    private fun wireFieldValidators() {
        val docListener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) { refreshOkEnablement() }
            override fun removeUpdate(e: DocumentEvent?) { refreshOkEnablement() }
            override fun changedUpdate(e: DocumentEvent?) { refreshOkEnablement() }
        }
        coefficientFields.values.forEach { it.document.addDocumentListener(docListener) }
        rhsField.document.addDocumentListener(docListener)
        leqRadio.addActionListener { refreshOkEnablement() }
        geqRadio.addActionListener { refreshOkEnablement() }
    }

    private fun wireOverrideCheckbox() {
        overrideCheckbox.addActionListener {
            penaltyEditor.isEnabled = overrideCheckbox.isSelected
            refreshOkEnablement()
        }
    }

    private fun wireButtons() {
        okButton.addActionListener {
            val spec = buildSpecOrNull() ?: return@addActionListener
            result = spec
            isVisible = false
            dispose()
        }
        cancelButton.addActionListener {
            result = null
            isVisible = false
            dispose()
        }
    }

    // ── Validation ───────────────────────────────────────────────────────

    private fun buildSpecOrNull(): LinearConstraintSpec? {
        if (declaredInputs.isEmpty()) return null
        val coeffs = mutableMapOf<String, Double>()
        for (input in declaredInputs) {
            val v = coefficientFields.getValue(input.name).text.trim().toDoubleOrNull() ?: return null
            if (!v.isFinite()) return null
            coeffs[input.name] = v
        }
        val rhs = rhsField.text.trim().toDoubleOrNull() ?: return null
        if (!rhs.isFinite()) return null
        val ineq = if (geqRadio.isSelected) InequalityType.GREATER_THAN else InequalityType.LESS_THAN
        val penalty = if (overrideCheckbox.isSelected) penaltyEditor.value ?: return null else null
        return try {
            LinearConstraintSpec(
                coefficients = coeffs,
                rhsValue = rhs,
                inequalityType = ineq,
                penaltyFunction = penalty
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun refreshOkEnablement() {
        val msg = validationMessage()
        if (msg == null) {
            okButton.isEnabled = true
            statusLabel.text = "Ready"
            statusLabel.foreground = Color(0x2D, 0x6D, 0x40)
        } else {
            okButton.isEnabled = false
            statusLabel.text = msg
            statusLabel.foreground = Color(0xB5, 0x40, 0x40)
        }
    }

    private fun validationMessage(): String? {
        if (declaredInputs.isEmpty()) return "Declare at least one decision variable first"
        for (input in declaredInputs) {
            val text = coefficientFields.getValue(input.name).text.trim()
            val v = text.toDoubleOrNull() ?: return "Coefficient for '${input.name}' must be a number"
            if (!v.isFinite()) return "Coefficient for '${input.name}' must be finite"
        }
        val rhs = rhsField.text.trim().toDoubleOrNull() ?: return "RHS must be a number"
        if (!rhs.isFinite()) return "RHS must be finite"
        if (overrideCheckbox.isSelected) {
            val penaltyMsg = penaltyEditor.validationMessage()
            if (penaltyMsg != null) return "Penalty: $penaltyMsg"
        }
        return null
    }

    private fun gbc(
        col: Int,
        row: Int,
        weightx: Double = 0.0,
        weighty: Double = 0.0,
        width: Int = 1,
        anchor: Int = GridBagConstraints.CENTER,
        fill: Int = GridBagConstraints.NONE,
        insets: Insets = Insets(2, 4, 2, 4)
    ): GridBagConstraints = GridBagConstraints().apply {
        this.gridx = col
        this.gridy = row
        this.gridwidth = width
        this.weightx = weightx
        this.weighty = weighty
        this.anchor = anchor
        this.fill = fill
        this.insets = insets
    }

    sealed class Mode {
        object Add : Mode()
        data class Edit(val index: Int, val spec: LinearConstraintSpec) : Mode()
    }

    private companion object {
        private fun dialogTitleFor(mode: Mode): String = when (mode) {
            is Mode.Add -> "Add linear constraint"
            is Mode.Edit -> "Edit linear constraint"
        }
    }
}
