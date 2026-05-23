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

package ksl.app.swing.experiment

import ksl.app.config.experiment.ReplicationSpec
import ksl.app.config.experiment.materializeDesign
import ksl.app.swing.common.notification.NotificationSeverity
import ksl.controls.experiments.DesignPoint
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Window
import java.io.PrintWriter
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.table.AbstractTableModel

/**
 *  Modal dialog that materializes the current document's design and
 *  shows the enumerated design points in a read-only-ish table.
 *  Launched from the *Materialize design points...* button on the
 *  Design tab.
 *
 *  Replacing the planned Phase E8 *Design Points* tab:
 *  - Showing the enumerated points is a check-my-work activity that
 *    fits a transient dialog better than a persistent tab.
 *  - Per-point replication overrides (the other use case for E8)
 *    live in this same dialog when the document's
 *    `ReplicationSpec` is `PerPoint`; overrides stage locally and
 *    are committed to the controller only on **Apply** so the user
 *    can experiment without thrashing the document dirty bit.
 *
 *  Columns: `#` (1-based index), one per factor (factor name), and
 *  `reps`.  The factor columns are read-only; the reps column is
 *  editable only when the document policy is
 *  `ReplicationSpec.PerPoint` (and even then only the override map
 *  changes — the default value is set on the Design tab's
 *  Replications panel).
 *
 *  CSV export uses the same column shape with no quoting needed
 *  (factor names and numbers don't contain commas).
 *
 *  The dialog is modal to the parent window so the user can't edit
 *  the spec underneath it; closing returns to the Design tab.
 *  Materialization runs once on open — if the design is huge
 *  (>= 10k points) this is the noticeable cost.  No streaming /
 *  pagination yet (deferred to Phase E11 polish).
 */
class DesignPointsPreviewDialog(
    owner: Window?,
    private val controller: ExperimentAppController,
    private val onMessage: (String, NotificationSeverity) -> Unit
) : JDialog(owner, "Design points preview", ModalityType.APPLICATION_MODAL) {

    private val factorNames: List<String> = controller.factors.value.map { it.name }
    private val originalPoints: List<EnumeratedPoint>
    private val stagedRepsOverrides: MutableMap<Int, Int>
    private val baseReps: ReplicationSpec = controller.replications.value

    private val tableModel: PreviewTableModel
    private val table: JTable
    private val totalLabel = JLabel(" ")
    private val applyBtn = JButton("Apply")

    init {
        // Snapshot the current spec, factors, replications, and
        // materialise the design once.  Exceptions bubble up to the
        // caller (the Design tab) which surfaces them as
        // notifications.
        val cfg = controller.currentConfiguration()
        val design = cfg.materializeDesign()
        originalPoints = design.designIterator().asSequence().mapIndexed { i, dp ->
            EnumeratedPoint(
                index = i,
                factorValues = factorNames.associateWith { name ->
                    dp.settings.entries.firstOrNull { it.key.name == name }?.value ?: 0.0
                },
                defaultReps = dp.numReplications
            )
        }.toList()
        stagedRepsOverrides = mutableMapOf<Int, Int>().apply {
            if (baseReps is ReplicationSpec.PerPoint) putAll(baseReps.overrides)
        }

        tableModel = PreviewTableModel()
        table = JTable(tableModel).apply {
            selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
            fillsViewportHeight = true
            putClientProperty("terminateEditOnFocusLost", true)
        }

        buildLayout()
        refreshTotal()
        updateApplyEnablement()
        pack()
        setLocationRelativeTo(owner)
        minimumSize = Dimension(520, 360)
    }

    private fun buildLayout() {
        layout = BorderLayout(0, 6)
        rootPane.border = BorderFactory.createEmptyBorder(10, 12, 10, 12)

        val header = JPanel(BorderLayout())
        val headerText = buildString {
            append("Design: ")
            when (val ds = controller.designSpec.value) {
                is ksl.app.config.experiment.DesignSpec.FullFactorial ->
                    append("Full factorial")
                is ksl.app.config.experiment.DesignSpec.TwoLevelFactorial ->
                    append("Two-level factorial")
                is ksl.app.config.experiment.DesignSpec.CentralComposite ->
                    append("Central composite")
                is ksl.app.config.experiment.DesignSpec.Manual ->
                    append("Custom (${ds.points.size} points)")
            }
            append("  ·  Replications: ")
            when (val rep = baseReps) {
                is ReplicationSpec.Uniform -> append("Uniform (${rep.replications} per point)")
                is ReplicationSpec.PerPoint -> append("Per-point (default ${rep.default})")
            }
        }
        header.add(JLabel(headerText), BorderLayout.WEST)
        add(header, BorderLayout.NORTH)

        val scroll = JScrollPane(table)
        scroll.preferredSize = Dimension(620, 320)
        add(scroll, BorderLayout.CENTER)

        val south = JPanel(BorderLayout())
        south.add(totalLabel, BorderLayout.WEST)

        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        val exportBtn = JButton("Export CSV...")
        exportBtn.addActionListener { exportCsv() }
        val closeBtn = JButton("Close")
        closeBtn.addActionListener { dispose() }
        applyBtn.addActionListener { apply() }
        buttons.add(exportBtn); buttons.add(applyBtn); buttons.add(closeBtn)
        south.add(buttons, BorderLayout.EAST)

        add(south, BorderLayout.SOUTH)
    }

    private fun refreshTotal() {
        val totalRuns = originalPoints.sumOf { p ->
            (stagedRepsOverrides[p.index] ?: effectiveDefaultReps(p)).toLong()
        }
        totalLabel.text = "<html><b>${originalPoints.size}</b> design points · " +
            "<b>$totalRuns</b> total runs</html>"
    }

    /** Effective reps for a single point under the staged-overrides
     *  view.  Used both for the table's reps column display and for
     *  the totalRuns calculation. */
    private fun effectiveDefaultReps(p: EnumeratedPoint): Int = when (val r = baseReps) {
        is ReplicationSpec.Uniform -> r.replications
        is ReplicationSpec.PerPoint -> r.default
    }

    /** True when the reps column should be editable (PerPoint
     *  policy).  Uniform makes every point's reps a function of the
     *  document-level default, so per-row editing wouldn't have
     *  anywhere to go. */
    private fun repsColumnEditable(): Boolean = baseReps is ReplicationSpec.PerPoint

    private fun updateApplyEnablement() {
        if (baseReps !is ReplicationSpec.PerPoint) {
            applyBtn.isEnabled = false
            applyBtn.toolTipText = "Switch the document policy to Per-point on the " +
                "Design tab to enable per-row overrides."
            return
        }
        val cur = baseReps.overrides
        val changed = stagedRepsOverrides != cur
        applyBtn.isEnabled = changed
        applyBtn.toolTipText = if (changed) "Commit the staged overrides to the document."
        else "No changes to apply."
    }

    private fun apply() {
        val rep = baseReps as? ReplicationSpec.PerPoint ?: return
        // Drop overrides that match the default — keeps the map tight.
        val cleaned = stagedRepsOverrides.filterValues { it != rep.default }
        controller.setReplications(rep.copy(overrides = cleaned))
        onMessage(
            "Applied ${cleaned.size} per-point replication override${if (cleaned.size == 1) "" else "s"}.",
            NotificationSeverity.INFO
        )
        // Re-snapshot the base so further edits compare correctly.
        dispose()
    }

    private fun exportCsv() {
        val chooser = JFileChooser().apply {
            dialogTitle = "Export design points to CSV"
            fileFilter = FileNameExtensionFilter("CSV files (*.csv)", "csv")
            selectedFile = java.io.File("design-points.csv")
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        var file = chooser.selectedFile
        if (!file.name.lowercase().endsWith(".csv")) {
            file = java.io.File(file.parentFile, "${file.name}.csv")
        }
        try {
            PrintWriter(file.bufferedWriter()).use { out ->
                out.print("#")
                for (n in factorNames) { out.print(","); out.print(n) }
                out.println(",reps")
                for (p in originalPoints) {
                    out.print(p.index + 1)
                    for (n in factorNames) { out.print(","); out.print(p.factorValues[n] ?: 0.0) }
                    out.print(","); out.println(effectiveReps(p))
                }
            }
            onMessage(
                "Wrote ${originalPoints.size} design points to ${file.absolutePath}",
                NotificationSeverity.INFO
            )
        } catch (ex: Exception) {
            JOptionPane.showMessageDialog(
                this,
                "Could not write CSV: ${ex.message ?: ex::class.simpleName}",
                "Export failed",
                JOptionPane.ERROR_MESSAGE
            )
        }
    }

    private fun effectiveReps(p: EnumeratedPoint): Int =
        stagedRepsOverrides[p.index] ?: effectiveDefaultReps(p)

    /** One row in the preview table.  factorValues is keyed by
     *  factor name so column access is stable even when the
     *  substrate's [DesignPoint.settings] is a Map<Factor,Double>. */
    private data class EnumeratedPoint(
        val index: Int,
        val factorValues: Map<String, Double>,
        val defaultReps: Int
    )

    private inner class PreviewTableModel : AbstractTableModel() {
        // Columns: # | factor1 ... factorN | reps
        private val repsColumn: Int get() = 1 + factorNames.size
        override fun getRowCount(): Int = originalPoints.size
        override fun getColumnCount(): Int = 2 + factorNames.size
        override fun getColumnName(column: Int): String = when (column) {
            0 -> "#"
            repsColumn -> "reps"
            else -> factorNames[column - 1]
        }
        override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
            0 -> java.lang.Integer::class.java
            repsColumn -> java.lang.Integer::class.java
            else -> java.lang.Double::class.java
        }
        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean =
            columnIndex == repsColumn && repsColumnEditable()

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val p = originalPoints[rowIndex]
            return when (columnIndex) {
                0 -> p.index + 1
                repsColumn -> effectiveReps(p)
                else -> p.factorValues[factorNames[columnIndex - 1]] ?: 0.0
            }
        }

        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            if (columnIndex != repsColumn || !repsColumnEditable()) return
            val parsed = when (aValue) {
                is Number -> aValue.toInt()
                is String -> aValue.trim().toIntOrNull()
                else -> null
            }
            val p = originalPoints[rowIndex]
            val defaultReps = effectiveDefaultReps(p)
            when {
                parsed == null || parsed < 1 -> {
                    // Invalid input: silently revert.  No noisy
                    // notification — the cell editor's foreground
                    // colour or border tells the user well enough.
                }
                parsed == defaultReps -> stagedRepsOverrides.remove(p.index)
                else -> stagedRepsOverrides[p.index] = parsed
            }
            fireTableCellUpdated(rowIndex, columnIndex)
            refreshTotal()
            updateApplyEnablement()
        }
    }
}
