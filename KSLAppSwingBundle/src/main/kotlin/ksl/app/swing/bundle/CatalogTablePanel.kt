/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.app.swing.bundle

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import ksl.simulation.NominatedInputKind
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

/**
 * The catalog-authoring tab — a **table per family** (Controls / RV parameters /
 * Outputs). Each row is a candidate input or output; the catalog is the set of
 * **featured** rows, and **list order conveys priority** (featured rows float to the
 * top of an app's pickers in this order).
 *
 * Editing, all in place:
 *  - **Featured** checkbox catalogues a row; **typing any label auto-features it**
 *    (the catalog is one ordered list — a labelled item is, by definition, in it).
 *  - **Display name / Unit / Description** are edited in-cell.
 *  - **▲ / ▼** re-rank the selected featured row within its family.
 *
 * Featured rows are listed first (in catalog order, with their rank shown), then the
 * remaining candidates — so unlabelled, auto-named items are easy to scan and label.
 * All edits push back via [BundleWorkbenchController.updateDraft].
 */
class CatalogTablePanel(
    private val controller: BundleWorkbenchController
) : JPanel(BorderLayout()) {

    private val controlsTable = FamilyTable(
        itemHeader = "Control (element · key)",
        onFeatured = { id, on -> controller.updateDraft { it.withInputNominated(id, on) } },
        onMeta = { id, dn, unit, desc -> controller.updateDraft { it.withInputMetadata(id, dn, desc, unit) } },
        onSwap = { a, b -> controller.updateDraft { it.swapInputs(a, b) } },
    )
    private val rvTable = FamilyTable(
        itemHeader = "RV parameter (element · key)",
        onFeatured = { id, on -> controller.updateDraft { it.withInputNominated(id, on) } },
        onMeta = { id, dn, unit, desc -> controller.updateDraft { it.withInputMetadata(id, dn, desc, unit) } },
        onSwap = { a, b -> controller.updateDraft { it.swapInputs(a, b) } },
    )
    private val outputTable = FamilyTable(
        itemHeader = "Output",
        onFeatured = { id, on -> controller.updateDraft { it.withOutputNominated(id, on) } },
        onMeta = { id, dn, unit, desc -> controller.updateDraft { it.withOutputMetadata(id, dn, desc, unit) } },
        onSwap = { a, b -> controller.updateDraft { it.swapOutputs(a, b) } },
    )
    private val statusLabel = JLabel(" ")

    init {
        val featureAll = JButton("Feature all").apply {
            toolTipText = "Mark every candidate input and output as featured (a headline item)."
            addActionListener { controller.updateDraft { it.nominateAll() } }
        }
        val clear = JButton("Clear all").apply {
            toolTipText = "Un-feature everything (the catalog becomes empty); labels are kept on the rows."
            addActionListener { controller.updateDraft { it.clearNominations() } }
        }
        val toolbar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
            add(featureAll); add(Box.createHorizontalStrut(6)); add(clear)
            add(Box.createHorizontalStrut(12))
            add(JLabel("Labels & tooltips show in every app; featured order ranks pickers. Type a label to feature a row; use ▲▼ to rank."))
            add(Box.createHorizontalGlue())
        }

        val tabs = JTabbedPane().apply {
            addTab("Controls", titled("Controls (numeric / string / JSON)", controlsTable.component))
            addTab("RV parameters", titled("Random-variable parameters", rvTable.component))
            addTab("Outputs", titled("Outputs (responses + counters)", outputTable.component))
        }
        statusLabel.border = BorderFactory.createEmptyBorder(4, 8, 4, 8)

        add(toolbar, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)

        controller.scope.launch(Dispatchers.Swing) {
            controller.catalogDraft.collect { draft ->
                val inputs = draft?.inputs ?: emptyList()
                controlsTable.setRows(inputDisplays(inputs.filter { it.kind != NominatedInputKind.RV_PARAMETER }))
                rvTable.setRows(inputDisplays(inputs.filter { it.kind == NominatedInputKind.RV_PARAMETER }))
                outputTable.setRows(outputDisplays(draft?.outputs ?: emptyList()))
            }
        }
        controller.scope.launch(Dispatchers.Swing) {
            controller.catalogProblems.collect { problems ->
                statusLabel.text = if (problems.isEmpty()) "No catalog problems."
                else "${problems.size} catalog problem(s): " + problems.joinToString("; ") { it.message }
            }
        }
    }

    private fun titled(title: String, comp: Component): JPanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createTitledBorder(title)
        add(comp, BorderLayout.CENTER)
    }

    // ── row → display mapping ─────────────────────────────────────────────────

    /**
     * Featured rows first (in their underlying list order, which is the catalog
     * order), each carrying its 1-based rank; then the remaining candidates sorted by
     * element then key so unlabelled items are easy to scan and label.
     */
    private fun inputDisplays(rows: List<CatalogDraft.InputRow>): List<Display> {
        val featured = rows.filter { it.nominated }
        val rest = rows.filterNot { it.nominated }.sortedWith(compareBy({ it.element ?: "" }, { it.key }))
        val ranks = featured.withIndex().associate { (i, r) -> r.key to i + 1 }
        return (featured + rest).map { r ->
            Display(
                id = r.key,
                featured = r.nominated,
                rank = ranks[r.key],
                item = listOfNotNull(r.element?.takeIf { it.isNotBlank() }, r.key).joinToString("  ·  "),
                displayName = r.displayName,
                unit = r.unit,
                description = r.description,
            )
        }
    }

    private fun outputDisplays(rows: List<CatalogDraft.OutputRow>): List<Display> {
        val featured = rows.filter { it.nominated }
        val rest = rows.filterNot { it.nominated }.sortedBy { it.name }
        val ranks = featured.withIndex().associate { (i, r) -> r.name to i + 1 }
        return (featured + rest).map { r ->
            Display(
                id = r.name,
                featured = r.nominated,
                rank = ranks[r.name],
                item = r.name,
                displayName = r.displayName,
                unit = r.unit,
                description = r.description,
            )
        }
    }

    /** One row as shown in a family table. */
    private data class Display(
        val id: String,
        val featured: Boolean,
        val rank: Int?,
        val item: String,
        val displayName: String?,
        val unit: String?,
        val description: String?,
    )

    // ── the per-family table ──────────────────────────────────────────────────

    /**
     * A [JTable] over one family's [Display] rows with a ▲/▼ ranking toolbar. The
     * Featured / Display name / Unit / Description cells are edited in place; ▲▼ swap
     * the selected featured row with its featured neighbour. Selection is preserved by
     * id across the rebuilds each edit triggers.
     */
    private inner class FamilyTable(
        itemHeader: String,
        private val onFeatured: (String, Boolean) -> Unit,
        private val onMeta: (id: String, displayName: String?, unit: String?, description: String?) -> Unit,
        private val onSwap: (String, String) -> Unit,
    ) {
        private var displays: List<Display> = emptyList()
        private var updating = false
        private var selectedId: String? = null

        private val model = FamilyTableModel(itemHeader)
        private val table = JTable(model).apply {
            selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
            autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
            rowHeight = 22
            putClientProperty("terminateEditOnFocusLost", true)
        }

        private val upButton = JButton("▲").apply {
            toolTipText = "Move the selected featured row up (higher priority)."
            isEnabled = false
            addActionListener { moveSelected(up = true) }
        }
        private val downButton = JButton("▼").apply {
            toolTipText = "Move the selected featured row down (lower priority)."
            isEnabled = false
            addActionListener { moveSelected(up = false) }
        }

        val component: JComponent = JPanel(BorderLayout()).apply {
            add(JScrollPane(table), BorderLayout.CENTER)
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
                add(JLabel("Rank: ")); add(upButton); add(Box.createHorizontalStrut(4)); add(downButton)
                add(Box.createHorizontalGlue())
            }, BorderLayout.SOUTH)
        }

        init {
            table.selectionModel.addListSelectionListener { e ->
                if (e.valueIsAdjusting || updating) return@addListSelectionListener
                selectedId = displays.getOrNull(table.selectedRow)?.id
                refreshMoveButtons()
            }
        }

        fun setRows(rows: List<Display>) {
            updating = true
            try {
                displays = rows
                model.fireTableDataChanged()
                val idx = displays.indexOfFirst { it.id == selectedId }
                if (idx >= 0) table.setRowSelectionInterval(idx, idx) else table.clearSelection()
            } finally {
                updating = false
            }
            refreshMoveButtons()
        }

        private fun refreshMoveButtons() {
            val featured = displays.filter { it.featured }
            val i = featured.indexOfFirst { it.id == selectedId }
            upButton.isEnabled = i > 0
            downButton.isEnabled = i in 0 until featured.size - 1
        }

        private fun moveSelected(up: Boolean) {
            val id = selectedId ?: return
            val featured = displays.filter { it.featured }
            val i = featured.indexOfFirst { it.id == id }
            val j = if (up) i - 1 else i + 1
            if (i < 0 || j !in featured.indices) return
            onSwap(id, featured[j].id) // selectedId stays; setRows reselects by id
        }

        private inner class FamilyTableModel(itemHeader: String) : AbstractTableModel() {
            private val headers = listOf("Featured", "Rank", itemHeader, "Display name", "Unit", "Description")

            override fun getRowCount(): Int = displays.size
            override fun getColumnCount(): Int = headers.size
            override fun getColumnName(column: Int): String = headers[column]
            override fun getColumnClass(columnIndex: Int): Class<*> =
                if (columnIndex == 0) java.lang.Boolean::class.java else String::class.java

            override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean =
                columnIndex == 0 || columnIndex in 3..5

            override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
                val d = displays[rowIndex]
                return when (columnIndex) {
                    0 -> d.featured
                    1 -> d.rank?.toString() ?: ""
                    2 -> d.item
                    3 -> d.displayName ?: ""
                    4 -> d.unit ?: ""
                    else -> d.description ?: ""
                }
            }

            override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
                if (updating) return
                val d = displays.getOrNull(rowIndex) ?: return
                when (columnIndex) {
                    0 -> onFeatured(d.id, value as? Boolean ?: return)
                    3 -> onMeta(d.id, value as? String, d.unit, d.description)
                    4 -> onMeta(d.id, d.displayName, value as? String, d.description)
                    5 -> onMeta(d.id, d.displayName, d.unit, value as? String)
                }
            }
        }
    }
}
