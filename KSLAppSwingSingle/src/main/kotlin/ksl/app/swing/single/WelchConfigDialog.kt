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

package ksl.app.swing.single

import ksl.app.config.OutputConfig
import ksl.app.config.WelchResponseSpec
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Window
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.RowFilter
import javax.swing.WindowConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter

/**
 * One editable row in the Welch dialog's response checklist: a response,
 * whether it is selected for capture, and its discretizing interval.
 * Pure data — no Swing — so the dialog's seeding and selection logic can
 * be unit-tested headless via [WelchDialogLogic].
 */
data class WelchRowState(
    val name: String,
    val isTimeWeighted: Boolean,
    val selected: Boolean,
    val interval: Double
)

/**
 * Pure, Swing-free logic backing [WelchConfigDialog].  Separated so every
 * behavior (seeding from config, selection collection, deletion-point
 * mapping, summary text) is unit-testable without constructing a
 * `JDialog`, which throws `HeadlessException` in a headless JVM.
 */
object WelchDialogLogic {

    /** Per-type default discretizing interval: batch size for tally
     *  responses, delta-t for time-weighted responses. */
    fun defaultInterval(isTimeWeighted: Boolean): Double = if (isTimeWeighted) 10.0 else 1.0

    /**
     * One row per probed response.  A response already present in
     * [config]'s `welchResponses` is pre-selected with its stored
     * interval; every other response is unselected with the per-type
     * default interval ([defaultInterval]).  Row order follows
     * [responses] (the probe order).
     */
    fun initialRows(config: OutputConfig, responses: List<ResponseProbe>): List<WelchRowState> {
        val byName = config.welchResponses.associateBy { it.responseName }
        return responses.map { probe ->
            val existing = byName[probe.name]
            WelchRowState(
                name = probe.name,
                isTimeWeighted = probe.isTimeWeighted,
                selected = existing != null,
                interval = existing?.interval ?: defaultInterval(probe.isTimeWeighted)
            )
        }
    }

    /** The selected rows as persisted specs, preserving row order. */
    fun selectedSpecs(rows: List<WelchRowState>): List<WelchResponseSpec> =
        rows.filter { it.selected }.map { WelchResponseSpec(it.name, it.interval) }

    /** Read-only one-liner for the Run Control summary label. */
    fun summary(config: OutputConfig): String =
        if (!config.enableWelchAnalysis || config.welchResponses.isEmpty()) {
            "off"
        } else {
            val n = config.welchResponses.size
            "$n response${if (n == 1) "" else "s"}"
        }
}

/**
 * Modal dialog for configuring Warm-Up Analysis (Welch) capture before a
 * run.  Opened from the *Output Options* section of the Run Control tab.
 *
 * OK-commit semantics: the dialog edits local widget state seeded from the
 * controller's current `OutputConfig`; **OK** pushes the whole selection in
 * one [SingleAppController.applyWelchConfig] call, **Cancel** discards.
 * Mirrors the module's `BundleModelPickerDialog` precedent.
 */
object WelchConfigDialog {

    /** Present the dialog modally.  Must be called on the Swing EDT. */
    fun show(controller: SingleAppController, owner: Window?) {
        WelchConfigDialogImpl(controller, owner).isVisible = true
    }
}

/**
 * The actual `JDialog`.  `internal` (not private) so the headless-guarded
 * smoke test can construct it, drive its widgets, and invoke [onOk]
 * without going through the blocking modal [WelchConfigDialog.show].
 */
internal class WelchConfigDialogImpl(
    private val controller: SingleAppController,
    owner: Window?
) : JDialog(owner, "Configure Warm-Up Analysis (Welch)", ModalityType.APPLICATION_MODAL) {

    private val seedConfig: OutputConfig = controller.outputConfig.value

    /** Mutable backing rows (model order = probe order); the table edits these in place. */
    private val rows: MutableList<WelchRowState> =
        WelchDialogLogic.initialRows(seedConfig, controller.responseSnapshot).toMutableList()

    internal val masterCheckBox = JCheckBox("Capture Welch warm-up data during the run").apply {
        isSelected = seedConfig.enableWelchAnalysis
        addActionListener { syncEnabled() }
    }

    private val tableModel = RowsModel()
    private val table = JTable(tableModel).apply {
        rowHeight = 22
        fillsViewportHeight = true
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        autoCreateRowSorter = true
        putClientProperty("terminateEditOnFocusLost", true)
        columnModel.getColumn(COL_CAPTURE).apply { preferredWidth = 64; maxWidth = 70 }
        columnModel.getColumn(COL_NAME).preferredWidth = 280
        columnModel.getColumn(COL_TYPE).preferredWidth = 110
        columnModel.getColumn(COL_INTERVAL).preferredWidth = 90
    }
    private val sorter = TableRowSorter(tableModel).also { table.rowSorter = it }

    private val filterField = JTextField(20).apply {
        toolTipText = "Type to filter the response list by name"
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = applyFilter()
            override fun removeUpdate(e: DocumentEvent?) = applyFilter()
            override fun changedUpdate(e: DocumentEvent?) = applyFilter()
        })
    }
    private val selectAllButton = JButton(object : AbstractAction("Select all") {
        override fun actionPerformed(e: java.awt.event.ActionEvent?) = setAllVisibleSelected(true)
    })
    private val selectNoneButton = JButton(object : AbstractAction("Select none") {
        override fun actionPerformed(e: java.awt.event.ActionEvent?) = setAllVisibleSelected(false)
    })

    init {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        isResizable = true

        contentPane.layout = BorderLayout()
        contentPane.add(buildHeader(), BorderLayout.NORTH)
        contentPane.add(buildCenter(), BorderLayout.CENTER)
        contentPane.add(buildFooter(), BorderLayout.SOUTH)

        syncEnabled()
        size = Dimension(640, 520)
        setLocationRelativeTo(owner)
    }

    private fun buildHeader(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createEmptyBorder(10, 12, 4, 12)
        add(leftRow(masterCheckBox))
        add(leftRow(JLabel(
            "<html><i>Check the box above to choose the responses to capture below.</i></html>"
        ).apply { foreground = Color(0x66, 0x66, 0x66) }))
        add(Box.createVerticalStrut(8))
        add(leftRow(boldLabel("Responses to analyze")))
        add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            add(JLabel("Filter:"))
            add(filterField)
            add(Box.createHorizontalStrut(12))
            add(selectAllButton)
            add(selectNoneButton)
        })
    }

    private fun buildCenter(): JComponent = JScrollPane(table).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createEmptyBorder(0, 12, 0, 12),
            BorderFactory.createLineBorder(Color(0xCC, 0xCC, 0xCC))
        )
    }

    private fun buildFooter(): JComponent = JPanel(BorderLayout()).apply {
        border = BorderFactory.createEmptyBorder(4, 12, 8, 12)
        add(JLabel(
            "<html><i>Captured during the run; analyzed afterward via the Post-Run " +
                "Reporting tab. Selecting fewer responses keeps runs fast.</i></html>"
        ).apply { foreground = Color(0x66, 0x66, 0x66) }, BorderLayout.NORTH)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 8)).apply {
            add(JButton(object : AbstractAction("Cancel") {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) = onCancel()
            }))
            add(JButton(object : AbstractAction("OK") {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) = onOk()
            }).also { this@WelchConfigDialogImpl.rootPane.defaultButton = it })
        }, BorderLayout.SOUTH)
    }

    private fun applyFilter() {
        val text = filterField.text.trim()
        sorter.rowFilter = if (text.isEmpty()) null
        else RowFilter.regexFilter("(?i)" + Regex.escape(text), COL_NAME)
    }

    /** Select / deselect every row currently visible under the active filter. */
    private fun setAllVisibleSelected(selected: Boolean) {
        if (table.isEditing) table.cellEditor?.stopCellEditing()
        for (viewRow in 0 until table.rowCount) {
            val modelRow = table.convertRowIndexToModel(viewRow)
            rows[modelRow] = rows[modelRow].copy(selected = selected)
            tableModel.fireTableCellUpdated(modelRow, COL_CAPTURE)
        }
    }

    /** Grey out the table + controls when the master toggle is off. */
    private fun syncEnabled() {
        val on = masterCheckBox.isSelected
        table.isEnabled = on
        filterField.isEnabled = on
        selectAllButton.isEnabled = on
        selectNoneButton.isEnabled = on
    }

    /** Collect the capture selection and apply it via one batched call. */
    internal fun onOk() {
        if (table.isEditing) table.cellEditor?.stopCellEditing()
        controller.applyWelchConfig(
            enableWelchAnalysis = masterCheckBox.isSelected,
            welchResponses = WelchDialogLogic.selectedSpecs(rows)
        )
        dispose()
    }

    private fun onCancel() = dispose()

    // ── Test seam (the headless smoke test drives rows by model index) ──────
    internal fun rowCount(): Int = rows.size
    internal fun isCaptured(row: Int): Boolean = rows[row].selected
    internal fun setCaptured(row: Int, value: Boolean) {
        tableModel.setValueAt(value, row, COL_CAPTURE)
    }

    private fun leftRow(component: JComponent): JComponent =
        JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            add(component)
        }

    private fun boldLabel(text: String): JLabel =
        JLabel(text).apply { font = font.deriveFont(Font.BOLD) }

    /**
     * Table model over [rows].  Capture (checkbox) and Interval are editable
     * while the master toggle is on; Response and Type are read-only.
     */
    private inner class RowsModel : AbstractTableModel() {
        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = 4
        override fun getColumnName(column: Int): String = when (column) {
            COL_CAPTURE -> "Capture"
            COL_NAME -> "Response"
            COL_TYPE -> "Type"
            COL_INTERVAL -> "Interval"
            else -> ""
        }
        override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
            COL_CAPTURE -> java.lang.Boolean::class.java
            COL_INTERVAL -> java.lang.Double::class.java
            else -> String::class.java
        }
        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean =
            masterCheckBox.isSelected && (columnIndex == COL_CAPTURE || columnIndex == COL_INTERVAL)

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val r = rows[rowIndex]
            return when (columnIndex) {
                COL_CAPTURE -> r.selected
                COL_NAME -> r.name
                COL_TYPE -> if (r.isTimeWeighted) "time-weighted" else "tally"
                COL_INTERVAL -> r.interval
                else -> ""
            }
        }

        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            val r = rows[rowIndex]
            rows[rowIndex] = when (columnIndex) {
                COL_CAPTURE -> r.copy(selected = aValue as? Boolean ?: r.selected)
                COL_INTERVAL -> r.copy(interval = (aValue as? Number)?.toDouble() ?: r.interval)
                else -> r
            }
            fireTableCellUpdated(rowIndex, columnIndex)
        }
    }

    private companion object {
        const val COL_CAPTURE = 0
        const val COL_NAME = 1
        const val COL_TYPE = 2
        const val COL_INTERVAL = 3
    }
}
