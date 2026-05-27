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

package ksl.app.swing.simopt.steps

import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import ksl.app.config.optimization.InequalityType
import ksl.app.config.optimization.LinearConstraintSpec
import ksl.app.config.optimization.PenaltyFunctionSpec
import ksl.app.config.optimization.ResponseConstraintSpec
import ksl.app.swing.common.notification.NotificationSeverity
import ksl.app.swing.simopt.SimoptAppController
import ksl.app.swing.simopt.problem.LinearConstraintDialog
import ksl.app.swing.simopt.problem.PenaltyFunctionEditor
import ksl.app.swing.simopt.problem.ResponseConstraintDialog
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel

/**
 *  *Constraints* step — Phase O5.  **Optional.**
 *
 *  Four sections stacked vertically inside a `JScrollPane`:
 *
 *  1. **Declared responses** — chip row listing the response names
 *     available to response constraints.  Objective is always implied
 *     and shown as a non-removable chip.  Adding from the model
 *     dropdown auto-grows the list; clicking × on a chip removes.
 *  2. **Response constraints** — table + Add / Edit / Delete / ↑ / ↓.
 *     The dialog's name combo lists every model response minus the
 *     objective; the controller auto-declares the chosen name on
 *     commit.
 *  3. **Linear constraints** — table + Add / Edit / Delete / ↑ / ↓.
 *     The dialog renders one coefficient field per declared decision
 *     variable.
 *  4. **Default penalty functions** — collapsed disclosure with two
 *     [PenaltyFunctionEditor] sub-panels (linear default + response
 *     default).  Edits are committed to the controller on every
 *     field change (preference-only — does not drop `lastResult`).
 *
 *  The step is reachable as soon as Problem is complete and is
 *  marked complete in the rail from that moment — adding constraints
 *  is opt-in, and the Algorithm step does not block on this step
 *  having content.
 */
class ConstraintsStepPanel(
    private val controller: SimoptAppController,
    private val onMessage: (String, NotificationSeverity) -> Unit = { _, _ -> }
) : JPanel(BorderLayout()) {

    // ── Response constraints table ────────────────────────────────────────

    private val responseTableModel = ResponseConstraintsTableModel()
    private val responseTable = JTable(responseTableModel).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        autoCreateRowSorter = false
        fillsViewportHeight = true
        rowHeight = 22
    }
    private val rcAddButton = JButton("Add…")
    private val rcEditButton = JButton("Edit")
    private val rcDeleteButton = JButton("Delete")
    private val rcUpButton = JButton("↑")
    private val rcDownButton = JButton("↓")

    // ── Linear constraints table ──────────────────────────────────────────

    private val linearTableModel = LinearConstraintsTableModel()
    private val linearTable = JTable(linearTableModel).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        autoCreateRowSorter = false
        fillsViewportHeight = true
        rowHeight = 22
    }
    private val lcAddButton = JButton("Add…")
    private val lcEditButton = JButton("Edit")
    private val lcDeleteButton = JButton("Delete")
    private val lcUpButton = JButton("↑")
    private val lcDownButton = JButton("↓")

    // ── Declared-responses chip row ───────────────────────────────────────

    private val chipsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4))
    private val addResponseCombo: JComboBox<String> = JComboBox()

    // ── Penalty defaults disclosure ───────────────────────────────────────

    private val penaltyToggle = JLabel("▸ Default penalty functions (advanced)").apply {
        foreground = Color(0x33, 0x55, 0x88)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = font.deriveFont(Font.PLAIN, 12f)
    }
    private val penaltyBodyPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isVisible = false
    }
    private val linearDefaultEditor = PenaltyFunctionEditor(
        initial = controller.defaultLinearPenalty.value,
        onChanged = { spec -> if (spec != null) controller.setDefaultLinearPenalty(spec) }
    ).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Default — linear constraints"),
            BorderFactory.createEmptyBorder(2, 6, 2, 6)
        )
    }
    private val responseDefaultEditor = PenaltyFunctionEditor(
        initial = controller.defaultResponsePenalty.value,
        onChanged = { spec -> if (spec != null) controller.setDefaultResponsePenalty(spec) }
    ).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Default — response constraints"),
            BorderFactory.createEmptyBorder(2, 6, 2, 6)
        )
    }

    @Volatile private var suppressEvents = false

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        // Empty-state banner at top
        val banner = JLabel(
            "<html><i>Constraints are optional.  Add response or linear constraints below, " +
                "or click <b>Next: Algorithm</b> on the footer to skip this step.</i></html>"
        ).apply {
            foreground = Color(0x55, 0x55, 0x55)
            border = BorderFactory.createEmptyBorder(4, 8, 8, 8)
        }
        add(banner, BorderLayout.NORTH)

        // Vertical scrollable stack of sections
        val stack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        stack.add(buildDeclaredResponsesSection())
        stack.add(Box.createVerticalStrut(8))
        stack.add(buildResponseConstraintsSection())
        stack.add(Box.createVerticalStrut(8))
        stack.add(buildLinearConstraintsSection())
        stack.add(Box.createVerticalStrut(8))
        stack.add(buildPenaltyDefaultsSection())
        stack.add(Box.createVerticalGlue())

        add(JScrollPane(
            stack,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        ), BorderLayout.CENTER)

        wireResponseConstraintsButtons()
        wireLinearConstraintsButtons()
        wireAddResponseCombo()
        wirePenaltyDisclosure()
        wireCollectors()

        // Initial render
        refreshResponseConstraintsTable()
        refreshLinearConstraintsTable()
        rebuildChipRow()
        refreshAddResponseCombo()
        refreshAllButtonEnablement()
    }

    // ── Section builders ──────────────────────────────────────────────────

    private fun buildDeclaredResponsesSection(): JPanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Declared responses"),
            BorderFactory.createEmptyBorder(2, 6, 6, 6)
        )
        add(chipsPanel, BorderLayout.CENTER)
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4))
        row.add(JLabel("Add response: "))
        row.add(addResponseCombo)
        add(row, BorderLayout.SOUTH)
    }

    private fun buildResponseConstraintsSection(): JPanel = JPanel(BorderLayout(0, 6)).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Response constraints"),
            BorderFactory.createEmptyBorder(2, 6, 6, 6)
        )
        add(JScrollPane(
            responseTable,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        ).apply { preferredSize = java.awt.Dimension(640, 130) }, BorderLayout.CENTER)
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(rcAddButton); add(Box.createHorizontalStrut(6))
            add(rcEditButton); add(Box.createHorizontalStrut(6))
            add(rcDeleteButton); add(Box.createHorizontalStrut(16))
            add(rcUpButton); add(Box.createHorizontalStrut(2))
            add(rcDownButton)
            add(Box.createHorizontalGlue())
        }, BorderLayout.SOUTH)
    }

    private fun buildLinearConstraintsSection(): JPanel = JPanel(BorderLayout(0, 6)).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Linear constraints"),
            BorderFactory.createEmptyBorder(2, 6, 6, 6)
        )
        add(JScrollPane(
            linearTable,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        ).apply { preferredSize = java.awt.Dimension(640, 130) }, BorderLayout.CENTER)
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(lcAddButton); add(Box.createHorizontalStrut(6))
            add(lcEditButton); add(Box.createHorizontalStrut(6))
            add(lcDeleteButton); add(Box.createHorizontalStrut(16))
            add(lcUpButton); add(Box.createHorizontalStrut(2))
            add(lcDownButton)
            add(Box.createHorizontalGlue())
        }, BorderLayout.SOUTH)
    }

    private fun buildPenaltyDefaultsSection(): JPanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createEmptyBorder(2, 4, 2, 4)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(penaltyToggle) }, BorderLayout.NORTH)
        penaltyBodyPanel.add(linearDefaultEditor)
        penaltyBodyPanel.add(Box.createVerticalStrut(8))
        penaltyBodyPanel.add(responseDefaultEditor)
        val help = JLabel(
            "<html><i>Defaults apply to any constraint that does not carry its own " +
                "penalty-function override.</i></html>"
        ).apply {
            foreground = Color(0x55, 0x55, 0x55)
            border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
        }
        penaltyBodyPanel.add(help)
        add(penaltyBodyPanel, BorderLayout.CENTER)
    }

    // ── Wiring ───────────────────────────────────────────────────────────

    private fun wireAddResponseCombo() {
        addResponseCombo.addActionListener {
            if (suppressEvents) return@addActionListener
            val choice = addResponseCombo.selectedItem as? String ?: return@addActionListener
            if (choice == ADD_PROMPT) return@addActionListener
            try {
                controller.addResponseName(choice)
            } catch (ex: IllegalArgumentException) {
                onMessage(ex.message ?: "Could not declare response", NotificationSeverity.ERROR)
            }
        }
    }

    private fun wireResponseConstraintsButtons() {
        responseTable.selectionModel.addListSelectionListener { e ->
            if (e.valueIsAdjusting || suppressEvents) return@addListSelectionListener
            controller.setSelectedResponseConstraintIndex(responseTable.selectedRow)
            refreshAllButtonEnablement()
        }
        rcAddButton.addActionListener { handleAddResponseConstraint() }
        rcEditButton.addActionListener { handleEditResponseConstraint() }
        rcDeleteButton.addActionListener { handleDeleteResponseConstraint() }
        rcUpButton.addActionListener {
            val idx = controller.selectedResponseConstraintIndex.value
            if (idx > 0) controller.moveResponseConstraintUp(idx)
        }
        rcDownButton.addActionListener {
            val idx = controller.selectedResponseConstraintIndex.value
            if (idx >= 0 && idx < controller.responseConstraints.value.lastIndex) {
                controller.moveResponseConstraintDown(idx)
            }
        }
    }

    private fun wireLinearConstraintsButtons() {
        linearTable.selectionModel.addListSelectionListener { e ->
            if (e.valueIsAdjusting || suppressEvents) return@addListSelectionListener
            controller.setSelectedLinearConstraintIndex(linearTable.selectedRow)
            refreshAllButtonEnablement()
        }
        lcAddButton.addActionListener { handleAddLinearConstraint() }
        lcEditButton.addActionListener { handleEditLinearConstraint() }
        lcDeleteButton.addActionListener { handleDeleteLinearConstraint() }
        lcUpButton.addActionListener {
            val idx = controller.selectedLinearConstraintIndex.value
            if (idx > 0) controller.moveLinearConstraintUp(idx)
        }
        lcDownButton.addActionListener {
            val idx = controller.selectedLinearConstraintIndex.value
            if (idx >= 0 && idx < controller.linearConstraints.value.lastIndex) {
                controller.moveLinearConstraintDown(idx)
            }
        }
    }

    private fun wirePenaltyDisclosure() {
        penaltyToggle.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                penaltyBodyPanel.isVisible = !penaltyBodyPanel.isVisible
                penaltyToggle.text = if (penaltyBodyPanel.isVisible)
                    "▾ Default penalty functions (advanced)"
                else "▸ Default penalty functions (advanced)"
                revalidate()
                repaint()
            }
        })
    }

    private fun wireCollectors() {
        controller.responseNames.onEach { _ ->
            rebuildChipRow()
            refreshAddResponseCombo()
        }.launchIn(controller.edtScope)

        controller.currentModelDescriptor.onEach { _ ->
            refreshAddResponseCombo()
        }.launchIn(controller.edtScope)

        controller.objectiveResponseName.onEach { _ ->
            rebuildChipRow()
            refreshAddResponseCombo()
        }.launchIn(controller.edtScope)

        controller.responseConstraints.onEach { _ ->
            refreshResponseConstraintsTable()
            refreshAllButtonEnablement()
        }.launchIn(controller.edtScope)

        controller.selectedResponseConstraintIndex.onEach { idx ->
            if (responseTable.selectedRow != idx) {
                suppressEvents = true
                try {
                    if (idx in 0 until responseTableModel.rowCount) {
                        responseTable.setRowSelectionInterval(idx, idx)
                    } else {
                        responseTable.clearSelection()
                    }
                } finally { suppressEvents = false }
            }
            refreshAllButtonEnablement()
        }.launchIn(controller.edtScope)

        controller.linearConstraints.onEach { _ ->
            refreshLinearConstraintsTable()
            refreshAllButtonEnablement()
        }.launchIn(controller.edtScope)

        controller.selectedLinearConstraintIndex.onEach { idx ->
            if (linearTable.selectedRow != idx) {
                suppressEvents = true
                try {
                    if (idx in 0 until linearTableModel.rowCount) {
                        linearTable.setRowSelectionInterval(idx, idx)
                    } else {
                        linearTable.clearSelection()
                    }
                } finally { suppressEvents = false }
            }
            refreshAllButtonEnablement()
        }.launchIn(controller.edtScope)

        controller.inputs.onEach { _ ->
            refreshLinearConstraintsTable()  // constraints render references to input names
            refreshAllButtonEnablement()
        }.launchIn(controller.edtScope)

        controller.defaultLinearPenalty.onEach { spec ->
            // Avoid keystroke-induced loop: skip rebuild while the editor itself is focused.
            if (linearDefaultEditor.value != spec) linearDefaultEditor.setValue(spec)
        }.launchIn(controller.edtScope)

        controller.defaultResponsePenalty.onEach { spec ->
            if (responseDefaultEditor.value != spec) responseDefaultEditor.setValue(spec)
        }.launchIn(controller.edtScope)
    }

    // ── Refresh ──────────────────────────────────────────────────────────

    private fun refreshResponseConstraintsTable() {
        suppressEvents = true
        try {
            responseTableModel.setRows(controller.responseConstraints.value)
            val sel = controller.selectedResponseConstraintIndex.value
            if (sel in 0 until responseTableModel.rowCount) {
                responseTable.setRowSelectionInterval(sel, sel)
            } else {
                responseTable.clearSelection()
            }
        } finally { suppressEvents = false }
    }

    private fun refreshLinearConstraintsTable() {
        suppressEvents = true
        try {
            linearTableModel.setRows(controller.linearConstraints.value)
            val sel = controller.selectedLinearConstraintIndex.value
            if (sel in 0 until linearTableModel.rowCount) {
                linearTable.setRowSelectionInterval(sel, sel)
            } else {
                linearTable.clearSelection()
            }
        } finally { suppressEvents = false }
    }

    private fun rebuildChipRow() {
        chipsPanel.removeAll()
        // Objective chip (non-removable)
        val objName = controller.objectiveResponseName.value
        if (objName != null) {
            chipsPanel.add(buildChip("$objName  (objective, implied)", removable = false))
        }
        // Declared response chips
        for (name in controller.responseNames.value) {
            chipsPanel.add(buildChip("$name  ×", removable = true, onClick = {
                controller.removeResponseName(name)
            }))
        }
        chipsPanel.revalidate()
        chipsPanel.repaint()
    }

    private fun buildChip(
        text: String,
        removable: Boolean,
        onClick: () -> Unit = {}
    ): JButton = JButton(text).apply {
        isFocusable = false
        isOpaque = true
        font = font.deriveFont(Font.PLAIN, 12f)
        margin = java.awt.Insets(2, 8, 2, 8)
        if (removable) {
            background = Color(0xEE, 0xF2, 0xF8)
            border = BorderFactory.createLineBorder(Color(0xC2, 0xD0, 0xE6))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener { onClick() }
        } else {
            background = Color(0xF5, 0xF5, 0xF5)
            border = BorderFactory.createLineBorder(Color(0xDD, 0xDD, 0xDD))
            isEnabled = false
        }
    }

    private fun refreshAddResponseCombo() {
        suppressEvents = true
        try {
            val descriptor = controller.currentModelDescriptor.value
            val all = descriptor?.responseNames?.toList()?.sorted().orEmpty()
            val objective = controller.objectiveResponseName.value
            val declared = controller.responseNames.value.toSet()
            val available = all.filter { it != objective && it !in declared }
            val items = mutableListOf<String>().apply {
                add(ADD_PROMPT)
                addAll(available)
            }
            addResponseCombo.model = javax.swing.DefaultComboBoxModel(items.toTypedArray())
            addResponseCombo.selectedItem = ADD_PROMPT
            addResponseCombo.isEnabled = available.isNotEmpty()
        } finally { suppressEvents = false }
    }

    private fun refreshAllButtonEnablement() {
        val rcSel = controller.selectedResponseConstraintIndex.value
        val rcList = controller.responseConstraints.value
        val rcHas = rcSel in rcList.indices
        rcEditButton.isEnabled = rcHas
        rcDeleteButton.isEnabled = rcHas
        rcUpButton.isEnabled = rcHas && rcSel > 0
        rcDownButton.isEnabled = rcHas && rcSel < rcList.lastIndex
        rcAddButton.isEnabled = (controller.currentModelDescriptor.value?.responseNames?.let { resps ->
            val obj = controller.objectiveResponseName.value
            resps.any { it != obj }
        } == true)

        val lcSel = controller.selectedLinearConstraintIndex.value
        val lcList = controller.linearConstraints.value
        val lcHas = lcSel in lcList.indices
        lcEditButton.isEnabled = lcHas
        lcDeleteButton.isEnabled = lcHas
        lcUpButton.isEnabled = lcHas && lcSel > 0
        lcDownButton.isEnabled = lcHas && lcSel < lcList.lastIndex
        lcAddButton.isEnabled = controller.inputs.value.isNotEmpty()
    }

    // ── Button handlers ──────────────────────────────────────────────────

    private fun handleAddResponseConstraint() {
        val available = availableResponseNamesForDialog()
        if (available.isEmpty()) {
            onMessage(
                "No non-objective responses available to constrain on this model.",
                NotificationSeverity.WARNING
            )
            return
        }
        val ownerWindow = SwingUtilities.getWindowAncestor(this)
        val dialog = ResponseConstraintDialog(
            owner = ownerWindow,
            availableResponseNames = available,
            defaultResponsePenalty = controller.defaultResponsePenalty.value,
            mode = ResponseConstraintDialog.Mode.Add
        )
        val spec = dialog.showDialog() ?: return
        try {
            controller.addResponseConstraint(spec)
        } catch (ex: IllegalArgumentException) {
            onMessage(ex.message ?: "Could not add response constraint", NotificationSeverity.ERROR)
        }
    }

    private fun handleEditResponseConstraint() {
        val idx = controller.selectedResponseConstraintIndex.value
        val list = controller.responseConstraints.value
        if (idx !in list.indices) return
        val ownerWindow = SwingUtilities.getWindowAncestor(this)
        val dialog = ResponseConstraintDialog(
            owner = ownerWindow,
            availableResponseNames = availableResponseNamesForDialog(),
            defaultResponsePenalty = controller.defaultResponsePenalty.value,
            mode = ResponseConstraintDialog.Mode.Edit(idx, list[idx])
        )
        val updated = dialog.showDialog() ?: return
        try {
            controller.updateResponseConstraint(idx, updated)
        } catch (ex: IllegalArgumentException) {
            onMessage(ex.message ?: "Could not update response constraint", NotificationSeverity.ERROR)
        }
    }

    private fun handleDeleteResponseConstraint() {
        val idx = controller.selectedResponseConstraintIndex.value
        val list = controller.responseConstraints.value
        if (idx !in list.indices) return
        val name = list[idx].name
        val choice = JOptionPane.showConfirmDialog(
            this,
            "Delete response constraint on '$name'?",
            "Delete Response Constraint",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        if (choice == JOptionPane.YES_OPTION) controller.deleteResponseConstraint(idx)
    }

    private fun availableResponseNamesForDialog(): List<String> {
        val descriptor = controller.currentModelDescriptor.value ?: return emptyList()
        val obj = controller.objectiveResponseName.value
        return descriptor.responseNames.filter { it != obj }.sorted()
    }

    private fun handleAddLinearConstraint() {
        if (controller.inputs.value.isEmpty()) {
            onMessage(
                "Declare at least one decision variable on the Problem step first.",
                NotificationSeverity.WARNING
            )
            return
        }
        val ownerWindow = SwingUtilities.getWindowAncestor(this)
        val dialog = LinearConstraintDialog(
            owner = ownerWindow,
            declaredInputs = controller.inputs.value,
            defaultLinearPenalty = controller.defaultLinearPenalty.value,
            mode = LinearConstraintDialog.Mode.Add
        )
        val spec = dialog.showDialog() ?: return
        try {
            controller.addLinearConstraint(spec)
        } catch (ex: IllegalArgumentException) {
            onMessage(ex.message ?: "Could not add linear constraint", NotificationSeverity.ERROR)
        }
    }

    private fun handleEditLinearConstraint() {
        val idx = controller.selectedLinearConstraintIndex.value
        val list = controller.linearConstraints.value
        if (idx !in list.indices) return
        val ownerWindow = SwingUtilities.getWindowAncestor(this)
        val dialog = LinearConstraintDialog(
            owner = ownerWindow,
            declaredInputs = controller.inputs.value,
            defaultLinearPenalty = controller.defaultLinearPenalty.value,
            mode = LinearConstraintDialog.Mode.Edit(idx, list[idx])
        )
        val updated = dialog.showDialog() ?: return
        try {
            controller.updateLinearConstraint(idx, updated)
        } catch (ex: IllegalArgumentException) {
            onMessage(ex.message ?: "Could not update linear constraint", NotificationSeverity.ERROR)
        }
    }

    private fun handleDeleteLinearConstraint() {
        val idx = controller.selectedLinearConstraintIndex.value
        val list = controller.linearConstraints.value
        if (idx !in list.indices) return
        val choice = JOptionPane.showConfirmDialog(
            this,
            "Delete linear constraint #${idx + 1}?",
            "Delete Linear Constraint",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        if (choice == JOptionPane.YES_OPTION) controller.deleteLinearConstraint(idx)
    }

    // ── Table models ─────────────────────────────────────────────────────

    private class ResponseConstraintsTableModel : AbstractTableModel() {
        private val columns = arrayOf("Name", "Inequality", "RHS", "Target", "Tolerance", "Penalty")
        private var rows: List<ResponseConstraintSpec> = emptyList()

        fun setRows(newRows: List<ResponseConstraintSpec>) {
            rows = newRows
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]
        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val r = rows[rowIndex]
            return when (columnIndex) {
                0 -> r.name
                1 -> when (r.inequalityType) {
                    InequalityType.LESS_THAN -> "≤"
                    InequalityType.GREATER_THAN -> "≥"
                }
                2 -> r.rhsValue
                3 -> r.target
                4 -> r.tolerance
                5 -> renderPenalty(r.penaltyFunction)
                else -> ""
            }
        }
    }

    private class LinearConstraintsTableModel : AbstractTableModel() {
        private val columns = arrayOf("Constraint", "Inequality", "RHS", "Penalty")
        private var rows: List<LinearConstraintSpec> = emptyList()

        fun setRows(newRows: List<LinearConstraintSpec>) {
            rows = newRows
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]
        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val r = rows[rowIndex]
            return when (columnIndex) {
                0 -> renderCoefficients(r.coefficients)
                1 -> when (r.inequalityType) {
                    InequalityType.LESS_THAN -> "≤"
                    InequalityType.GREATER_THAN -> "≥"
                }
                2 -> r.rhsValue
                3 -> renderPenalty(r.penaltyFunction)
                else -> ""
            }
        }

        private fun renderCoefficients(coeffs: Map<String, Double>): String {
            val nonZero = coeffs.entries.filter { it.value != 0.0 }
            if (nonZero.isEmpty()) return "(all zero)"
            return nonZero.joinToString(" + ") { (name, value) ->
                if (value == 1.0) name
                else if (value == -1.0) "-$name"
                else "${value}·$name"
            }.replace("+ -", "- ")
        }
    }

    private companion object {
        const val ADD_PROMPT: String = "(pick a response…)"

        fun renderPenalty(p: PenaltyFunctionSpec?): String = when (p) {
            null -> "(default)"
            is PenaltyFunctionSpec.WithMemory ->
                "WithMemory(C=${p.basePenalty}, β=${p.iterationExponent}, α=${p.violationExponent})"
            is PenaltyFunctionSpec.DynamicPolynomial ->
                "Dynamic(C=${p.basePenalty}, β=${p.iterationExponent}, α=${p.violationExponent})"
        }
    }
}
