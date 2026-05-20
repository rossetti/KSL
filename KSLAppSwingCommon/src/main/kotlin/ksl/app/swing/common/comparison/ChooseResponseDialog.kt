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

package ksl.app.swing.common.comparison

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dialog
import java.awt.Dimension
import java.awt.Font
import java.awt.Point
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.RowFilter
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.JTableHeader
import javax.swing.table.TableCellRenderer
import javax.swing.table.TableRowSorter

/**
 *  Modal dialog for picking a single response from a potentially
 *  long list, with Excel-style per-column filter dropdowns and a
 *  live validation warning for the currently-highlighted candidate.
 *
 *  Open via [showDialog]; returns the chosen response name, or
 *  `null` when the user cancels.  The dialog never commits state to
 *  the [ComparisonSelectionModel] itself — that's the caller's job
 *  after the dialog closes successfully.
 *
 *  ## UX
 *
 *  - Three columns: *Response*, *Category*, *Recorded by*.
 *  - Each column header carries a small ▾ glyph; clicking it opens a
 *    popup with checkboxes for that column's distinct values plus
 *    (for the Response column) a substring search field.  Unchecking
 *    a value hides matching rows; the glyph turns blue while a
 *    filter is active.
 *  - Single-row selection.  The bottom strip shows a warning when
 *    the highlighted response would be invalid under the analyzer's
 *    current analysis type, but OK is *always* enabled — the user
 *    can still pick a response intending to switch analyses
 *    afterwards.
 */
object ChooseResponseDialog {

    /**
     *  One row of metadata for the picker.  Built by the caller
     *  from the analyzer's current state.
     *
     *  @property name          response name
     *  @property category      [ResponseCategory] of the response
     *  @property recordingExperiments count of experiments that record this
     *                          response among the currently-checked subset
     *  @property totalCheckedExperiments  count of experiments currently checked
     *                          (the "M" in "Recorded by N of M")
     */
    data class Row(
        val name: String,
        val category: ResponseCategory,
        val recordingExperiments: Int,
        val totalCheckedExperiments: Int
    )

    /** Result of [showDialog]. */
    sealed class Result {
        data class Chosen(val responseName: String) : Result()
        object Cancelled : Result()
    }

    /**
     *  Show the dialog over [parent] with the given [rows].
     *  [initialSelection] is highlighted on open.  When
     *  [validator] is non-null it's invoked with each newly-
     *  highlighted response name; the returned [ValidationResult]
     *  drives the warning status strip.
     *
     *  @return [Result.Chosen] when the user clicks OK with a row
     *    selected; [Result.Cancelled] when the user closes the
     *    dialog without confirming a selection.
     */
    fun showDialog(
        parent: Component,
        rows: List<Row>,
        initialSelection: String?,
        validator: ((String) -> ValidationResult)?
    ): Result {
        // SwingUtilities.getWindowAncestor walks getParent() starting
        // from parent's parent, so it returns null when parent itself
        // is the top-level Window.  Resolve directly when parent is a
        // Window; otherwise walk up.
        val owner: Window = (parent as? Window)
            ?: SwingUtilities.getWindowAncestor(parent)
            ?: return Result.Cancelled
        val dialog = PickerDialog(owner, rows, initialSelection, validator)
        dialog.isVisible = true
        return dialog.outcome
    }

    private class PickerDialog(
        owner: Window,
        private val rows: List<Row>,
        initialSelection: String?,
        private val validator: ((String) -> ValidationResult)?
    ) : JDialog(owner, "Choose Response", Dialog.ModalityType.APPLICATION_MODAL) {

        var outcome: Result = Result.Cancelled
            private set

        private val tableModel = ResponseTableModel(rows)
        private val sorter = TableRowSorter(tableModel)
        private val table = JTable(tableModel).apply {
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            rowHeight = 22
            autoCreateRowSorter = false
            rowSorter = sorter
        }

        /** Per-column "allowed values" filter state — when a column
         *  isn't present in this map, no filter is applied. */
        private val columnFilters: MutableMap<Int, Set<String>> = mutableMapOf()

        private val statusLabel = JLabel(" ").apply {
            border = BorderFactory.createEmptyBorder(4, 12, 4, 12)
            foreground = Color(0x99, 0x55, 0x00)
            font = font.deriveFont(font.size2D)
        }

        private val okButton = JButton("OK")
        private val cancelButton = JButton("Cancel")

        init {
            defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
            preferredSize = Dimension(680, 480)
            contentPane.layout = BorderLayout()
            contentPane.add(JScrollPane(table), BorderLayout.CENTER)
            contentPane.add(statusLabel, BorderLayout.NORTH)
            contentPane.add(buildButtons(), BorderLayout.SOUTH)
            rootPane.defaultButton = okButton

            applyColumnWidths()
            installFilterableHeader()
            sorter.rowFilter = composeRowFilter()

            // Honor incoming selection.
            if (initialSelection != null) {
                val idx = rows.indexOfFirst { it.name == initialSelection }
                if (idx >= 0) {
                    val viewRow = table.convertRowIndexToView(idx)
                    if (viewRow >= 0) {
                        table.setRowSelectionInterval(viewRow, viewRow)
                        table.scrollRectToVisible(table.getCellRect(viewRow, 0, true))
                    }
                }
            }

            table.selectionModel.addListSelectionListener {
                if (!it.valueIsAdjusting) refreshStatus()
            }
            refreshStatus()

            okButton.addActionListener { onOk() }
            cancelButton.addActionListener { dispose() }

            pack()
            setLocationRelativeTo(owner)
        }

        private fun applyColumnWidths() {
            val cm = table.columnModel
            cm.getColumn(COL_RESPONSE).preferredWidth = 320
            cm.getColumn(COL_CATEGORY).preferredWidth = 130
            cm.getColumn(COL_RECORDED).preferredWidth = 140
        }

        private fun buildButtons(): JComponent = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createEmptyBorder(0, 12, 12, 12)
            add(Box.createHorizontalGlue())
            add(cancelButton)
            add(Box.createHorizontalStrut(8))
            add(okButton)
        }

        private fun refreshStatus() {
            val name = currentSelection()
            if (name == null) {
                statusLabel.text = "Pick a response from the list."
                statusLabel.foreground = Color(0x99, 0x55, 0x00)
                return
            }
            val v = validator?.invoke(name)
            if (v == null || v.ok) {
                statusLabel.text = "Ready: '$name'"
                statusLabel.foreground = Color(0x33, 0x77, 0x33)
            } else {
                statusLabel.text = "⚠ ${v.reason}"
                statusLabel.foreground = Color(0x99, 0x55, 0x00)
            }
        }

        private fun currentSelection(): String? {
            val viewRow = table.selectedRow
            if (viewRow < 0) return null
            val modelRow = table.convertRowIndexToModel(viewRow)
            return rows.getOrNull(modelRow)?.name
        }

        private fun onOk() {
            val name = currentSelection()
            if (name == null) {
                statusLabel.text = "Pick a response first."
                statusLabel.foreground = Color(0x99, 0x55, 0x00)
                return
            }
            outcome = Result.Chosen(name)
            dispose()
        }

        // ── Column filter wiring ─────────────────────────────────────────

        private fun installFilterableHeader() {
            val originalRenderer = table.tableHeader.defaultRenderer
            table.tableHeader.defaultRenderer = FilterableHeaderRenderer(originalRenderer) {
                columnFilters[it]?.let { allowed -> allowed.size < distinctValues(it).size } ?: false
            }
            table.tableHeader.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val col = table.columnModel.getColumnIndexAtX(e.x)
                    if (col < 0) return
                    // Show popup just below the header cell.
                    val cellRect = table.tableHeader.getHeaderRect(col)
                    val anchor = Point(cellRect.x, cellRect.y + cellRect.height)
                    showFilterPopup(col, anchor)
                }
            })
        }

        private fun distinctValues(columnIndex: Int): List<String> =
            rows.map { tableModel.cellAsString(it, columnIndex) }.distinct().sorted()

        private fun showFilterPopup(columnIndex: Int, anchor: Point) {
            val all = distinctValues(columnIndex)
            val currentlyAllowed = columnFilters[columnIndex] ?: all.toSet()

            val popup = JPopupMenu()
            val checkBoxes = LinkedHashMap<String, JCheckBox>()

            // Optional search field — useful for the Response column;
            // shown for every column for consistency.
            val searchField = JTextField().apply {
                preferredSize = Dimension(220, preferredSize.height)
                maximumSize = Dimension(220, preferredSize.height)
            }
            val searchPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                border = BorderFactory.createEmptyBorder(4, 6, 4, 6)
                add(JLabel("Search:"))
                add(Box.createHorizontalStrut(4))
                add(searchField)
            }

            val listPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                background = Color.WHITE
                border = BorderFactory.createEmptyBorder(2, 6, 4, 6)
            }
            for (value in all) {
                val cb = JCheckBox(value, value in currentlyAllowed).apply {
                    isFocusable = false
                    background = Color.WHITE
                    alignmentX = Component.LEFT_ALIGNMENT
                }
                checkBoxes[value] = cb
                listPanel.add(cb)
            }
            val listScroll = JScrollPane(listPanel).apply {
                preferredSize = Dimension(260, 220)
                border = BorderFactory.createEmptyBorder()
            }

            val toggles = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                border = BorderFactory.createEmptyBorder(4, 6, 4, 6)
                add(JButton("Select All").apply {
                    addActionListener { checkBoxes.values.forEach { it.isSelected = true } }
                })
                add(Box.createHorizontalStrut(6))
                add(JButton("Clear").apply {
                    addActionListener { checkBoxes.values.forEach { it.isSelected = false } }
                })
                add(Box.createHorizontalGlue())
                add(JButton("Apply").apply {
                    addActionListener {
                        applyColumnFilter(columnIndex, checkBoxes)
                        popup.isVisible = false
                    }
                })
            }

            searchField.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = filter()
                override fun removeUpdate(e: DocumentEvent) = filter()
                override fun changedUpdate(e: DocumentEvent) = filter()
                private fun filter() {
                    val q = searchField.text.trim().lowercase()
                    for ((value, cb) in checkBoxes) {
                        cb.isVisible = q.isEmpty() || value.lowercase().contains(q)
                    }
                    listPanel.revalidate()
                    listPanel.repaint()
                }
            })

            popup.layout = BoxLayout(popup, BoxLayout.Y_AXIS)
            popup.add(searchPanel)
            popup.add(listScroll)
            popup.add(toggles)
            popup.show(table.tableHeader, anchor.x, anchor.y)
            SwingUtilities.invokeLater { searchField.requestFocusInWindow() }
        }

        private fun applyColumnFilter(columnIndex: Int, checkBoxes: Map<String, JCheckBox>) {
            val allowed = checkBoxes.filter { it.value.isSelected }.keys
            if (allowed.size == distinctValues(columnIndex).size) {
                columnFilters.remove(columnIndex)
            } else {
                columnFilters[columnIndex] = allowed
            }
            sorter.rowFilter = composeRowFilter()
            table.tableHeader.repaint()
            // If the previously-selected row got filtered out, clear.
            if (table.selectedRow < 0) refreshStatus()
        }

        private fun composeRowFilter(): RowFilter<AbstractTableModel, Int>? {
            if (columnFilters.isEmpty()) return null
            return object : RowFilter<AbstractTableModel, Int>() {
                override fun include(entry: Entry<out AbstractTableModel, out Int>): Boolean {
                    for ((col, allowed) in columnFilters) {
                        val value = tableModel.cellAsString(rows[entry.identifier], col)
                        if (value !in allowed) return false
                    }
                    return true
                }
            }
        }
    }

    // ── Table model ──────────────────────────────────────────────────────

    private const val COL_RESPONSE = 0
    private const val COL_CATEGORY = 1
    private const val COL_RECORDED = 2

    private class ResponseTableModel(
        private val rows: List<Row>
    ) : AbstractTableModel() {

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = 3
        override fun getColumnName(c: Int): String = when (c) {
            COL_RESPONSE -> "Response"
            COL_CATEGORY -> "Category"
            COL_RECORDED -> "Recorded by"
            else -> ""
        }
        override fun isCellEditable(r: Int, c: Int): Boolean = false
        override fun getValueAt(r: Int, c: Int): Any = cellAsString(rows[r], c)

        fun cellAsString(row: Row, column: Int): String = when (column) {
            COL_RESPONSE -> row.name
            COL_CATEGORY -> when (row.category) {
                ResponseCategory.OBSERVATION -> "Observation"
                ResponseCategory.TIME_WEIGHTED -> "Time-weighted"
                ResponseCategory.COUNTER -> "Counter"
            }
            COL_RECORDED -> "${row.recordingExperiments} of ${row.totalCheckedExperiments}"
            else -> ""
        }
    }

    // ── Header renderer with filter glyph ────────────────────────────────

    /**
     *  Wraps the default table-header renderer so each cell gets a
     *  small `▾` glyph after the column name.  The glyph turns blue
     *  when [filterActive] returns `true` for the column.
     */
    private class FilterableHeaderRenderer(
        private val delegate: TableCellRenderer,
        private val filterActive: (column: Int) -> Boolean
    ) : TableCellRenderer {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val c = delegate.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            if (c is DefaultTableCellRenderer) {
                val active = filterActive(column)
                val arrow = if (active) "▼" else "▾"
                c.text = "${value ?: ""}  $arrow"
                c.horizontalAlignment = SwingConstants.LEFT
                if (active) {
                    c.foreground = Color(0x16, 0x4A, 0xA0)
                    c.font = c.font.deriveFont(Font.BOLD)
                }
            }
            return c
        }
    }
}
