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
import ksl.app.config.optimization.OptimizationInputSpec
import ksl.app.config.optimization.OptimizationType
import ksl.app.swing.common.notification.NotificationSeverity
import ksl.app.swing.simopt.SimoptAppController
import ksl.app.swing.simopt.problem.InputEditorDialog
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.ListSelectionEvent
import javax.swing.table.AbstractTableModel

/**
 *  *Problem* step — Phase O4.
 *
 *  Two sections:
 *
 *  1. **Objective form (top)** — response dropdown (from the model's
 *     introspection), direction radio, optional problem name,
 *     indifference-zone parameter, objective granularity.
 *  2. **Decision-variables table (center)** — read-only table of the
 *     current inputs with Add / Edit / Delete / ↑ / ↓ buttons.
 *
 *  Constraints + declared response-names list + penalty defaults land
 *  in Phase O5 — those sections are not present in this panel yet.
 *
 *  Selecting a model is a prerequisite for the Problem step
 *  (the step itself is locked otherwise on the rail), so this panel
 *  assumes [SimoptAppController.currentModelDescriptor] is non-null
 *  during normal use.  If a TOML is loaded with a bundle ref whose
 *  bundle isn't loaded, the dropdown will simply be empty until the
 *  user resolves it on the Model step.
 */
class ProblemStepPanel(
    private val controller: SimoptAppController,
    private val onMessage: (String, NotificationSeverity) -> Unit = { _, _ -> }
) : JPanel(BorderLayout()) {

    // ── Objective widgets ─────────────────────────────────────────────────

    private val objectiveCombo: JComboBox<String> = JComboBox()
    private val minimizeRadio = JRadioButton("Minimize").apply { isSelected = true }
    private val maximizeRadio = JRadioButton("Maximize")
    private val problemNameField = JTextField(24)
    private val deltaField = JTextField(10)
    private val granularityField = JTextField(10)

    // ── Decision-variables widgets ────────────────────────────────────────

    private val inputsTableModel = InputsTableModel()
    private val inputsTable = JTable(inputsTableModel).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        autoCreateRowSorter = false
        fillsViewportHeight = true
        rowHeight = 22
    }

    private val addButton = JButton("Add input…")
    private val editButton = JButton("Edit")
    private val deleteButton = JButton("Delete")
    private val upButton = JButton("↑")
    private val downButton = JButton("↓")

    // ── Guards ─────────────────────────────────────────────────────────────

    @Volatile private var suppressEvents: Boolean = false

    init {
        border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
        ButtonGroup().apply {
            add(minimizeRadio); add(maximizeRadio)
        }

        add(buildObjectivePanel(), BorderLayout.NORTH)
        add(buildInputsPanel(), BorderLayout.CENTER)

        wireObjectiveWidgets()
        wireInputsWidgets()
        wireCollectors()

        // Initial render from current controller state.
        refreshObjectiveCombo()
        refreshObjectiveFields()
        refreshInputsTable()
        refreshButtonEnablement()
    }

    // ── Layout builders ────────────────────────────────────────────────────

    private fun buildObjectivePanel(): JPanel = JPanel(GridBagLayout()).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Objective"),
            BorderFactory.createEmptyBorder(2, 6, 6, 6)
        )

        add(JLabel("Response:"), gbc(0, 0, anchor = GridBagConstraints.WEST,
            insets = Insets(2, 4, 2, 8)))
        add(objectiveCombo, gbc(1, 0, weightx = 0.6, fill = GridBagConstraints.HORIZONTAL))
        add(JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JLabel("Direction: "))
            add(minimizeRadio); add(Box.createHorizontalStrut(4))
            add(maximizeRadio)
        }, gbc(2, 0, weightx = 0.4, fill = GridBagConstraints.HORIZONTAL,
            insets = Insets(2, 16, 2, 4)))

        add(JLabel("Problem name:"), gbc(0, 1, anchor = GridBagConstraints.WEST,
            insets = Insets(2, 4, 2, 8)))
        add(problemNameField, gbc(1, 1, width = 2, weightx = 1.0,
            fill = GridBagConstraints.HORIZONTAL))

        add(JLabel("Indifference zone (Δ):"), gbc(0, 2, anchor = GridBagConstraints.WEST,
            insets = Insets(2, 4, 2, 8)))
        add(deltaField, gbc(1, 2, weightx = 0.5, fill = GridBagConstraints.HORIZONTAL))
        add(JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JLabel("Objective granularity: "))
            add(granularityField)
        }, gbc(2, 2, weightx = 0.5, fill = GridBagConstraints.HORIZONTAL,
            insets = Insets(2, 16, 2, 4)))

        val help = JLabel(
            "<html><i>Δ is the smallest objective-function difference considered " +
                "practically meaningful.  Objective granularity rounds the objective; " +
                "0.0 means full precision.  Both must be ≥ 0.</i></html>"
        ).apply { foreground = Color(0x55, 0x55, 0x55) }
        add(help, gbc(0, 3, width = 3, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL,
            insets = Insets(6, 4, 2, 4)))
    }

    private fun buildInputsPanel(): JPanel = JPanel(BorderLayout(0, 8)).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Decision variables"),
            BorderFactory.createEmptyBorder(2, 6, 6, 6)
        )

        add(JScrollPane(
            inputsTable,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        ), BorderLayout.CENTER)

        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(addButton); add(Box.createHorizontalStrut(6))
            add(editButton); add(Box.createHorizontalStrut(6))
            add(deleteButton); add(Box.createHorizontalStrut(16))
            add(upButton); add(Box.createHorizontalStrut(2))
            add(downButton)
            add(Box.createHorizontalGlue())
        }, BorderLayout.SOUTH)
    }

    // ── Wiring ─────────────────────────────────────────────────────────────

    private fun wireObjectiveWidgets() {
        objectiveCombo.addActionListener {
            if (suppressEvents) return@addActionListener
            val name = (objectiveCombo.selectedItem as? String)?.takeIf { it.isNotBlank() }
            controller.setObjectiveResponseName(name)
        }
        minimizeRadio.addActionListener {
            if (suppressEvents) return@addActionListener
            if (minimizeRadio.isSelected) controller.setOptimizationType(OptimizationType.MINIMIZE)
        }
        maximizeRadio.addActionListener {
            if (suppressEvents) return@addActionListener
            if (maximizeRadio.isSelected) controller.setOptimizationType(OptimizationType.MAXIMIZE)
        }

        problemNameField.addActionListener { commitProblemName() }
        problemNameField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent) { commitProblemName() }
        })
        deltaField.addActionListener { commitDelta() }
        deltaField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent) { commitDelta() }
        })
        granularityField.addActionListener { commitGranularity() }
        granularityField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent) { commitGranularity() }
        })
    }

    private fun commitProblemName() {
        if (suppressEvents) return
        controller.setProblemName(problemNameField.text.takeIf { it.isNotBlank() })
    }

    private fun commitDelta() {
        if (suppressEvents) return
        val parsed = deltaField.text.trim().toDoubleOrNull()
        if (parsed != null) {
            try {
                controller.setIndifferenceZoneParameter(parsed)
            } catch (ex: IllegalArgumentException) {
                onMessage(ex.message ?: "Invalid Δ", NotificationSeverity.WARNING)
            }
        }
        refreshDeltaField()
    }

    private fun commitGranularity() {
        if (suppressEvents) return
        val parsed = granularityField.text.trim().toDoubleOrNull()
        if (parsed != null) {
            try {
                controller.setObjectiveGranularity(parsed)
            } catch (ex: IllegalArgumentException) {
                onMessage(ex.message ?: "Invalid granularity", NotificationSeverity.WARNING)
            }
        }
        refreshGranularityField()
    }

    private fun wireInputsWidgets() {
        inputsTable.selectionModel.addListSelectionListener { e: ListSelectionEvent ->
            if (e.valueIsAdjusting) return@addListSelectionListener
            if (suppressEvents) return@addListSelectionListener
            controller.setSelectedInputIndex(inputsTable.selectedRow)
            refreshButtonEnablement()
        }
        addButton.addActionListener { handleAdd() }
        editButton.addActionListener { handleEdit() }
        deleteButton.addActionListener { handleDelete() }
        upButton.addActionListener { handleMoveUp() }
        downButton.addActionListener { handleMoveDown() }
    }

    private fun wireCollectors() {
        controller.currentModelDescriptor.onEach { _ ->
            refreshObjectiveCombo()
            refreshObjectiveFields()
            // Add Input depends on the descriptor being non-null; without
            // this call, the button stays disabled forever after the user
            // selects a model on the Model step (the descriptor collector
            // fires, but enablement wasn't being recomputed here).
            refreshButtonEnablement()
        }.launchIn(controller.edtScope)

        controller.objectiveResponseName.onEach { _ ->
            refreshObjectiveCombo()
        }.launchIn(controller.edtScope)

        controller.optimizationType.onEach { _ -> refreshObjectiveFields() }
            .launchIn(controller.edtScope)
        controller.problemName.onEach { _ -> refreshProblemNameField() }
            .launchIn(controller.edtScope)
        controller.indifferenceZoneParameter.onEach { _ -> refreshDeltaField() }
            .launchIn(controller.edtScope)
        controller.objectiveGranularity.onEach { _ -> refreshGranularityField() }
            .launchIn(controller.edtScope)
        controller.inputs.onEach { _ ->
            refreshInputsTable()
            refreshButtonEnablement()
        }.launchIn(controller.edtScope)
        controller.selectedInputIndex.onEach { idx ->
            if (inputsTable.selectedRow != idx) {
                suppressEvents = true
                try {
                    if (idx in 0 until inputsTableModel.rowCount) {
                        inputsTable.setRowSelectionInterval(idx, idx)
                    } else {
                        inputsTable.clearSelection()
                    }
                } finally { suppressEvents = false }
            }
            refreshButtonEnablement()
        }.launchIn(controller.edtScope)
    }

    // ── Refresh ─────────────────────────────────────────────────────────────

    private fun refreshObjectiveCombo() {
        suppressEvents = true
        try {
            val descriptor = controller.currentModelDescriptor.value
            val names = descriptor?.responseNames?.toList()?.sorted() ?: emptyList()
            val model = DefaultComboBoxModel(names.toTypedArray())
            objectiveCombo.model = model
            val current = controller.objectiveResponseName.value
            objectiveCombo.selectedItem = if (current != null && current in names) current else null
            objectiveCombo.isEnabled = names.isNotEmpty()
        } finally { suppressEvents = false }
    }

    private fun refreshObjectiveFields() {
        suppressEvents = true
        try {
            when (controller.optimizationType.value) {
                OptimizationType.MINIMIZE -> minimizeRadio.isSelected = true
                OptimizationType.MAXIMIZE -> maximizeRadio.isSelected = true
            }
        } finally { suppressEvents = false }
        refreshProblemNameField()
        refreshDeltaField()
        refreshGranularityField()
    }

    private fun refreshProblemNameField() {
        if (problemNameField.hasFocus()) return
        suppressEvents = true
        try {
            problemNameField.text = controller.problemName.value.orEmpty()
        } finally { suppressEvents = false }
    }

    private fun refreshDeltaField() {
        if (deltaField.hasFocus()) return
        suppressEvents = true
        try {
            deltaField.text = controller.indifferenceZoneParameter.value.toString()
        } finally { suppressEvents = false }
    }

    private fun refreshGranularityField() {
        if (granularityField.hasFocus()) return
        suppressEvents = true
        try {
            granularityField.text = controller.objectiveGranularity.value.toString()
        } finally { suppressEvents = false }
    }

    private fun refreshInputsTable() {
        suppressEvents = true
        try {
            inputsTableModel.setRows(controller.inputs.value)
            val selected = controller.selectedInputIndex.value
            if (selected in 0 until inputsTableModel.rowCount) {
                inputsTable.setRowSelectionInterval(selected, selected)
            } else {
                inputsTable.clearSelection()
            }
        } finally { suppressEvents = false }
    }

    private fun refreshButtonEnablement() {
        val selected = controller.selectedInputIndex.value
        val list = controller.inputs.value
        val hasSelection = selected in list.indices
        editButton.isEnabled = hasSelection
        deleteButton.isEnabled = hasSelection
        upButton.isEnabled = hasSelection && selected > 0
        downButton.isEnabled = hasSelection && selected < list.lastIndex
        addButton.isEnabled = controller.currentModelDescriptor.value != null
    }

    // ── Button handlers ───────────────────────────────────────────────────

    private fun handleAdd() {
        val descriptor = controller.currentModelDescriptor.value
        if (descriptor == null) {
            onMessage("Select a model on the Model step first.", NotificationSeverity.WARNING)
            return
        }
        val ownerWindow = SwingUtilities.getWindowAncestor(this)
        val dialog = InputEditorDialog(
            owner = ownerWindow,
            descriptor = descriptor,
            mode = InputEditorDialog.Mode.Add,
            existingNames = controller.inputs.value.map { it.name }.toSet()
        )
        val spec = dialog.showDialog() ?: return
        try {
            controller.addInput(spec)
        } catch (ex: IllegalArgumentException) {
            onMessage(ex.message ?: "Could not add input", NotificationSeverity.ERROR)
        }
    }

    private fun handleEdit() {
        val descriptor = controller.currentModelDescriptor.value ?: return
        val idx = controller.selectedInputIndex.value
        val list = controller.inputs.value
        if (idx !in list.indices) return
        val ownerWindow = SwingUtilities.getWindowAncestor(this)
        val dialog = InputEditorDialog(
            owner = ownerWindow,
            descriptor = descriptor,
            mode = InputEditorDialog.Mode.Edit(idx, list[idx]),
            existingNames = list.map { it.name }.toSet()
        )
        val updated = dialog.showDialog() ?: return
        try {
            controller.updateInput(idx, updated)
        } catch (ex: IllegalArgumentException) {
            onMessage(ex.message ?: "Could not update input", NotificationSeverity.ERROR)
        }
    }

    private fun handleDelete() {
        val idx = controller.selectedInputIndex.value
        val list = controller.inputs.value
        if (idx !in list.indices) return
        val name = list[idx].name
        val choice = JOptionPane.showConfirmDialog(
            this,
            "Delete decision variable '$name'?",
            "Delete Input",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        if (choice == JOptionPane.YES_OPTION) {
            controller.deleteInput(idx)
        }
    }

    private fun handleMoveUp() {
        val idx = controller.selectedInputIndex.value
        if (idx > 0) controller.moveInputUp(idx)
    }

    private fun handleMoveDown() {
        val idx = controller.selectedInputIndex.value
        if (idx >= 0 && idx < controller.inputs.value.lastIndex) {
            controller.moveInputDown(idx)
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

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

    /** Read-only table model backing the decision-variables table. */
    private class InputsTableModel : AbstractTableModel() {
        private val columns = arrayOf("Name", "Lower", "Upper", "Granularity")
        private var rows: List<OptimizationInputSpec> = emptyList()

        fun setRows(newRows: List<OptimizationInputSpec>) {
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
                1 -> r.lowerBound
                2 -> r.upperBound
                3 -> r.granularity
                else -> ""
            }
        }
    }
}
