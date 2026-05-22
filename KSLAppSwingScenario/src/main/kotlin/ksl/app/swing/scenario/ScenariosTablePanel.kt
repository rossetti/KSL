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
import ksl.app.config.ExperimentRunOverrides
import ksl.app.config.ModelReference
import ksl.app.config.ScenarioSpec
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
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
import javax.swing.table.DefaultTableCellRenderer

/**
 * Scenarios tab body: master JTable plus row-action toolbar.
 *
 * Columns (left → right) — matches the layout in workflow-scenario.md §6:
 *  - **Status** — per-scenario lifecycle text ("Running…" / "Completed" / "Failed" / "Skipped").
 *  - **Run?** — checkbox; checked = enabled, unchecked = skipped (inverted [ScenarioSpec.skipOnRun]).
 *  - **Name** — read-only; edit via the per-scenario editor window.
 *  - **Model** — display id derived from [ScenarioSpec.modelReference].
 *  - **Reps** — inline-editable replication count; blank = model default.
 *  - **Overrides** — summary text e.g. `"3 controls · 2 RVs · run-params"` or `"(no overrides)"`.
 *
 * The double-click hook and *Edit…* button both open the modeless
 * scenario-editor window.  Selection is bidirectionally synced with
 * `controller.selectedIndex`.
 */
class ScenariosTablePanel(
    private val controller: ScenarioAppController,
    private val addScenarioProvider: () -> ScenarioSpec? = { null },
    private val openEditor: (Int) -> Unit = { _ -> }
) : JPanel(BorderLayout()) {

    private val tableModel = ScenariosTableModel { controller.scenarios.value }

    /** Suppresses the table→controller selection listener while the
     *  table is being rebuilt from a controller-side update. */
    private var suppressSelectionListener: Boolean = false

    val table: JTable = JTable(tableModel).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        fillsViewportHeight = true
        rowHeight = 22
        autoCreateRowSorter = false
        putClientProperty("terminateEditOnFocusLost", true)
    }

    private val addButton = JButton("Add…")
    private val editButton = JButton("Edit…")
    private val cloneButton = JButton("Clone")
    private val deleteButton = JButton("Delete")
    private val clearAllButton = JButton("Clear Scenarios").apply {
        toolTipText = "Remove every scenario from the list.  Output preferences " +
            "(database toggle, CSV flags, database policy) and execution mode " +
            "survive — this is a scenario-list reset, not a document reset.  " +
            "For a blank document, use <i>File → New Configuration</i>."
    }
    private val upButton = JButton("Move Up")
    private val downButton = JButton("Move Down")

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        applyColumnWidths()
        // Custom renderer for the Status column: when a scenario is
        // running, show "Running k / N  ✕" with the ✕ rendered in red
        // as a clickable cancel glyph.
        table.columnModel.getColumn(COL_STATUS).cellRenderer = StatusCellRenderer()
        add(buildToolbar(), BorderLayout.NORTH)
        add(JScrollPane(table).apply { preferredSize = Dimension(0, 320) }, BorderLayout.CENTER)

        wireSelectionSync()
        wireDoubleClick()
        wireScenariosCollector()
        wireStatusCollector()
        wireProgressCollector()
        wireRunningCollector()
        wireActions()
        wireStatusCancelClick()
        refreshActionEnablement()
    }

    /** Rightmost pixel width of the Status cell reserved for the
     *  cancel-glyph hit region.  Clicks inside this band trigger
     *  per-scenario cancel; clicks outside fall through to normal
     *  row selection. */
    private val cancelHitRegionWidth: Int = 22

    private fun wireStatusCancelClick() {
        // Click → cancel if the click landed in the cancel-glyph hit
        // region of a running scenario's Status cell.
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1 || e.clickCount != 1) return
                if (!isCancelHit(e)) return
                val row = table.rowAtPoint(e.point)
                val spec = controller.scenarios.value.getOrNull(row) ?: return
                controller.cancelScenario(spec.name)
                e.consume()
            }
        })
        // Hover feedback: pointer changes to a hand over the hit region
        // so the user can tell the glyph is clickable.
        table.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                table.cursor = if (isCancelHit(e)) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                               else Cursor.getDefaultCursor()
            }
        })
    }

    private fun isCancelHit(e: MouseEvent): Boolean {
        val col = table.columnAtPoint(e.point)
        if (col != COL_STATUS) return false
        val row = table.rowAtPoint(e.point)
        if (row < 0) return false
        val spec = controller.scenarios.value.getOrNull(row) ?: return false
        val status = controller.scenarioStatuses.value[spec.name]
        if (status != ScenarioAppController.ScenarioStatus.RUNNING) return false
        val cellRect = table.getCellRect(row, col, false)
        val hitRegionLeft = cellRect.x + cellRect.width - cancelHitRegionWidth
        return e.x >= hitRegionLeft
    }

    private fun wireStatusCollector() {
        controller.edtScope.launch {
            controller.scenarioStatuses.collect {
                tableModel.fireTableDataChanged()
            }
        }
    }

    private fun wireProgressCollector() {
        controller.edtScope.launch {
            controller.replicationProgress.collect {
                tableModel.fireTableDataChanged()
            }
        }
    }

    private fun wireRunningCollector() {
        controller.edtScope.launch {
            controller.runningFlow.collect { running ->
                table.isEnabled = !running
                refreshActionEnablement()
            }
        }
    }

    private fun applyColumnWidths() {
        val cm = table.columnModel
        cm.getColumn(COL_STATUS).preferredWidth = 140
        cm.getColumn(COL_RUN).preferredWidth = 50
        cm.getColumn(COL_RUN).maxWidth = 60
        cm.getColumn(COL_NAME).preferredWidth = 220
        cm.getColumn(COL_MODEL).preferredWidth = 240
        cm.getColumn(COL_REPS).preferredWidth = 70
        cm.getColumn(COL_OVERRIDES).preferredWidth = 280
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
        add(Box.createHorizontalStrut(6))
        add(clearAllButton)
        add(Box.createHorizontalStrut(16))
        add(upButton)
        add(Box.createHorizontalStrut(6))
        add(downButton)
        add(Box.createHorizontalGlue())
    }

    private fun wireSelectionSync() {
        table.selectionModel.addListSelectionListener { e ->
            if (e.valueIsAdjusting) return@addListSelectionListener
            if (suppressSelectionListener) {
                refreshActionEnablement()
                return@addListSelectionListener
            }
            val row = table.selectedRow
            controller.setSelectedIndex(if (row < 0) -1 else row)
            refreshActionEnablement()
        }
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
                    // Don't open the editor when the user is double-clicking
                    // to start an inline edit on Reps/Run? — that's a normal
                    // table cell-edit gesture.
                    val col = table.columnAtPoint(e.point)
                    if (col == COL_REPS || col == COL_RUN) return
                    openEditor(table.selectedRow)
                }
            }
        })
    }

    private fun wireScenariosCollector() {
        controller.edtScope.launch {
            controller.scenarios.collect {
                suppressSelectionListener = true
                try {
                    tableModel.fireTableDataChanged()
                    val idx = controller.selectedIndex.value
                    if (idx in 0 until table.rowCount && table.selectedRow != idx) {
                        table.setRowSelectionInterval(idx, idx)
                    }
                } finally {
                    suppressSelectionListener = false
                }
                refreshActionEnablement()
            }
        }
    }

    private fun wireActions() {
        addButton.addActionListener {
            val spec = addScenarioProvider() ?: return@addActionListener
            controller.addScenario(spec)
        }
        editButton.addActionListener {
            val idx = currentRow()
            if (idx >= 0) openEditor(idx)
        }
        cloneButton.addActionListener {
            val idx = currentRow()
            if (idx >= 0) controller.cloneScenario(idx)
        }
        deleteButton.addActionListener {
            val idx = currentRow()
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
        clearAllButton.addActionListener {
            val count = controller.scenarios.value.size
            if (count == 0) return@addActionListener
            val plural = if (count == 1) "" else "s"
            // Clear All is a single-click whole-document wipe.  Under
            // the controller's contract it also detaches the document
            // from any loaded file (so a subsequent Save can't
            // overwrite the loaded file with an empty configuration).
            // Spell that out in the confirmation when there's a file
            // to lose; users with a new unsaved document don't need
            // the detach sentence.
            val attachedFile = controller.currentFile.value
            val body = if (attachedFile != null) {
                "Remove all $count scenario$plural from the list?\n\n" +
                    "The document will be detached from '${attachedFile.fileName}'.\n" +
                    "The file on disk is preserved — re-open it to recover its scenarios."
            } else {
                "Remove all $count scenario$plural from the list?"
            }
            val choice = JOptionPane.showConfirmDialog(
                this,
                body,
                "Clear Scenarios",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            )
            if (choice == JOptionPane.YES_OPTION) controller.clearScenarios()
        }
        upButton.addActionListener {
            val idx = currentRow()
            if (idx >= 1) controller.moveScenarioUp(idx)
        }
        downButton.addActionListener {
            val idx = currentRow()
            if (idx in 0 until controller.scenarios.value.lastIndex) controller.moveScenarioDown(idx)
        }
    }

    private fun currentRow(): Int {
        val row = table.selectedRow
        if (row >= 0) return row
        return controller.selectedIndex.value
    }

    private fun refreshActionEnablement() {
        val running = controller.runningFlow.value
        val idx = currentRow()
        val count = controller.scenarios.value.size
        val hasSelection = idx in 0 until count
        addButton.isEnabled = !running
        editButton.isEnabled = !running && hasSelection
        cloneButton.isEnabled = !running && hasSelection
        deleteButton.isEnabled = !running && hasSelection
        clearAllButton.isEnabled = !running && count > 0
        upButton.isEnabled = !running && hasSelection && idx >= 1
        downButton.isEnabled = !running && hasSelection && idx < count - 1
    }

    companion object {
        const val COL_STATUS: Int = 0
        const val COL_RUN: Int = 1
        const val COL_NAME: Int = 2
        const val COL_MODEL: Int = 3
        const val COL_REPS: Int = 4
        const val COL_OVERRIDES: Int = 5
        private const val COLUMN_COUNT: Int = 6
    }

    /** Renders the Status column.  For running scenarios, suffixes
     *  the status text with a red ✕ glyph that doubles as a "cancel
     *  this scenario" affordance — actual click handling lives in
     *  the panel's MouseListener so the renderer stays stateless. */
    private inner class StatusCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, column: Int
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val spec = controller.scenarios.value.getOrNull(row)
            val status = spec?.let { controller.scenarioStatuses.value[it.name] }
            text = if (status == ScenarioAppController.ScenarioStatus.RUNNING) {
                // HTML lets us colour the glyph without writing a custom paint.
                val base = (value as? String).orEmpty()
                "<html>$base &nbsp;<font color='#C0392B'>✕</font></html>"
            } else {
                (value as? String).orEmpty()
            }
            toolTipText = if (status == ScenarioAppController.ScenarioStatus.RUNNING)
                "Click ✕ to cancel just this scenario" else null
            horizontalAlignment = LEFT
            foreground = if (isSelected) table.selectionForeground else statusForeground(status)
            return this
        }

        private fun statusForeground(s: ScenarioAppController.ScenarioStatus?): Color = when (s) {
            ScenarioAppController.ScenarioStatus.FAILED -> Color(0xC0, 0x39, 0x2B)        // red
            ScenarioAppController.ScenarioStatus.COMPLETED -> Color(0x1E, 0x88, 0x44)      // green
            ScenarioAppController.ScenarioStatus.SKIPPED -> Color(0x77, 0x77, 0x77)        // grey
            // Muted slate-grey: distinct from FAILED's red so the user
            // can tell at a glance "you stopped this on purpose, nothing
            // went wrong."  Also distinct from the lighter SKIPPED grey.
            ScenarioAppController.ScenarioStatus.CANCELLED -> Color(0x55, 0x60, 0x6E)
            else -> table.foreground
        }
    }

    private inner class ScenariosTableModel(
        private val snapshotProvider: () -> List<ScenarioSpec>
    ) : AbstractTableModel() {

        override fun getRowCount(): Int = snapshotProvider().size
        override fun getColumnCount(): Int = COLUMN_COUNT

        override fun getColumnName(column: Int): String = when (column) {
            COL_STATUS -> "Status"
            COL_RUN -> "Run?"
            COL_NAME -> "Name"
            COL_MODEL -> "Model"
            COL_REPS -> "Reps"
            COL_OVERRIDES -> "Overrides"
            else -> ""
        }

        override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
            COL_RUN -> java.lang.Boolean::class.java
            else -> String::class.java
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean =
            !controller.runningFlow.value && (columnIndex == COL_RUN || columnIndex == COL_REPS)

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
            val spec = snapshotProvider().getOrNull(rowIndex) ?: return null
            return when (columnIndex) {
                COL_STATUS -> statusText(
                    controller.scenarioStatuses.value[spec.name],
                    controller.replicationProgress.value[spec.name]
                )
                COL_RUN -> !spec.skipOnRun                       // checked = enabled
                COL_NAME -> spec.name
                COL_MODEL -> modelDisplay(spec.modelReference)
                COL_REPS -> repsCellText(rowIndex, spec)
                COL_OVERRIDES -> overridesSummary(spec)
                else -> null
            }
        }

        /** Reps cell shows the override when present; otherwise the
         *  cached model default (italicised parentheses).  Returns an
         *  empty string when nothing is known yet (probe failure /
         *  unresolved bundle). */
        private fun repsCellText(rowIndex: Int, spec: ScenarioSpec): String {
            val override = spec.runOverrides?.numberOfReplications
            if (override != null) return override.toString()
            val defaults = controller.modelDefaultsFor(rowIndex) ?: return ""
            return "(${defaults.numberOfReplications})"
        }

        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            val spec = snapshotProvider().getOrNull(rowIndex) ?: return
            when (columnIndex) {
                COL_RUN -> {
                    val v = (aValue as? Boolean) ?: return
                    controller.setSkipOnRun(rowIndex, !v)        // checked = enabled
                }
                COL_REPS -> {
                    val text = (aValue as? String).orEmpty().trim()
                    val newReps: Int? = if (text.isEmpty()) null else text.toIntOrNull()?.takeIf { it >= 1 }
                    // Treat unparseable input as "no change" rather than wiping the override silently.
                    if (text.isNotEmpty() && newReps == null) return
                    val current = spec.runOverrides ?: ExperimentRunOverrides.EMPTY
                    if (current.numberOfReplications == newReps) return
                    val nextOverrides = current.copy(numberOfReplications = newReps)
                    val nextSpec = spec.copy(
                        runOverrides = if (nextOverrides.isEmpty) null else nextOverrides
                    )
                    controller.updateScenario(rowIndex, nextSpec)
                }
            }
        }
    }
}

private fun modelDisplay(ref: ModelReference): String = when (ref) {
    is ModelReference.ByProviderId -> ref.providerId
    is ModelReference.ByJar -> ref.jarPath
    is ModelReference.ByBundleAndModelId -> "${ref.bundleId} / ${ref.modelId}"
    is ModelReference.Embedded -> "embedded: ${ref.modelName}"
}

/**
 *  Per-row summary of every override category the scenario carries.
 *  Includes:
 *  - **run-params** when the spec has any non-null run-parameter override
 *    field (other than `numberOfReplications`, which already has its own
 *    column).
 *  - **N controls** when the spec carries any numeric/string/JSON
 *    control overrides.
 *  - **N RVs** when the spec carries any RV-parameter overrides.
 *  Returns `"(no overrides)"` when none of those apply.
 */
private fun overridesSummary(spec: ScenarioSpec): String {
    val parts = mutableListOf<String>()

    val runOverrideCount = countNonRepsRunOverrides(spec.runOverrides)
    if (runOverrideCount > 0) parts += "run-params ($runOverrideCount)"

    val controlCount = spec.controlOverrides.totalControls
    if (controlCount > 0) parts += "$controlCount control${if (controlCount == 1) "" else "s"}"

    val rvCount = spec.rvOverrides.size
    if (rvCount > 0) parts += "$rvCount RV${if (rvCount == 1) "" else "s"}"

    if (spec.modelConfiguration?.isNotEmpty() == true) parts += "config-map"

    return if (parts.isEmpty()) "(no overrides)" else parts.joinToString(" · ")
}

/** Count of non-null run-parameter override fields excluding
 *  `numberOfReplications` (which has its own column). */
private fun countNonRepsRunOverrides(o: ExperimentRunOverrides?): Int {
    if (o == null) return 0
    var n = 0
    if (o.lengthOfReplication != null) n++
    if (o.lengthOfReplicationWarmUp != null) n++
    if (o.numChunks != null) n++
    if (o.startingRepId != null) n++
    if (o.replicationInitializationOption != null) n++
    if (o.maximumAllowedExecutionTimePerReplication != null) n++
    if (o.resetStartStreamOption != null) n++
    if (o.advanceNextSubStreamOption != null) n++
    if (o.antitheticOption != null) n++
    if (o.numberOfStreamAdvancesPriorToRunning != null) n++
    if (o.garbageCollectAfterReplicationFlag != null) n++
    return n
}

private fun statusText(
    status: ScenarioAppController.ScenarioStatus?,
    progress: Pair<Int, Int>?
): String = when (status) {
    null, ScenarioAppController.ScenarioStatus.IDLE -> ""
    ScenarioAppController.ScenarioStatus.PENDING -> "Queued…"
    ScenarioAppController.ScenarioStatus.RUNNING ->
        progress?.let { (cur, total) -> "Running $cur / $total" } ?: "Running…"
    ScenarioAppController.ScenarioStatus.COMPLETED -> "Completed"
    ScenarioAppController.ScenarioStatus.FAILED -> "Failed"
    ScenarioAppController.ScenarioStatus.CANCELLED -> "Cancelled"
    ScenarioAppController.ScenarioStatus.SKIPPED -> "Skipped"
}
