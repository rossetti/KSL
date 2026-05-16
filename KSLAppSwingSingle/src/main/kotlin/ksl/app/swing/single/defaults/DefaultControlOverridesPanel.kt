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

package ksl.app.swing.single.defaults

import ksl.app.swing.common.overridefield.SectionHeaderWithStatus
import ksl.app.swing.single.SingleAppController
import ksl.controls.ControlData
import ksl.controls.ControlType
import ksl.controls.JsonControlData
import ksl.controls.StringControlData
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultCellEditor
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.RowFilter
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter

/**
 * Default control-overrides panel for `kslSingleApp(...)`.
 *
 * Uses a [JTabbedPane] holding one tab per non-empty control
 * family (Numeric / String / JSON) so the layout scales to
 * hundreds of controls.  Each tab carries a filter strip and a
 * sortable [JTable] whose rows are the controls in that family.
 *
 * The first column on every table is *Override?* — a checkbox
 * that toggles whether the row is included in
 * [SingleAppController.controlOverrides].  When checked the
 * *Value* column edits; when unchecked the entry is removed
 * from the override export.  Sorting and filtering apply to the
 * displayed view; the underlying snapshot order is preserved.
 *
 * The whole panel hides itself when the model snapshot has no
 * controls at all.  Individual tabs only appear for non-empty
 * families.
 */
class DefaultControlOverridesPanel(
    private val controller: SingleAppController
) : JPanel(BorderLayout()) {

    private val snapshot get() = controller.controlsSnapshot
    private val tables: MutableList<JTable> = mutableListOf()
    private val filterFields: MutableList<JTextField> = mutableListOf()
    private val resetButtons: MutableList<JButton> = mutableListOf()

    init {
        border = BorderFactory.createEmptyBorder(OUTER_PADDING, OUTER_PADDING + 8, OUTER_PADDING, OUTER_PADDING + 8)
        if (snapshot.totalControls == 0) {
            isVisible = false
        } else {
            val body = JPanel(BorderLayout()).apply { isVisible = false }
            val header = SectionHeaderWithStatus(
                title = "Control Overrides",
                pathPrefix = "scenarios[0].controlOverrides",
                bus = controller.validationBus,
                scope = controller.edtScope,
                initiallyExpanded = false,
                onToggle = { expanded -> body.isVisible = expanded; revalidate(); repaint() }
            )
            val tabs = JTabbedPane()
            if (snapshot.numericControls.isNotEmpty()) {
                tabs.addTab(tabTitle("Numeric", snapshot.numericControls.size), numericTab())
            }
            if (snapshot.stringControls.isNotEmpty()) {
                tabs.addTab(tabTitle("String", snapshot.stringControls.size), stringTab())
            }
            if (snapshot.jsonControls.isNotEmpty()) {
                tabs.addTab(tabTitle("JSON", snapshot.jsonControls.size), jsonTab())
            }
            body.add(tabs, BorderLayout.CENTER)
            add(header, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        for (t in tables) t.isEnabled = enabled
        for (f in filterFields) f.isEnabled = enabled
        for (b in resetButtons) b.isEnabled = enabled
    }

    // ── Tab construction ───────────────────────────────────────────────────

    private fun numericTab(): JComponent {
        val model = NumericControlTableModel(snapshot.numericControls, controller)
        val table = makeTable(model)
        table.columnModel.getColumn(NumericControlTableModel.COL_VALUE).cellEditor =
            NumericValueCellEditor(model)
        return tabContainer("Numeric", table, model)
    }

    private fun stringTab(): JComponent {
        val model = StringControlTableModel(snapshot.stringControls, controller)
        val table = makeTable(model)
        table.columnModel.getColumn(StringControlTableModel.COL_VALUE).cellEditor =
            StringValueCellEditor(model)
        return tabContainer("String", table, model)
    }

    private fun jsonTab(): JComponent {
        val model = JsonControlTableModel(snapshot.jsonControls, controller)
        val table = makeTable(model)
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2 || !table.isEnabled) return
                val viewCol = table.columnAtPoint(e.point)
                val viewRow = table.rowAtPoint(e.point)
                if (viewRow < 0 || viewCol != JsonControlTableModel.COL_VALUE) return
                val modelRow = table.convertRowIndexToModel(viewRow)
                if (!model.isOverridden(modelRow)) return
                val data = model.dataAt(modelRow)
                val current = model.effectiveValue(modelRow)
                val updated = JsonValueEditorDialog.show(
                    owner = SwingUtilities.getWindowAncestor(this@DefaultControlOverridesPanel),
                    keyName = data.keyName,
                    typeHint = data.typeHint,
                    initialJson = current
                ) ?: return
                model.setValue(modelRow, updated)
            }
        })
        return tabContainer("JSON", table, model)
    }

    private fun tabContainer(familyLabel: String, table: JTable, model: OverrideTableModel): JComponent {
        val filterField = JTextField().apply {
            toolTipText = "Type to filter rows by any visible text"
            preferredSize = Dimension(220, preferredSize.height)
            maximumSize = Dimension(220, preferredSize.height)
        }
        val resetBtn = JButton("Reset all in $familyLabel").apply {
            toolTipText = "Clear every override in this family"
            addActionListener { model.resetAllOverrides() }
        }
        filterFields.add(filterField)
        resetButtons.add(resetBtn)

        val sorter = TableRowSorter<AbstractTableModel>(model)
        table.rowSorter = sorter
        filterField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = update()
            override fun removeUpdate(e: DocumentEvent) = update()
            override fun changedUpdate(e: DocumentEvent) = update()
            private fun update() {
                val q = filterField.text
                sorter.rowFilter = if (q.isBlank()) null
                else RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(q))
            }
        })

        val northStrip = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
            add(JLabel("Filter:").apply { border = BorderFactory.createEmptyBorder(0, 0, 0, 6) })
            add(filterField)
            add(Box.createHorizontalGlue())
            add(resetBtn)
        }
        val scroll = JScrollPane(table).apply {
            preferredSize = Dimension(0, 220)
        }
        return JPanel(BorderLayout()).apply {
            add(northStrip, BorderLayout.NORTH)
            add(scroll, BorderLayout.CENTER)
        }
    }

    private fun makeTable(model: OverrideTableModel): JTable {
        val table = JTable(model).apply {
            autoCreateRowSorter = false
            fillsViewportHeight = true
            rowHeight = 22
            putClientProperty("terminateEditOnFocusLost", true)
        }
        model.applyColumnWidths(table)
        tables.add(table)
        return table
    }

    private fun tabTitle(label: String, count: Int): String = "$label ($count)"

    companion object {
        private const val OUTER_PADDING: Int = 4
    }
}

// ── Shared base table model ────────────────────────────────────────────────

internal abstract class OverrideTableModel : AbstractTableModel() {
    abstract fun isOverridden(modelRow: Int): Boolean
    abstract fun resetAllOverrides()
    abstract fun applyColumnWidths(table: JTable)
}

// ── Numeric family ─────────────────────────────────────────────────────────

internal class NumericControlTableModel(
    private val controls: List<ControlData>,
    private val controller: SingleAppController
) : OverrideTableModel() {

    private val columns = listOf("Override?", "Key", "Element", "Parent", "Type", "Default", "Value", "Bounds", "Comment")

    override fun getRowCount(): Int = controls.size
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
        val c = controls[rowIndex]
        return when (columnIndex) {
            COL_OVERRIDE -> isOverridden(rowIndex)
            COL_KEY -> c.keyName
            COL_ELEMENT -> c.elementName
            COL_PARENT -> c.parentElementName ?: "—"
            COL_TYPE -> c.controlType.name
            COL_DEFAULT -> formatNumeric(c, c.value)
            COL_VALUE -> {
                val override = controller.controlOverrides.value.numericControls.firstOrNull { it.keyName == c.keyName }
                if (override == null) "" else formatNumeric(c, override.value)
            }
            COL_BOUNDS -> "[${c.lowerBound}, ${c.upperBound}]"
            COL_COMMENT -> c.comment
            else -> null
        }
    }

    override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
        val c = controls[rowIndex]
        when (columnIndex) {
            COL_OVERRIDE -> {
                val checked = value as? Boolean ?: false
                if (checked) controller.setNumericOverride(c.keyName, c.value)
                else controller.clearNumericOverride(c.keyName)
                fireTableRowsUpdated(rowIndex, rowIndex)
            }
            COL_VALUE -> {
                val parsed = (value as? String)?.trim()?.toDoubleOrNull()
                if (parsed != null) {
                    controller.setNumericOverride(c.keyName, parsed)
                    fireTableRowsUpdated(rowIndex, rowIndex)
                }
            }
        }
    }

    override fun isOverridden(modelRow: Int): Boolean {
        val key = controls[modelRow].keyName
        return controller.controlOverrides.value.numericControls.any { it.keyName == key }
    }

    override fun resetAllOverrides() {
        for (c in controls) controller.clearNumericOverride(c.keyName)
        fireTableDataChanged()
    }

    fun dataAt(modelRow: Int): ControlData = controls[modelRow]

    fun effectiveValue(modelRow: Int): String {
        val c = controls[modelRow]
        val override = controller.controlOverrides.value.numericControls.firstOrNull { it.keyName == c.keyName }
        return formatNumeric(c, override?.value ?: c.value)
    }

    private fun formatNumeric(c: ControlData, value: Double): String = when (c.controlType) {
        ControlType.BOOLEAN -> if (value >= 0.5) "true" else "false"
        ControlType.DOUBLE, ControlType.FLOAT -> {
            val str = "%g".format(value)
            // strip trailing zeros after decimal point, but only when a decimal point is present
            if ('.' in str || 'e' in str.lowercase()) str.trimEnd('0').trimEnd('.') else str
        }
        else -> value.toInt().toString()
    }

    override fun applyColumnWidths(table: JTable) {
        applyWidth(table, COL_OVERRIDE, 70)
        applyWidth(table, COL_KEY, 220)
        applyWidth(table, COL_ELEMENT, 140)
        applyWidth(table, COL_PARENT, 140)
        applyWidth(table, COL_TYPE, 80)
        applyWidth(table, COL_DEFAULT, 80)
        applyWidth(table, COL_VALUE, 100)
        applyWidth(table, COL_BOUNDS, 120)
        applyWidth(table, COL_COMMENT, 240)
    }

    companion object {
        const val COL_OVERRIDE: Int = 0
        const val COL_KEY: Int = 1
        const val COL_ELEMENT: Int = 2
        const val COL_PARENT: Int = 3
        const val COL_TYPE: Int = 4
        const val COL_DEFAULT: Int = 5
        const val COL_VALUE: Int = 6
        const val COL_BOUNDS: Int = 7
        const val COL_COMMENT: Int = 8
    }
}

// ── String family ──────────────────────────────────────────────────────────

internal class StringControlTableModel(
    private val controls: List<StringControlData>,
    private val controller: SingleAppController
) : OverrideTableModel() {

    private val columns = listOf("Override?", "Key", "Element", "Parent", "Allowed values", "Default", "Value", "Comment")

    override fun getRowCount(): Int = controls.size
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
        val c = controls[rowIndex]
        return when (columnIndex) {
            COL_OVERRIDE -> isOverridden(rowIndex)
            COL_KEY -> c.keyName
            COL_ELEMENT -> c.elementName
            COL_PARENT -> c.parentElementName ?: "—"
            COL_ALLOWED -> if (c.allowedValues.isEmpty()) "(free text)" else c.allowedValues.joinToString(", ")
            COL_DEFAULT -> c.value
            COL_VALUE -> controller.controlOverrides.value.stringControls.firstOrNull { it.keyName == c.keyName }?.value ?: ""
            COL_COMMENT -> c.comment
            else -> null
        }
    }

    override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
        val c = controls[rowIndex]
        when (columnIndex) {
            COL_OVERRIDE -> {
                val checked = value as? Boolean ?: false
                if (checked) controller.setStringOverride(c.keyName, c.value)
                else controller.clearStringOverride(c.keyName)
                fireTableRowsUpdated(rowIndex, rowIndex)
            }
            COL_VALUE -> {
                val text = (value as? String) ?: return
                controller.setStringOverride(c.keyName, text)
                fireTableRowsUpdated(rowIndex, rowIndex)
            }
        }
    }

    override fun isOverridden(modelRow: Int): Boolean {
        val key = controls[modelRow].keyName
        return controller.controlOverrides.value.stringControls.any { it.keyName == key }
    }

    override fun resetAllOverrides() {
        for (c in controls) controller.clearStringOverride(c.keyName)
        fireTableDataChanged()
    }

    fun allowedValues(modelRow: Int): List<String> = controls[modelRow].allowedValues

    override fun applyColumnWidths(table: JTable) {
        applyWidth(table, COL_OVERRIDE, 70)
        applyWidth(table, COL_KEY, 220)
        applyWidth(table, COL_ELEMENT, 140)
        applyWidth(table, COL_PARENT, 140)
        applyWidth(table, COL_ALLOWED, 200)
        applyWidth(table, COL_DEFAULT, 120)
        applyWidth(table, COL_VALUE, 160)
        applyWidth(table, COL_COMMENT, 240)
    }

    companion object {
        const val COL_OVERRIDE: Int = 0
        const val COL_KEY: Int = 1
        const val COL_ELEMENT: Int = 2
        const val COL_PARENT: Int = 3
        const val COL_ALLOWED: Int = 4
        const val COL_DEFAULT: Int = 5
        const val COL_VALUE: Int = 6
        const val COL_COMMENT: Int = 7
    }
}

// ── JSON family ────────────────────────────────────────────────────────────

internal class JsonControlTableModel(
    private val controls: List<JsonControlData>,
    private val controller: SingleAppController
) : OverrideTableModel() {

    private val columns = listOf("Override?", "Key", "Element", "Parent", "Type hint", "Default", "Value", "Comment")

    override fun getRowCount(): Int = controls.size
    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(column: Int): String = columns[column]

    override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
        COL_OVERRIDE -> java.lang.Boolean::class.java
        else -> String::class.java
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnIndex == COL_OVERRIDE

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
        val c = controls[rowIndex]
        return when (columnIndex) {
            COL_OVERRIDE -> isOverridden(rowIndex)
            COL_KEY -> c.keyName
            COL_ELEMENT -> c.elementName
            COL_PARENT -> c.parentElementName ?: "—"
            COL_TYPE_HINT -> c.typeHint
            COL_DEFAULT -> truncate(c.jsonValue)
            COL_VALUE -> {
                val override = controller.controlOverrides.value.jsonControls.firstOrNull { it.keyName == c.keyName }
                if (override == null) "" else truncate(override.jsonValue)
            }
            COL_COMMENT -> c.comment
            else -> null
        }
    }

    override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
        if (columnIndex != COL_OVERRIDE) return
        val c = controls[rowIndex]
        val checked = value as? Boolean ?: false
        if (checked) controller.setJsonOverride(c.keyName, c.jsonValue)
        else controller.clearJsonOverride(c.keyName)
        fireTableRowsUpdated(rowIndex, rowIndex)
    }

    override fun isOverridden(modelRow: Int): Boolean {
        val key = controls[modelRow].keyName
        return controller.controlOverrides.value.jsonControls.any { it.keyName == key }
    }

    override fun resetAllOverrides() {
        for (c in controls) controller.clearJsonOverride(c.keyName)
        fireTableDataChanged()
    }

    fun dataAt(modelRow: Int): JsonControlData = controls[modelRow]

    fun effectiveValue(modelRow: Int): String {
        val c = controls[modelRow]
        return controller.controlOverrides.value.jsonControls.firstOrNull { it.keyName == c.keyName }?.jsonValue
            ?: c.jsonValue
    }

    fun setValue(modelRow: Int, jsonValue: String) {
        val c = controls[modelRow]
        controller.setJsonOverride(c.keyName, jsonValue)
        fireTableRowsUpdated(modelRow, modelRow)
    }

    private fun truncate(s: String, maxLen: Int = 60): String =
        if (s.length <= maxLen) s else s.substring(0, maxLen - 1) + "…"

    override fun applyColumnWidths(table: JTable) {
        applyWidth(table, COL_OVERRIDE, 70)
        applyWidth(table, COL_KEY, 220)
        applyWidth(table, COL_ELEMENT, 140)
        applyWidth(table, COL_PARENT, 140)
        applyWidth(table, COL_TYPE_HINT, 160)
        applyWidth(table, COL_DEFAULT, 220)
        applyWidth(table, COL_VALUE, 220)
        applyWidth(table, COL_COMMENT, 240)
    }

    companion object {
        const val COL_OVERRIDE: Int = 0
        const val COL_KEY: Int = 1
        const val COL_ELEMENT: Int = 2
        const val COL_PARENT: Int = 3
        const val COL_TYPE_HINT: Int = 4
        const val COL_DEFAULT: Int = 5
        const val COL_VALUE: Int = 6
        const val COL_COMMENT: Int = 7
    }
}

// ── Cell editors ───────────────────────────────────────────────────────────

private class NumericValueCellEditor(private val model: NumericControlTableModel) :
    DefaultCellEditor(JTextField()) {

    init { clickCountToStart = 1 }

    override fun getTableCellEditorComponent(
        table: JTable, value: Any?, isSelected: Boolean, row: Int, column: Int
    ): Component {
        val modelRow = table.convertRowIndexToModel(row)
        val data = model.dataAt(modelRow)
        val tf = component as JTextField
        tf.text = model.effectiveValue(modelRow)
        tf.toolTipText = "Range: [${data.lowerBound}, ${data.upperBound}]"
        return tf
    }
}

private class StringValueCellEditor(private val model: StringControlTableModel) :
    DefaultCellEditor(JTextField()) {

    private val textField: JTextField = component as JTextField
    private val comboHolder: JComboBox<String> = JComboBox<String>().apply { isEditable = false }

    init { clickCountToStart = 1 }

    override fun getTableCellEditorComponent(
        table: JTable, value: Any?, isSelected: Boolean, row: Int, column: Int
    ): Component {
        val modelRow = table.convertRowIndexToModel(row)
        val allowed = model.allowedValues(modelRow)
        val current = (value as? String).orEmpty()
        return if (allowed.isNotEmpty()) {
            comboHolder.model = javax.swing.DefaultComboBoxModel(allowed.toTypedArray())
            comboHolder.selectedItem = current.takeIf { it in allowed } ?: allowed.first()
            comboHolder
        } else {
            textField.text = current
            textField
        }
    }

    override fun getCellEditorValue(): Any {
        // When the combo was last shown, return its selection; otherwise return the text-field value.
        val combo = comboHolder
        return if (combo.isShowing) combo.selectedItem?.toString().orEmpty() else textField.text
    }
}

private fun applyWidth(table: JTable, column: Int, width: Int) {
    val tc = table.columnModel.getColumn(column)
    tc.preferredWidth = width
    tc.minWidth = (width * 0.5).toInt().coerceAtLeast(40)
}
