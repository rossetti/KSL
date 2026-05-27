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
import ksl.app.config.optimization.PenaltyFunctionSpec
import ksl.app.config.optimization.ResponseConstraintSpec
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
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Modal dialog for adding or editing one response constraint.
 *
 * Layout:
 *   - Response-name combo (model responses minus objective).
 *   - Inequality radio (≤ / ≥).
 *   - RHS / target / tolerance fields.
 *   - "Override default penalty function" checkbox + embedded
 *     [PenaltyFunctionEditor] disclosure.
 *
 * Returns a [ResponseConstraintSpec] on OK, or `null` on Cancel /
 * window-close.
 *
 * If the selected response name is not yet declared in the document
 * the controller's `addResponseConstraint(...)` / `updateResponseConstraint(...)`
 * auto-declares it on commit; the dialog itself does not coordinate
 * that side effect.  The combo therefore shows every model response
 * minus the objective, not just the currently-declared list.
 *
 * @param owner parent window
 * @param availableResponseNames response names the user may choose
 *        from (typically `descriptor.responseNames - objectiveName`)
 * @param defaultResponsePenalty the problem-level default; shown in
 *        the editor when the override checkbox is first ticked
 * @param mode Add (fresh entry) or Edit (pre-populated)
 */
class ResponseConstraintDialog(
    owner: Window?,
    private val availableResponseNames: List<String>,
    private val defaultResponsePenalty: PenaltyFunctionSpec,
    private val mode: Mode
) : JDialog(owner, dialogTitleFor(mode), ModalityType.APPLICATION_MODAL) {

    private var result: ResponseConstraintSpec? = null

    // Sort by leaf segment first (the user-meaningful response name)
    // with the full path as tiebreaker.  Long hierarchy-prefixed
    // responses sit alphabetically by their leaf name.
    private val sortedAvailableResponseNames: List<String> =
        availableResponseNames.sortedWith(
            compareBy({ it.substringAfterLast(':') }, { it })
        )

    private val nameCombo: JComboBox<String> = JComboBox(
        DefaultComboBoxModel(sortedAvailableResponseNames.toTypedArray())
    ).apply {
        // Leaf-emphasizing renderer.
        renderer = ResponseNameCellRenderer()
    }

    /** Filter field — narrows the response combo's items live. */
    private val nameFilterField = JTextField(12).apply {
        toolTipText = "Filter responses by substring (case-insensitive)."
    }
    private val leqRadio = JRadioButton("≤  (less than)")
    private val geqRadio = JRadioButton("≥  (greater than)")
    private val rhsField = JTextField(12)
    private val targetField = JTextField(12)
    private val toleranceField = JTextField(12)

    private val overrideCheckbox = JCheckBox("Override default penalty function")
    private val penaltyEditor = PenaltyFunctionEditor(
        initial = (mode as? Mode.Edit)?.spec?.penaltyFunction ?: defaultResponsePenalty,
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

        // Pre-populate from mode.
        when (mode) {
            is Mode.Add -> {
                rhsField.text = "0.0"
                targetField.text = "0.0"
                toleranceField.text = "0.0"
                overrideCheckbox.isSelected = false
                penaltyEditor.isEnabled = false
            }
            is Mode.Edit -> {
                val spec = mode.spec
                if (spec.name in availableResponseNames) nameCombo.selectedItem = spec.name
                if (spec.inequalityType == InequalityType.GREATER_THAN) geqRadio.isSelected = true
                else leqRadio.isSelected = true
                rhsField.text = spec.rhsValue.toString()
                targetField.text = spec.target.toString()
                toleranceField.text = spec.tolerance.toString()
                val override = spec.penaltyFunction != null
                overrideCheckbox.isSelected = override
                penaltyEditor.isEnabled = override
                if (override) penaltyEditor.setValue(spec.penaltyFunction!!)
            }
        }
        refreshOkEnablement()

        pack()
        minimumSize = Dimension(520, 420)
        setLocationRelativeTo(owner)
    }

    fun showDialog(): ResponseConstraintSpec? {
        isVisible = true
        return result
    }

    // ── Layout builders ──────────────────────────────────────────────────

    private fun buildCenter(): JPanel = JPanel(GridBagLayout()).apply {
        border = BorderFactory.createEmptyBorder(10, 14, 10, 14)

        add(JLabel("Response:"), gbc(0, 0, anchor = GridBagConstraints.WEST))
        // [filter] [combo] composite so users can narrow long lists
        // of hierarchy-prefixed responses without scrolling.
        add(JPanel(BorderLayout(4, 0)).apply {
            isOpaque = false
            add(nameFilterField, BorderLayout.WEST)
            add(nameCombo, BorderLayout.CENTER)
        }, gbc(1, 0, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        add(JLabel("Inequality:"), gbc(0, 1, anchor = GridBagConstraints.WEST))
        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(leqRadio); add(geqRadio)
        }, gbc(1, 1, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        add(JLabel("RHS:"), gbc(0, 2, anchor = GridBagConstraints.WEST))
        add(rhsField, gbc(1, 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        add(JLabel("Target:"), gbc(0, 3, anchor = GridBagConstraints.WEST))
        add(targetField, gbc(1, 3, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        add(JLabel("Tolerance:"), gbc(0, 4, anchor = GridBagConstraints.WEST))
        add(toleranceField, gbc(1, 4, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        val help = JLabel(
            "<html><i>Target and tolerance are solver-specific cut-off parameters " +
                "used by some convergence tests; both default to 0.  Tolerance must " +
                "be ≥ 0.</i></html>"
        ).apply { foreground = Color(0x55, 0x55, 0x55) }
        add(help, gbc(0, 5, width = 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL,
            insets = Insets(4, 4, 8, 4)))

        // Override penalty section
        add(overrideCheckbox, gbc(0, 6, width = 2, anchor = GridBagConstraints.WEST,
            insets = Insets(8, 4, 2, 4)))
        add(penaltyEditor.apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Per-constraint penalty"),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)
            )
        }, gbc(0, 7, width = 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL,
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
        rhsField.document.addDocumentListener(docListener)
        targetField.document.addDocumentListener(docListener)
        toleranceField.document.addDocumentListener(docListener)
        nameCombo.addActionListener { refreshOkEnablement() }
        leqRadio.addActionListener { refreshOkEnablement() }
        geqRadio.addActionListener { refreshOkEnablement() }

        // Filter — rebuild the combo's items on every keystroke.
        nameFilterField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = refreshNameCombo()
            override fun removeUpdate(e: DocumentEvent?) = refreshNameCombo()
            override fun changedUpdate(e: DocumentEvent?) = refreshNameCombo()
        })
    }

    /** Re-populate the name combo from [sortedAvailableResponseNames]
     *  applying the current filter substring (case-insensitive).
     *  Preserves the current selection when it survives the filter. */
    private fun refreshNameCombo() {
        val filter = nameFilterField.text.trim().lowercase()
        val visible = if (filter.isEmpty()) sortedAvailableResponseNames
        else sortedAvailableResponseNames.filter { it.lowercase().contains(filter) }
        val previouslySelected = nameCombo.selectedItem as? String
        nameCombo.model = DefaultComboBoxModel(visible.toTypedArray())
        if (previouslySelected != null && previouslySelected in visible) {
            nameCombo.selectedItem = previouslySelected
        }
        refreshOkEnablement()
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

    private fun buildSpecOrNull(): ResponseConstraintSpec? {
        val name = (nameCombo.selectedItem as? String)?.takeIf { it.isNotBlank() } ?: return null
        val rhs = rhsField.text.trim().toDoubleOrNull() ?: return null
        val target = targetField.text.trim().toDoubleOrNull() ?: return null
        val tolerance = toleranceField.text.trim().toDoubleOrNull() ?: return null
        if (!rhs.isFinite() || !target.isFinite() || !tolerance.isFinite()) return null
        if (tolerance < 0.0) return null
        val ineq = if (geqRadio.isSelected) InequalityType.GREATER_THAN else InequalityType.LESS_THAN
        val penalty = if (overrideCheckbox.isSelected) penaltyEditor.value ?: return null else null
        return try {
            ResponseConstraintSpec(
                name = name,
                rhsValue = rhs,
                inequalityType = ineq,
                target = target,
                tolerance = tolerance,
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
        if (availableResponseNames.isEmpty()) return "Model has no non-objective responses to constrain"
        val name = (nameCombo.selectedItem as? String)?.takeIf { it.isNotBlank() }
            ?: return "Select a response name"
        val rhs = rhsField.text.trim().toDoubleOrNull() ?: return "RHS must be a number"
        if (!rhs.isFinite()) return "RHS must be finite"
        val target = targetField.text.trim().toDoubleOrNull() ?: return "Target must be a number"
        if (!target.isFinite()) return "Target must be finite"
        val tolerance = toleranceField.text.trim().toDoubleOrNull()
            ?: return "Tolerance must be a number"
        if (!tolerance.isFinite()) return "Tolerance must be finite"
        if (tolerance < 0.0) return "Tolerance must be ≥ 0"
        if (overrideCheckbox.isSelected) {
            val penaltyMsg = penaltyEditor.validationMessage()
            if (penaltyMsg != null) return "Penalty: $penaltyMsg"
        }
        // Suppress unused-variable warning on name.
        check(name.isNotBlank())
        return null
    }

    private fun gbc(
        col: Int,
        row: Int,
        weightx: Double = 0.0,
        width: Int = 1,
        anchor: Int = GridBagConstraints.CENTER,
        fill: Int = GridBagConstraints.NONE,
        insets: Insets = Insets(2, 4, 2, 4)
    ): GridBagConstraints = GridBagConstraints().apply {
        this.gridx = col
        this.gridy = row
        this.gridwidth = width
        this.weightx = weightx
        this.anchor = anchor
        this.fill = fill
        this.insets = insets
    }

    sealed class Mode {
        object Add : Mode()
        data class Edit(val index: Int, val spec: ResponseConstraintSpec) : Mode()
    }

    /** Same renderer used by `ProblemStepPanel` for the objective
     *  combo.  Splits hierarchy-prefixed response names at the last
     *  `:` so the leaf segment stands out from the prefix.  Tooltip
     *  shows the full path on hover. */
    private class ResponseNameCellRenderer : javax.swing.DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: javax.swing.JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): java.awt.Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val full = value as? String ?: return this
            val splitAt = full.lastIndexOf(':')
            if (splitAt < 0) {
                text = full
            } else {
                val prefix = full.substring(0, splitAt + 1)
                val leaf = full.substring(splitAt + 1)
                val color = if (isSelected) "white" else "#888888"
                text = "<html><span style='color:$color;'>$prefix</span>$leaf</html>"
            }
            toolTipText = full
            return this
        }
    }

    private companion object {
        private fun dialogTitleFor(mode: Mode): String = when (mode) {
            is Mode.Add -> "Add response constraint"
            is Mode.Edit -> "Edit response constraint"
        }
    }
}
