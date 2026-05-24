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

import ksl.app.config.experiment.DesignSpec
import ksl.app.config.experiment.FactorSpec
import ksl.app.config.experiment.ManualPointSpec
import ksl.app.config.experiment.ReplicationSpec
import kotlinx.coroutines.launch
import ksl.app.config.experiment.materializeDesign
import ksl.app.swing.common.notification.NotificationSeverity
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Window
import java.io.BufferedReader
import java.io.File
import java.io.PrintWriter
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.table.AbstractTableModel

/**
 *  Modal dialog that materializes the current document's design and
 *  shows the enumerated design points.  Launched from the
 *  *Materialize design points...* button on the Design tab.  Replaces
 *  the originally-planned Phase E8 *Design Points* tab — a transient
 *  check-my-work view fits a dialog better than a persistent tab.
 *
 *  ## Display mode
 *
 *  A Raw / Coded toggle at the top picks how the factor columns
 *  display.  Raw shows the substrate's raw factor values
 *  (`DesignPoint.values()`); Coded shows the coded representation
 *  (`DesignPoint.codedValues()`, where each factor's range maps to
 *  [-1, +1]).  CSV export uses whichever mode is currently active.
 *
 *  ## Per-point reps
 *
 *  When the document's `ReplicationSpec` is `PerPoint`, the `reps`
 *  column is editable; edits stage locally and the **Apply** button
 *  pushes the override map to the controller.  When the policy is
 *  `Uniform`, the column is read-only (every point gets the
 *  Uniform value).
 *
 *  ## CSV import (Manual designs only)
 *
 *  When the family is `DesignSpec.Manual`, an **Import CSV...**
 *  button lets the user replace the manual point list with values
 *  loaded from a CSV whose header matches the document's factor
 *  names (in any order).  Cell values are validated against each
 *  factor's [min, max] interval — out-of-range rows are listed in
 *  the failure dialog and nothing is imported on error.  On
 *  success the dialog closes (the user can reopen it to see the
 *  imported points).
 *
 *  Materialization runs once on open — if the design is huge
 *  (>= 10k points) this is the noticeable cost.  No streaming /
 *  pagination yet (deferred to Phase E11 polish).
 */
class DesignPointsPreviewDialog(
    owner: Window?,
    private val controller: ExperimentAppController,
    private val onMessage: (String, NotificationSeverity) -> Unit
) : JDialog(owner, "Design points preview", ModalityType.APPLICATION_MODAL) {
    //  Modal — the dialog is a pre-run preview ("did the design I
    //  configured produce the points I expected?").  Live status
    //  + per-point cancellation moved out of this dialog in E7.9
    //  and now live on the Simulate tab where they're persistently
    //  visible during runs.  No coroutine subscriptions to leak.

    private val factors: List<FactorSpec> = controller.factors.value
    private val factorNames: List<String> = factors.map { it.name }
    private val originalPoints: List<EnumeratedPoint>
    private val stagedRepsOverrides: MutableMap<Int, Int>
    private val baseReps: ReplicationSpec = controller.replications.value
    private val isManual: Boolean =
        controller.designSpec.value is DesignSpec.Manual

    private val rawRadio = JRadioButton("Raw (default)", true)
    private val codedRadio = JRadioButton("Coded")
    private val displayGroup = ButtonGroup().apply {
        add(rawRadio); add(codedRadio)
    }
    private val tableModel: PreviewTableModel
    private val table: JTable
    private val totalLabel = JLabel(" ")
    private val applyBtn = JButton("Apply")
    private val importBtn = JButton("Import CSV...")

    init {
        val cfg = controller.currentConfiguration()
        val design = cfg.materializeDesign()
        // Capture both raw + coded values per point at construction
        // time.  Toggling the display radio is then pure UI.
        originalPoints = design.designIterator().asSequence().mapIndexed { i, dp ->
            val rawArr = dp.values()
            val codedArr = dp.codedValues()
            // dp.settings is keyed by Factor (substrate type); the
            // values() / codedValues() arrays are in factor order
            // matching dp.design.factorNames.
            val designFactorNames = dp.design.factorNames
            val rawByName = LinkedHashMap<String, Double>()
            val codedByName = LinkedHashMap<String, Double>()
            for ((idx, name) in designFactorNames.withIndex()) {
                rawByName[name] = rawArr.getOrNull(idx) ?: 0.0
                codedByName[name] = codedArr.getOrNull(idx) ?: 0.0
            }
            EnumeratedPoint(
                index = i,
                rawValues = rawByName,
                codedValues = codedByName,
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

        rawRadio.addActionListener { tableModel.fireTableDataChanged() }
        codedRadio.addActionListener { tableModel.fireTableDataChanged() }

        buildLayout()
        refreshTotal()
        updateApplyEnablement()
        importBtn.isEnabled = isManual
        importBtn.toolTipText =
            if (isManual) "Replace the manual point list with values from a CSV file."
            else "Import is only available for the Custom design points family."
        pack()
        setLocationRelativeTo(owner)
        minimumSize = Dimension(640, 400)
    }

    private fun buildLayout() {
        layout = BorderLayout(0, 6)
        rootPane.border = BorderFactory.createEmptyBorder(10, 12, 10, 12)

        val north = JPanel(BorderLayout())
        val headerText = buildString {
            append("Design: ")
            when (val ds = controller.designSpec.value) {
                is DesignSpec.FullFactorial -> append("Full factorial")
                is DesignSpec.TwoLevelFactorial -> append("Two-level factorial")
                is DesignSpec.CentralComposite -> append("Central composite")
                is DesignSpec.Manual -> append("Custom (${ds.points.size} points)")
            }
            append("  ·  Replications: ")
            when (val rep = baseReps) {
                is ReplicationSpec.Uniform -> append("Uniform (${rep.replications} per point)")
                is ReplicationSpec.PerPoint -> append("Per-point (default ${rep.default})")
            }
        }
        north.add(JLabel(headerText), BorderLayout.NORTH)

        val displayRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        displayRow.add(JLabel("Display factor values:"))
        displayRow.add(rawRadio)
        displayRow.add(codedRadio)
        north.add(displayRow, BorderLayout.SOUTH)

        add(north, BorderLayout.NORTH)

        val scroll = JScrollPane(table)
        scroll.preferredSize = Dimension(680, 320)
        add(scroll, BorderLayout.CENTER)

        val south = JPanel(BorderLayout())
        south.add(totalLabel, BorderLayout.WEST)

        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        val exportBtn = JButton("Export CSV...")
        exportBtn.addActionListener { exportCsv() }
        importBtn.addActionListener { importCsv() }
        val closeBtn = JButton("Close")
        closeBtn.addActionListener { dispose() }
        applyBtn.addActionListener { apply() }
        buttons.add(importBtn)
        buttons.add(exportBtn)
        buttons.add(applyBtn)
        buttons.add(closeBtn)
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

    private fun effectiveDefaultReps(p: EnumeratedPoint): Int = when (val r = baseReps) {
        is ReplicationSpec.Uniform -> r.replications
        is ReplicationSpec.PerPoint -> r.default
    }

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
        dispose()
    }

    private fun effectiveReps(p: EnumeratedPoint): Int =
        stagedRepsOverrides[p.index] ?: effectiveDefaultReps(p)

    /** Factor values for the active display mode.  Raw is the
     *  default; Coded reads the precomputed coded values. */
    private fun factorValuesFor(p: EnumeratedPoint): Map<String, Double> =
        if (codedRadio.isSelected) p.codedValues else p.rawValues

    // ---------------------------------------------------------------
    // CSV export
    // ---------------------------------------------------------------

    private fun exportCsv() {
        val chooser = JFileChooser().apply {
            dialogTitle = "Export design points to CSV"
            fileFilter = FileNameExtensionFilter("CSV files (*.csv)", "csv")
            val mode = if (codedRadio.isSelected) "coded" else "raw"
            selectedFile = File("design-points-$mode.csv")
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        var file = chooser.selectedFile
        if (!file.name.lowercase().endsWith(".csv")) {
            file = File(file.parentFile, "${file.name}.csv")
        }
        try {
            PrintWriter(file.bufferedWriter()).use { out ->
                out.print("#")
                for (n in factorNames) { out.print(","); out.print(n) }
                out.println(",reps")
                for (p in originalPoints) {
                    val values = factorValuesFor(p)
                    out.print(p.index + 1)
                    for (n in factorNames) { out.print(","); out.print(values[n] ?: 0.0) }
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

    // ---------------------------------------------------------------
    // CSV import (Manual only)
    // ---------------------------------------------------------------

    private fun importCsv() {
        if (!isManual) return
        val chooser = JFileChooser().apply {
            dialogTitle = "Import design points from CSV"
            fileFilter = FileNameExtensionFilter("CSV files (*.csv)", "csv")
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val file = chooser.selectedFile
        val result = parseImportCsv(file)
        when (result) {
            is ImportResult.Failure -> {
                JOptionPane.showMessageDialog(
                    this,
                    buildString {
                        append("Import failed (${result.errors.size} error")
                        append(if (result.errors.size == 1) "" else "s")
                        append("):\n\n")
                        for ((i, msg) in result.errors.withIndex()) {
                            append("  • $msg")
                            if (i < result.errors.lastIndex) append("\n")
                        }
                    },
                    "CSV import failed",
                    JOptionPane.ERROR_MESSAGE
                )
            }
            is ImportResult.Ok -> {
                controller.setDesignSpec(DesignSpec.Manual(result.points))
                onMessage(
                    "Imported ${result.points.size} design point" +
                        "${if (result.points.size == 1) "" else "s"} from ${file.name}.  " +
                        "Reopen the preview to view them.",
                    NotificationSeverity.INFO
                )
                dispose()
            }
        }
    }

    private sealed class ImportResult {
        data class Ok(val points: List<ManualPointSpec>) : ImportResult()
        data class Failure(val errors: List<String>) : ImportResult()
    }

    /** Parse a CSV in the shape `exportCsv` produces.  Header must
     *  contain a column for each declared factor name; the `#` and
     *  `reps` columns are optional.  Imported values are
     *  range-checked against each factor's [min, max] interval.
     *  Values that are within range but not declared levels are
     *  accepted without a warning — the import path is for
     *  power-user workflows where the caller knows what they're
     *  doing. */
    private fun parseImportCsv(file: File): ImportResult {
        val errors = mutableListOf<String>()
        val lines: List<String> = try {
            file.bufferedReader().use(BufferedReader::readLines)
        } catch (ex: Exception) {
            return ImportResult.Failure(
                listOf("could not read ${file.absolutePath}: ${ex.message ?: ex::class.simpleName}")
            )
        }
        if (lines.isEmpty()) {
            return ImportResult.Failure(listOf("file is empty"))
        }
        val header = lines[0].split(',').map { it.trim() }
        // Build name -> column index map; require every factor name.
        val nameToCol = header.withIndex().associate { it.value to it.index }
        val missing = factorNames.filter { it !in nameToCol }
        if (missing.isNotEmpty()) {
            return ImportResult.Failure(
                listOf("header is missing required factor column(s): ${missing.joinToString(", ")}")
            )
        }
        val repsCol = nameToCol["reps"]
        val points = mutableListOf<ManualPointSpec>()
        for ((rowIdx, raw) in lines.drop(1).withIndex()) {
            val lineNo = rowIdx + 2  // 1-based, accounting for the header
            if (raw.isBlank()) continue
            val cells = raw.split(',').map { it.trim() }
            val values = mutableMapOf<String, Double>()
            for (f in factors) {
                val col = nameToCol.getValue(f.name)
                val token = cells.getOrNull(col)
                if (token.isNullOrEmpty()) {
                    errors += "line $lineNo: missing value for '${f.name}'"
                    continue
                }
                val v = token.toDoubleOrNull()
                if (v == null) {
                    errors += "line $lineNo: value for '${f.name}' is not a number: '$token'"
                    continue
                }
                val minLvl = f.levels.min()
                val maxLvl = f.levels.max()
                if (v < minLvl || v > maxLvl) {
                    errors += "line $lineNo: '${f.name}' value $v is outside " +
                        "the factor's range [$minLvl, $maxLvl]"
                    continue
                }
                values[f.name] = v
            }
            val reps: Int? = if (repsCol != null) {
                val token = cells.getOrNull(repsCol)?.trim().orEmpty()
                if (token.isEmpty()) null
                else token.toIntOrNull()?.coerceAtLeast(1)
                    ?: run {
                        errors += "line $lineNo: reps token '$token' is not a positive integer"
                        null
                    }
            } else null
            // Only build a point if every factor's value parsed cleanly.
            if (values.size == factors.size) {
                points += ManualPointSpec(factorValues = values, replications = reps)
            }
        }
        if (errors.isNotEmpty()) return ImportResult.Failure(errors)
        if (points.isEmpty()) return ImportResult.Failure(listOf("no data rows found"))
        return ImportResult.Ok(points)
    }

    // ---------------------------------------------------------------
    // Table model
    // ---------------------------------------------------------------

    private data class EnumeratedPoint(
        val index: Int,
        val rawValues: Map<String, Double>,
        val codedValues: Map<String, Double>,
        val defaultReps: Int
    )

    private inner class PreviewTableModel : AbstractTableModel() {
        // Column layout:  # | factor1 ... factorN | reps
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
                else -> factorValuesFor(p)[factorNames[columnIndex - 1]] ?: 0.0
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
                parsed == null || parsed < 1 -> { /* invalid; silently revert */ }
                parsed == defaultReps -> stagedRepsOverrides.remove(p.index)
                else -> stagedRepsOverrides[p.index] = parsed
            }
            fireTableCellUpdated(rowIndex, columnIndex)
            refreshTotal()
            updateApplyEnablement()
        }
    }
}
