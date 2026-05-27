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
import java.awt.Component
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
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * Modal dialog for adding or editing one linear constraint over the
 * problem's decision variables.
 *
 * **Sparse entry.**  Models often have many decision variables but
 * each constraint typically involves only a few.  The dialog renders
 * a small table — one row per variable the user has explicitly added
 * — instead of a fixed row per declared variable.  The substrate
 * treats missing keys as coefficient 0, so the dialog's sparse map
 * is exact.
 *
 * **Equation preview.**  A read-only label renders the assembled
 * equation as the user edits coefficients, the inequality, and the
 * RHS — `1·reorderPoint + 2·reorderQuantity ≤ 100`.  Lets the user
 * verify what they're building before clicking OK.
 *
 * The "Add variable…" button opens a popup menu listing decision
 * variables not yet in the constraint; clicking one appends a row
 * with coefficient 1.0.  "Remove selected" removes the highlighted
 * row.  An optional per-constraint penalty function override is
 * available via the [PenaltyFunctionEditor] disclosure.
 *
 * Returns the [LinearConstraintSpec] on OK, or `null` on Cancel.
 *
 * @param owner parent window
 * @param declaredInputs the document's currently-declared decision
 *        variables; used to populate the "Add variable" picker
 * @param defaultLinearPenalty the problem-level default penalty;
 *        shown in the editor when the override checkbox is ticked
 * @param mode Add (fresh, empty table) or Edit (pre-populated)
 */
class LinearConstraintDialog(
    owner: Window?,
    private val declaredInputs: List<OptimizationInputSpec>,
    private val defaultLinearPenalty: PenaltyFunctionSpec,
    private val mode: Mode
) : JDialog(owner, dialogTitleFor(mode), ModalityType.APPLICATION_MODAL) {

    private var result: LinearConstraintSpec? = null

    // ── Coefficient table ─────────────────────────────────────────────────

    private val coeffTableModel = CoefficientTableModel()
    private val coeffTable = JTable(coeffTableModel).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        autoCreateRowSorter = false
        fillsViewportHeight = true
        rowHeight = 24
        // Wide name column; narrow coefficient column.
        columnModel.getColumn(0).preferredWidth = 280
        columnModel.getColumn(1).preferredWidth = 100
        // Tooltip renderer on the Variable column so long
        // hierarchy-prefixed names survive truncation.
        columnModel.getColumn(0).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable?, value: Any?, isSelected: Boolean,
                hasFocus: Boolean, row: Int, column: Int
            ): Component {
                val c = super.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, column
                )
                toolTipText = value?.toString()
                return c
            }
        }
    }

    private val addVariableButton = JButton("Add variable…")
    private val removeRowButton = JButton("Remove selected")

    // ── Inequality + RHS ──────────────────────────────────────────────────

    private val leqRadio = JRadioButton("≤  (less than)")
    private val geqRadio = JRadioButton("≥  (greater than)")
    private val rhsField = JTextField(12)

    // ── Equation preview ──────────────────────────────────────────────────

    private val previewLabel = JLabel(" ").apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        foreground = Color(0x33, 0x33, 0x33)
        toolTipText = "Equation currently being built; updates as you edit."
    }

    // ── Penalty override ──────────────────────────────────────────────────

    private val overrideCheckbox = JCheckBox("Override default penalty function")
    private val penaltyEditor = PenaltyFunctionEditor(
        initial = (mode as? Mode.Edit)?.spec?.penaltyFunction ?: defaultLinearPenalty,
        onChanged = { refreshOkEnablement() }
    )

    // ── Footer ────────────────────────────────────────────────────────────

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

        wireTableListeners()
        wireAddVariableButton()
        wireRemoveRowButton()
        wireFieldValidators()
        wireOverrideCheckbox()
        wireButtons()

        // Pre-populate from mode.
        when (mode) {
            is Mode.Add -> {
                rhsField.text = "0.0"
                overrideCheckbox.isSelected = false
                penaltyEditor.isEnabled = false
            }
            is Mode.Edit -> {
                val spec = mode.spec
                // Migrate spec.coefficients into the sparse table.  Zero-valued
                // entries are preserved (the user can [Remove selected] them).
                spec.coefficients.forEach { (name, value) ->
                    coeffTableModel.addRow(CoefficientRow(name, value))
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
        refreshAddVariableEnablement()
        refreshRemoveButtonEnablement()
        refreshPreview()
        refreshOkEnablement()

        pack()
        minimumSize = Dimension(620, 560)
        setLocationRelativeTo(owner)
    }

    fun showDialog(): LinearConstraintSpec? {
        isVisible = true
        return result
    }

    // ── Layout builders ──────────────────────────────────────────────────

    private fun buildCenter(): JPanel = JPanel(GridBagLayout()).apply {
        border = BorderFactory.createEmptyBorder(10, 14, 10, 14)

        // Coefficient table section
        add(JLabel("Variables in this constraint:").apply {
            font = font.deriveFont(Font.BOLD)
        }, gbc(0, 0, width = 2, anchor = GridBagConstraints.WEST))

        if (declaredInputs.isEmpty()) {
            add(JLabel(
                "<html><i>No decision variables declared.  Add at least one on the " +
                    "Problem step before creating a linear constraint.</i></html>"
            ).apply { foreground = Color(0xB5, 0x40, 0x40) },
                gbc(0, 1, width = 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL,
                    insets = Insets(4, 4, 8, 4)))
        } else {
            add(JScrollPane(
                coeffTable,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            ).apply { preferredSize = Dimension(440, 140) },
                gbc(0, 1, width = 2, weightx = 1.0, weighty = 1.0,
                    fill = GridBagConstraints.BOTH, insets = Insets(4, 4, 4, 4)))

            add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false
                add(addVariableButton)
                add(removeRowButton)
            }, gbc(0, 2, width = 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL,
                insets = Insets(0, 4, 8, 4)))
        }

        // Inequality row
        add(JLabel("Inequality:"), gbc(0, 3, anchor = GridBagConstraints.WEST))
        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(leqRadio); add(geqRadio)
        }, gbc(1, 3, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        // RHS row
        add(JLabel("RHS:"), gbc(0, 4, anchor = GridBagConstraints.WEST))
        add(rhsField, gbc(1, 4, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        // Equation preview
        add(JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Preview"),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)
            )
            add(previewLabel, BorderLayout.CENTER)
        }, gbc(0, 5, width = 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL,
            insets = Insets(8, 4, 8, 4)))

        // Override penalty section
        add(overrideCheckbox, gbc(0, 6, width = 2, anchor = GridBagConstraints.WEST,
            insets = Insets(4, 4, 2, 4)))
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

    private fun wireTableListeners() {
        coeffTable.selectionModel.addListSelectionListener { e ->
            if (e.valueIsAdjusting) return@addListSelectionListener
            refreshRemoveButtonEnablement()
        }
        coeffTableModel.addTableModelListener {
            refreshPreview()
            refreshAddVariableEnablement()
            refreshRemoveButtonEnablement()
            refreshOkEnablement()
        }
    }

    private fun wireAddVariableButton() {
        addVariableButton.addActionListener {
            val available = availableForAdd()
            if (available.isEmpty()) return@addActionListener
            val popup = JPopupMenu().apply {
                for (input in available) {
                    add(JMenuItem(input.name).apply {
                        toolTipText = input.name
                        addActionListener {
                            coeffTableModel.addRow(CoefficientRow(input.name, 1.0))
                            // Select the newly-added row.
                            val newRow = coeffTableModel.rowCount - 1
                            coeffTable.setRowSelectionInterval(newRow, newRow)
                        }
                    })
                }
            }
            popup.show(addVariableButton, 0, addVariableButton.height)
        }
    }

    private fun wireRemoveRowButton() {
        removeRowButton.addActionListener {
            val row = coeffTable.selectedRow
            if (row >= 0) coeffTableModel.removeRow(row)
        }
    }

    private fun wireFieldValidators() {
        val docListener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) { onAnyChange() }
            override fun removeUpdate(e: DocumentEvent?) { onAnyChange() }
            override fun changedUpdate(e: DocumentEvent?) { onAnyChange() }
        }
        rhsField.document.addDocumentListener(docListener)
        leqRadio.addActionListener { onAnyChange() }
        geqRadio.addActionListener { onAnyChange() }
    }

    private fun onAnyChange() {
        refreshPreview()
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

    private fun availableForAdd(): List<OptimizationInputSpec> {
        val taken = (0 until coeffTableModel.rowCount).map { coeffTableModel.getRow(it).name }.toSet()
        return declaredInputs.filter { it.name !in taken }
    }

    // ── Refresh ──────────────────────────────────────────────────────────

    private fun refreshAddVariableEnablement() {
        addVariableButton.isEnabled = availableForAdd().isNotEmpty()
    }

    private fun refreshRemoveButtonEnablement() {
        removeRowButton.isEnabled = coeffTable.selectedRow >= 0
    }

    private fun refreshPreview() {
        val rows = (0 until coeffTableModel.rowCount).map { coeffTableModel.getRow(it) }
        if (rows.isEmpty()) {
            previewLabel.text = "(add at least one variable)"
            return
        }
        val ineq = if (geqRadio.isSelected) "≥" else "≤"
        val rhs = rhsField.text.trim().ifBlank { "?" }
        // Build the LHS term by term so signs ("+" / "-") sit between
        // terms cleanly.  Leading minus stays attached to the first term.
        val lhs = rows.foldIndexed("") { i, acc, row ->
            val v = row.coefficient
            val leaf = row.name.substringAfterLast(':').substringAfterLast('.')
            val absV = if (v < 0.0) -v else v
            val term = if (absV == 1.0) leaf else "${formatCoefficient(absV)}·$leaf"
            val sign = when {
                i == 0 && v < 0.0 -> "-"
                i == 0 -> ""
                v < 0.0 -> " - "
                else -> " + "
            }
            acc + sign + term
        }
        previewLabel.text = "$lhs $ineq $rhs"
    }

    private fun formatCoefficient(v: Double): String {
        // Render integer doubles without trailing ".0" for compactness.
        return if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
    }

    // ── Validation ───────────────────────────────────────────────────────

    private fun buildSpecOrNull(): LinearConstraintSpec? {
        if (coeffTableModel.rowCount == 0) return null
        val coeffs = mutableMapOf<String, Double>()
        for (i in 0 until coeffTableModel.rowCount) {
            val row = coeffTableModel.getRow(i)
            if (!row.coefficient.isFinite()) return null
            coeffs[row.name] = row.coefficient
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
        if (coeffTableModel.rowCount == 0) return "Add at least one variable to the constraint"
        for (i in 0 until coeffTableModel.rowCount) {
            val row = coeffTableModel.getRow(i)
            if (!row.coefficient.isFinite()) return "Coefficient for '${row.name}' must be finite"
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

    /** One row in the sparse coefficient table. */
    private data class CoefficientRow(val name: String, var coefficient: Double)

    /** Two-column table model.  Variable column is read-only; the
     *  Coefficient column is editable as Double. */
    private class CoefficientTableModel : AbstractTableModel() {
        private val columns = arrayOf("Variable", "Coefficient")
        private val rows = mutableListOf<CoefficientRow>()

        fun addRow(row: CoefficientRow) {
            rows.add(row)
            fireTableRowsInserted(rows.size - 1, rows.size - 1)
        }

        fun removeRow(rowIndex: Int) {
            if (rowIndex !in rows.indices) return
            rows.removeAt(rowIndex)
            fireTableRowsDeleted(rowIndex, rowIndex)
        }

        fun getRow(rowIndex: Int): CoefficientRow = rows[rowIndex]

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]
        override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
            1 -> Double::class.javaObjectType
            else -> String::class.java
        }
        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnIndex == 1
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val r = rows[rowIndex]
            return when (columnIndex) {
                0 -> r.name
                1 -> r.coefficient
                else -> ""
            }
        }
        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            if (columnIndex != 1) return
            val parsed: Double? = when (aValue) {
                is Double -> aValue
                is Number -> aValue.toDouble()
                is String -> aValue.trim().toDoubleOrNull()
                else -> null
            }
            if (parsed != null && parsed.isFinite()) {
                rows[rowIndex] = rows[rowIndex].copy(coefficient = parsed)
                fireTableCellUpdated(rowIndex, columnIndex)
            }
        }
    }

    private companion object {
        private fun dialogTitleFor(mode: Mode): String = when (mode) {
            is Mode.Add -> "Add linear constraint"
            is Mode.Edit -> "Edit linear constraint"
        }
    }
}
