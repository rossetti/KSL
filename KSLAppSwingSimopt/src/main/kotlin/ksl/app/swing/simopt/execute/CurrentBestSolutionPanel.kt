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

package ksl.app.swing.simopt.execute

import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import ksl.app.swing.common.editor.CatalogLabels
import ksl.app.swing.simopt.SimoptAppController
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.RowSorter
import javax.swing.SortOrder
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableRowSorter

/**
 * Tabular display of the best decision-variable assignment found so
 * far.
 *
 * Sourced from [SimoptAppController.latestIteration]`?.bestInputs`
 * during a live run, falling back to
 * `lastResult.bestSolution.bestSolutionSoFar.inputMap` once the run
 * terminates — the panel keeps showing the winning point after the
 * run ends instead of resetting.
 *
 * Implemented as a [JTable] (rather than stacked labels) so it
 * scales gracefully when the problem has many decision variables;
 * the table sits inside a scroll pane and offers sortable columns
 * by clicking the header.
 */
class CurrentBestSolutionPanel(
    private val controller: SimoptAppController
) : JPanel(BorderLayout()) {

    private val tableModel = object : DefaultTableModel(arrayOf("Decision variable", "Value"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == 1) java.lang.Double::class.java else String::class.java
    }
    private val table = JTable(tableModel).apply {
        autoCreateRowSorter = true
        fillsViewportHeight = true
        rowHeight = 22
        showVerticalLines = true
        showHorizontalLines = true
        gridColor = Color(0xEC, 0xEC, 0xEC)
        font = font.deriveFont(Font.PLAIN, 12f)
        // Sort by variable name ascending by default — stable display
        // independent of the snapshot's hash ordering.
        val sorter = rowSorter as? TableRowSorter<*>
        sorter?.sortKeys = listOf(RowSorter.SortKey(0, SortOrder.ASCENDING))
        columnModel.getColumn(0).preferredWidth = 220
        columnModel.getColumn(1).preferredWidth = 120
        // Label a nominated decision variable with its catalog display name + unit tooltip.
        columnModel.getColumn(0).cellRenderer = object : javax.swing.table.DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                t: JTable, value: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int
            ): java.awt.Component {
                super.getTableCellRendererComponent(t, value, sel, focus, row, col)
                val key = value as? String
                val input = key?.let { k ->
                    controller.currentModelDescriptor.value?.catalog
                        ?.nominatedInputs?.firstOrNull { it.key == k }
                }
                if (!input?.displayName.isNullOrBlank()) text = "$key   —   ${input!!.displayName}"
                toolTipText = CatalogLabels.tooltip(input)
                return this
            }
        }
        javax.swing.ToolTipManager.sharedInstance().registerComponent(this)
    }
    private val emptyPlaceholder = JLabel("<html><i>No solution yet — start the optimization.</i></html>").apply {
        foreground = Color(0x77, 0x77, 0x77)
        font = font.deriveFont(Font.PLAIN, 12f)
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
    }

    private val scrollPane = JScrollPane(table).apply {
        preferredSize = Dimension(360, 160)
        border = BorderFactory.createEmptyBorder()
    }

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Current Best Solution"),
            BorderFactory.createEmptyBorder(2, 6, 6, 6)
        )

        wireCollectors()
        refresh()
    }

    private fun wireCollectors() {
        controller.latestIteration.onEach { refresh() }.launchIn(controller.edtScope)
        controller.lastResult.onEach { refresh() }.launchIn(controller.edtScope)
    }

    private fun refresh() {
        val inputs = currentInputs()
        removeAll()
        if (inputs.isEmpty()) {
            add(emptyPlaceholder, BorderLayout.CENTER)
        } else {
            tableModel.rowCount = 0
            for ((name, value) in inputs.entries.sortedBy { it.key }) {
                tableModel.addRow(arrayOf<Any?>(name, value))
            }
            add(scrollPane, BorderLayout.CENTER)
        }
        revalidate()
        repaint()
    }

    private fun currentInputs(): Map<String, Double> {
        controller.latestIteration.value?.bestInputs?.let { if (it.isNotEmpty()) return it }
        controller.lastResult.value
            ?.bestSolution?.bestSolutionSoFar?.inputMap?.let { return it }
        return emptyMap()
    }
}
