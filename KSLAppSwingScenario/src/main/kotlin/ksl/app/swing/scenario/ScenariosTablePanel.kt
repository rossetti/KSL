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

package ksl.app.swing.scenario

import kotlinx.coroutines.launch
import ksl.app.config.ModelReference
import ksl.app.config.ScenarioSpec
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

/**
 * Scenarios tab body: master JTable plus row-action toolbar.
 *
 * Columns:
 *  - **Skip** (boolean, editable) — toggles [ScenarioSpec.skipOnRun].
 *  - **Name** (read-only) — [ScenarioSpec.name].
 *  - **Model** (read-only) — display id derived from
 *    [ScenarioSpec.modelReference].
 *  - **Run params** (read-only) — short summary of any
 *    [ScenarioSpec.runOverrides] (e.g. "30 reps, 480.0 length") or
 *    `(model defaults)`.
 *
 * Row actions:
 *  - **Add** opens an [AddScenarioDialog] (name-only in Phase E); the
 *    new scenario starts with an `<unresolved>` model reference that
 *    the analyst sets in the editor (lands in Phase F).
 *  - **Clone / Delete / Move Up / Move Down** delegate to the
 *    controller; enabled only when a row is selected.
 *  - **Edit** is built but disabled (tooltip points at Phase F).
 *
 * The double-click hook is wired but no-ops until Phase F.  The table
 * is a one-way mirror of `controller.scenarios` — re-builds on every
 * emission via `fireTableDataChanged`; selection synchronises
 * `controller.selectedIndex` bidirectionally.
 */
class ScenariosTablePanel(
    private val controller: ScenarioAppController
) : JPanel(BorderLayout()) {

    private val tableModel = ScenariosTableModel { controller.scenarios.value }
    val table: JTable = JTable(tableModel).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        fillsViewportHeight = true
        rowHeight = 22
        autoCreateRowSorter = false
        putClientProperty("terminateEditOnFocusLost", true)
    }

    private val addButton = JButton("Add…")
    private val editButton = JButton("Edit…").apply {
        isEnabled = false
        toolTipText = "Scenario editor lands in Phase F."
    }
    private val cloneButton = JButton("Clone")
    private val deleteButton = JButton("Delete")
    private val upButton = JButton("Move Up")
    private val downButton = JButton("Move Down")

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        applyColumnWidths()
        add(buildToolbar(), BorderLayout.NORTH)
        add(JScrollPane(table).apply { preferredSize = Dimension(0, 320) }, BorderLayout.CENTER)

        wireSelectionSync()
        wireDoubleClick()
        wireScenariosCollector()
        wireActions()
        refreshActionEnablement()
    }

    private fun applyColumnWidths() {
        val cm = table.columnModel
        cm.getColumn(COL_SKIP).preferredWidth = 50
        cm.getColumn(COL_SKIP).maxWidth = 60
        cm.getColumn(COL_NAME).preferredWidth = 220
        cm.getColumn(COL_MODEL).preferredWidth = 260
        cm.getColumn(COL_PARAMS).preferredWidth = 240
    }

    private fun buildToolbar(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        border = BorderFactory.createEmptyBorder(0, 0, 6, 0)
        add(addButton)
        add(Box.createHorizontalStrut(6))
        add(editButton)
        add(Box.createHorizontalStrut(6))
        add(cloneButton)
        add(Box.createHorizontalStrut(6))
        add(deleteButton)
        add(Box.createHorizontalStrut(16))
        add(upButton)
        add(Box.createHorizontalStrut(6))
        add(downButton)
        add(Box.createHorizontalGlue())
    }

    private fun wireSelectionSync() {
        // Table → controller
        table.selectionModel.addListSelectionListener { e ->
            if (e.valueIsAdjusting) return@addListSelectionListener
            val row = table.selectedRow
            controller.setSelectedIndex(if (row < 0) -1 else row)
            refreshActionEnablement()
        }
        // Controller → table
        controller.edtScope.launch {
            controller.selectedIndex.collect { idx ->
                if (idx < 0) {
                    if (table.selectedRow != -1) table.clearSelection()
                } else if (idx in 0 until table.rowCount && table.selectedRow != idx) {
                    table.setRowSelectionInterval(idx, idx)
                }
                refreshActionEnablement()
            }
        }
    }

    private fun wireDoubleClick() {
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && table.selectedRow >= 0) {
                    // Phase F: open ScenarioEditorWindow for the selected scenario.
                }
            }
        })
    }

    private fun wireScenariosCollector() {
        controller.edtScope.launch {
            controller.scenarios.collect {
                tableModel.fireTableDataChanged()
                // After data refresh, restore selection from controller.
                val idx = controller.selectedIndex.value
                if (idx in 0 until table.rowCount && table.selectedRow != idx) {
                    table.setRowSelectionInterval(idx, idx)
                }
                refreshActionEnablement()
            }
        }
    }

    private fun wireActions() {
        addButton.addActionListener {
            val existingNames = controller.scenarios.value.map { it.name }.toSet()
            val name = AddScenarioDialog.prompt(this, existingNames) ?: return@addActionListener
            val spec = ScenarioSpec(
                name = name,
                modelReference = ModelReference.Embedded(UNRESOLVED_MODEL_ID)
            )
            controller.addScenario(spec)
        }
        cloneButton.addActionListener {
            val idx = controller.selectedIndex.value
            if (idx >= 0) controller.cloneScenario(idx)
        }
        deleteButton.addActionListener {
            val idx = controller.selectedIndex.value
            if (idx < 0) return@addActionListener
            val spec = controller.scenarios.value.getOrNull(idx) ?: return@addActionListener
            val choice = JOptionPane.showConfirmDialog(
                this,
                "Delete scenario '${spec.name}'?",
                "Delete Scenario",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            )
            if (choice == JOptionPane.YES_OPTION) controller.deleteScenario(idx)
        }
        upButton.addActionListener {
            val idx = controller.selectedIndex.value
            if (idx >= 1) controller.moveScenarioUp(idx)
        }
        downButton.addActionListener {
            val idx = controller.selectedIndex.value
            if (idx in 0 until controller.scenarios.value.lastIndex) controller.moveScenarioDown(idx)
        }
    }

    private fun refreshActionEnablement() {
        val idx = controller.selectedIndex.value
        val count = controller.scenarios.value.size
        val hasSelection = idx in 0 until count
        cloneButton.isEnabled = hasSelection
        deleteButton.isEnabled = hasSelection
        upButton.isEnabled = hasSelection && idx >= 1
        downButton.isEnabled = hasSelection && idx < count - 1
    }

    companion object {
        const val COL_SKIP: Int = 0
        const val COL_NAME: Int = 1
        const val COL_MODEL: Int = 2
        const val COL_PARAMS: Int = 3
        const val UNRESOLVED_MODEL_ID: String = "<unresolved>"
    }

    /** Backing table model.  Reads through the supplied snapshot
     *  lambda on every cell access so refresh is a single
     *  `fireTableDataChanged()` call. */
    private inner class ScenariosTableModel(
        private val snapshotProvider: () -> List<ScenarioSpec>
    ) : AbstractTableModel() {

        override fun getRowCount(): Int = snapshotProvider().size
        override fun getColumnCount(): Int = 4

        override fun getColumnName(column: Int): String = when (column) {
            COL_SKIP -> "Skip"
            COL_NAME -> "Name"
            COL_MODEL -> "Model"
            COL_PARAMS -> "Run params"
            else -> ""
        }

        override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
            COL_SKIP -> java.lang.Boolean::class.java
            else -> String::class.java
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean =
            columnIndex == COL_SKIP

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
            val spec = snapshotProvider().getOrNull(rowIndex) ?: return null
            return when (columnIndex) {
                COL_SKIP -> spec.skipOnRun
                COL_NAME -> spec.name
                COL_MODEL -> modelDisplay(spec.modelReference)
                COL_PARAMS -> runParamsSummary(spec)
                else -> null
            }
        }

        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            if (columnIndex != COL_SKIP) return
            val v = (aValue as? Boolean) ?: return
            controller.setSkipOnRun(rowIndex, v)
        }
    }
}

private fun modelDisplay(ref: ModelReference): String = when (ref) {
    is ModelReference.ByProviderId -> ref.providerId
    is ModelReference.ByJar -> ref.jarPath
    is ModelReference.ByBundleAndModelId -> "${ref.bundleId} / ${ref.modelId}"
    is ModelReference.Embedded -> "embedded: ${ref.modelName}"
}

private fun runParamsSummary(spec: ScenarioSpec): String {
    val o = spec.runOverrides ?: return "(model defaults)"
    val parts = mutableListOf<String>()
    o.numberOfReplications?.let { parts += "$it reps" }
    o.lengthOfReplication?.let { parts += "len $it" }
    o.lengthOfReplicationWarmUp?.let { parts += "warm $it" }
    return if (parts.isEmpty()) "(model defaults)" else parts.joinToString(", ")
}
