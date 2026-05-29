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

package ksl.app.swing.common.editor

import ksl.app.editor.ConfigurationEditorState

import kotlinx.coroutines.launch
import ksl.utilities.random.rvariable.parameters.RVParameterData
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultCellEditor
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.RowFilter
import javax.swing.RowSorter
import javax.swing.SortOrder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter

/**
 * Default RV-overrides panel for `kslSingleApp(...)`.
 *
 * Single sortable [JTable] — random variables are one "family" (unlike
 * controls, which have three).  Each row is one parameter on one
 * parameterized `RandomVariable` (e.g. `ExponentialRV.mean`); RVs with
 * multiple parameters appear as multiple rows.
 *
 * Columns: *Override?* · *Owner* · *RV* · *Parameter* · *Value*
 * (editable when Override? is checked) · *Default* · *Type* · *Class*.
 *
 * Filter strip + Group-by-Owner toggle work the same way as the
 * control-override tabs.  The whole panel hides itself when the model
 * exposes no parameterized RVs (`state.rvSnapshot.isEmpty()`).
 *
 * Override rows pass [ksl.app.config.RVParameterOverride] objects into
 * `state.rvOverrides`, which the orchestrator hands to
 * `RVParameterSetter.changeParameters` at submit time.  Absent
 * (rvName, paramName) pairs are left at model defaults.
 */
class RVOverridesPanel(
    private val state: ConfigurationEditorState
) : JPanel(BorderLayout()) {

    private val snapshot: List<RVParameterData> get() = state.rvSnapshot
    private var table: JTable? = null
    private var filterField: JTextField? = null
    private var resetButton: JButton? = null
    private var groupCheckbox: JCheckBox? = null

    init {
        border = BorderFactory.createEmptyBorder(OUTER_PADDING, OUTER_PADDING + 8, OUTER_PADDING, OUTER_PADDING + 8)
        if (snapshot.isEmpty()) {
            isVisible = false
        } else {
            add(buildTable(), BorderLayout.CENTER)
            // Subscribe to state.rvOverrides so external state
            // changes (resetConfiguration, loadConfiguration) refresh
            // the displayed cells.  See DefaultControlOverridesPanel
            // for the same pattern and rationale.
            state.edtScope.launch {
                state.rvOverrides.collect {
                    table?.let { (it.model as? AbstractTableModel)?.fireTableDataChanged() }
                }
            }
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        table?.isEnabled = enabled
        filterField?.isEnabled = enabled
        resetButton?.isEnabled = enabled
        groupCheckbox?.isEnabled = enabled
    }

    private fun buildTable(): JPanel {
        val model = RVOverridesTableModel(snapshot, state)
        val tbl = JTable(model).apply {
            autoCreateRowSorter = false
            fillsViewportHeight = true
            rowHeight = 22
            putClientProperty("terminateEditOnFocusLost", true)
        }
        tbl.columnModel.getColumn(RVOverridesTableModel.COL_VALUE).cellEditor =
            RVValueCellEditor(model)
        model.applyColumnWidths(tbl)
        table = tbl

        val ff = JTextField().apply {
            toolTipText = "Type to filter rows by any visible text"
            preferredSize = Dimension(220, preferredSize.height)
            maximumSize = Dimension(220, preferredSize.height)
        }
        filterField = ff
        val rb = JButton("Reset all RV overrides").apply {
            toolTipText = "Clear every RV-parameter override"
            addActionListener { model.resetAllOverrides() }
        }
        resetButton = rb
        val gb = JCheckBox("Group by owner").apply {
            toolTipText =
                "Pin the Owner column as the primary sort key so secondary " +
                "sorts (by clicking other column headers) preserve the owner grouping."
        }
        groupCheckbox = gb

        val sorter = RVGroupingRowSorter(model, RVOverridesTableModel.COL_OWNER)
        tbl.rowSorter = sorter
        ff.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = update()
            override fun removeUpdate(e: DocumentEvent) = update()
            override fun changedUpdate(e: DocumentEvent) = update()
            private fun update() {
                val q = ff.text
                sorter.rowFilter = if (q.isBlank()) null
                else RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(q))
            }
        })
        gb.addActionListener { sorter.groupByOwner = gb.isSelected }

        val northStrip = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
            add(JLabel("Filter:").apply { border = BorderFactory.createEmptyBorder(0, 0, 0, 6) })
            add(ff)
            add(Box.createHorizontalGlue())
            add(gb)
            add(Box.createHorizontalStrut(8))
            add(rb)
        }
        val scroll = JScrollPane(tbl).apply {
            preferredSize = Dimension(0, 220)
        }
        return JPanel(BorderLayout()).apply {
            add(northStrip, BorderLayout.NORTH)
            add(scroll, BorderLayout.CENTER)
        }
    }

    companion object {
        private const val OUTER_PADDING: Int = 4
    }
}

// ── Table model ────────────────────────────────────────────────────────────

internal class RVOverridesTableModel(
    private val rows: List<RVParameterData>,
    private val state: ConfigurationEditorState
) : AbstractTableModel() {

    private val columns = listOf("Override?", "Owner", "RV", "Parameter", "Value", "Default", "Type", "Class")

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(column: Int): String = columns[column]

    override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
        COL_OVERRIDE -> java.lang.Boolean::class.java
        else -> String::class.java
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = when (columnIndex) {
        COL_OVERRIDE -> true
        COL_VALUE -> isOverridden(rowIndex)
        else -> false
    }

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
        val r = rows[rowIndex]
        return when (columnIndex) {
            COL_OVERRIDE -> isOverridden(rowIndex)
            COL_OWNER -> r.parentElementName ?: "—"
            COL_RV -> r.rvName
            COL_PARAM -> r.paramName
            COL_VALUE -> {
                val ov = state.rvOverrides.value.firstOrNull {
                    it.rvName == r.rvName && it.paramName == r.paramName
                }
                if (ov == null) "" else formatValue(r, ov.value)
            }
            COL_DEFAULT -> formatValue(r, r.paramValue)
            COL_TYPE -> r.dataType
            COL_CLASS -> r.clazzName
            else -> null
        }
    }

    override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
        val r = rows[rowIndex]
        when (columnIndex) {
            COL_OVERRIDE -> {
                val checked = value as? Boolean ?: false
                if (checked) state.setRVOverride(r.rvName, r.paramName, r.paramValue)
                else state.clearRVOverride(r.rvName, r.paramName)
                fireTableRowsUpdated(rowIndex, rowIndex)
            }
            COL_VALUE -> {
                val text = (value as? String)?.trim().orEmpty()
                val parsed = parseForType(r, text) ?: return
                state.setRVOverride(r.rvName, r.paramName, parsed)
                fireTableRowsUpdated(rowIndex, rowIndex)
            }
        }
    }

    fun dataAt(modelRow: Int): RVParameterData = rows[modelRow]

    /** Current effective value for the cell editor — override value if set, else snapshot default. */
    fun effectiveValue(modelRow: Int): String {
        val r = rows[modelRow]
        val ov = state.rvOverrides.value.firstOrNull {
            it.rvName == r.rvName && it.paramName == r.paramName
        }
        return formatValue(r, ov?.value ?: r.paramValue)
    }

    fun isOverridden(modelRow: Int): Boolean {
        val r = rows[modelRow]
        return state.rvOverrides.value.any {
            it.rvName == r.rvName && it.paramName == r.paramName
        }
    }

    fun resetAllOverrides() {
        for (r in rows) state.clearRVOverride(r.rvName, r.paramName)
        fireTableDataChanged()
    }

    /**
     * Parse a cell-editor string into a Double appropriate for the
     * row's dataType.  INTEGER-typed parameters require a whole number;
     * non-whole input is rejected (null return) so the edit is dropped
     * silently — consistent with how the numeric control table behaves.
     */
    private fun parseForType(r: RVParameterData, text: String): Double? {
        val asDouble = text.toDoubleOrNull() ?: return null
        if (!asDouble.isFinite()) return null
        return if (r.dataType.equals("INTEGER", ignoreCase = true)) {
            if (asDouble == asDouble.toLong().toDouble()) asDouble else null
        } else asDouble
    }

    private fun formatValue(r: RVParameterData, value: Double): String =
        if (r.dataType.equals("INTEGER", ignoreCase = true)) {
            value.toLong().toString()
        } else {
            val str = "%g".format(value)
            if ('.' in str || 'e' in str.lowercase()) str.trimEnd('0').trimEnd('.') else str
        }

    fun applyColumnWidths(table: JTable) {
        applyWidth(table, COL_OVERRIDE, 70)
        applyWidth(table, COL_OWNER, 140)
        applyWidth(table, COL_RV, 200)
        applyWidth(table, COL_PARAM, 140)
        applyWidth(table, COL_VALUE, 100)
        applyWidth(table, COL_DEFAULT, 100)
        applyWidth(table, COL_TYPE, 80)
        applyWidth(table, COL_CLASS, 160)
    }

    private fun applyWidth(table: JTable, column: Int, width: Int) {
        val tc = table.columnModel.getColumn(column)
        tc.preferredWidth = width
        tc.minWidth = (width * 0.5).toInt().coerceAtLeast(40)
    }

    companion object {
        const val COL_OVERRIDE: Int = 0
        const val COL_OWNER: Int = 1
        const val COL_RV: Int = 2
        const val COL_PARAM: Int = 3
        const val COL_VALUE: Int = 4
        const val COL_DEFAULT: Int = 5
        const val COL_TYPE: Int = 6
        const val COL_CLASS: Int = 7
    }
}

/**
 * Cell editor for the *Value* column: a text field pre-populated with
 * the current effective value (override if set, else snapshot default)
 * plus a tooltip noting the parameter's expected dataType.
 */
private class RVValueCellEditor(
    private val model: RVOverridesTableModel
) : DefaultCellEditor(JTextField()) {

    init { clickCountToStart = 1 }

    override fun getTableCellEditorComponent(
        table: JTable, value: Any?, isSelected: Boolean, row: Int, column: Int
    ): Component {
        val modelRow = table.convertRowIndexToModel(row)
        val r = model.dataAt(modelRow)
        val tf = component as JTextField
        tf.text = model.effectiveValue(modelRow)
        tf.toolTipText = "Expected type: ${r.dataType}"
        return tf
    }
}

/**
 * [TableRowSorter] that pins the *Owner* column as the primary sort
 * key when [groupByOwner] is `true`.  Mirrors `RVGroupingRowSorter` in
 * `DefaultControlOverridesPanel` — same idea, different leading
 * column.
 */
private class RVGroupingRowSorter(
    model: AbstractTableModel,
    private val ownerColumn: Int
) : TableRowSorter<AbstractTableModel>(model) {

    var groupByOwner: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                setSortKeys(sortKeys)
            }
        }

    override fun setSortKeys(keys: List<RowSorter.SortKey>?) {
        if (!groupByOwner || keys == null) {
            super.setSortKeys(keys); return
        }
        val first = keys.firstOrNull()
        val leadsWithOwner = first?.column == ownerColumn &&
            first.sortOrder == SortOrder.ASCENDING
        if (leadsWithOwner) {
            super.setSortKeys(keys)
        } else {
            val rest = keys.filterNot { it.column == ownerColumn }
            super.setSortKeys(
                listOf(RowSorter.SortKey(ownerColumn, SortOrder.ASCENDING)) + rest
            )
        }
    }
}
