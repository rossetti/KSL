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
import ksl.app.bundle.KSLAppKind
import ksl.app.swing.bundle.BundleWorkbenchController.ModelView
import ksl.app.swing.common.layout.WrapLayout
import ksl.simulation.ModelDescriptor
import java.awt.BorderLayout
import java.awt.Component
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.JTree
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * The Models tab — a **master table over every model in the bundle**. The table is
 * the selection driver (the Catalog tab follows the highlighted row, so the window
 * no longer needs a global model combo). Each row shows the model id, display name,
 * builder class, and a checkbox per [KSLAppKind] (`supportedApps`).
 *
 * Editing:
 *  - **Display name** and the **app checkboxes** are edited in-cell.
 *  - The **Bulk** toolbar adds/removes the checked apps across all selected rows.
 *  - **Double-click** a row for a detail dialog: rename the model id and inspect the
 *    descriptor (controls / RV parameters / responses, grouped and collapsed).
 *
 * All edits push back through [BundleWorkbenchController.updateModel]; the table is
 * rebuilt from the resulting `models` emission, preserving the selected model id.
 */
class ModelMetadataPanel(
    private val controller: BundleWorkbenchController
) : JPanel(BorderLayout()) {

    private val appKinds = KSLAppKind.entries
    private val tableModel = ModelsTableModel()
    private val table = JTable(tableModel).apply {
        selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        autoResizeMode = JTable.AUTO_RESIZE_OFF
        rowHeight = 22
    }

    /** Suppresses selection/edit callbacks while the table is rebuilt from a flow emission. */
    private var updating = false

    private val bulkChecks: Map<KSLAppKind, JCheckBox> =
        appKinds.associateWith { JCheckBox(it.name).apply { toolTipText = appTooltip(it) } }

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        add(buildBulkToolbar(), BorderLayout.NORTH)
        add(JScrollPane(table), BorderLayout.CENTER)
        add(legend(), BorderLayout.SOUTH)

        // Selecting a row drives the global selection so the Catalog tab follows.
        table.selectionModel.addListSelectionListener { e ->
            if (e.valueIsAdjusting || updating) return@addListSelectionListener
            tableModel.rows.getOrNull(table.selectionModel.leadSelectionIndex)?.let {
                controller.selectModel(it.modelId)
            }
        }
        // Double-click opens the per-model detail dialog.
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    tableModel.rows.getOrNull(table.rowAtPoint(e.point))?.let { openDetail(it) }
                }
            }
        })

        controller.scope.launch(Dispatchers.Swing) { controller.models.collect { setRows(it) } }
        controller.scope.launch(Dispatchers.Swing) {
            controller.selectedModelId.collect { id -> selectRowFor(id) }
        }
    }

    // ── bulk toolbar ────────────────────────────────────────────────────────

    // WrapLayout (not BoxLayout) so the strip reflows to additional rows — and the
    // NORTH region grows to show them — when the window is too narrow for one row.
    private fun buildBulkToolbar(): JComponent = JPanel(WrapLayout(hgap = 6, vgap = 4)).apply {
        border = BorderFactory.createEmptyBorder(0, 0, 6, 0)
        add(JLabel("Bulk-edit supported apps (tick apps, select rows, then):"))
        bulkChecks.values.forEach { add(it) }
        add(JButton("Add to selected rows").apply {
            toolTipText = "Add the ticked apps to every selected model row."
            addActionListener { bulkApply(add = true) }
        })
        add(JButton("Remove from selected rows").apply {
            toolTipText = "Remove the ticked apps from every selected model row."
            addActionListener { bulkApply(add = false) }
        })
        add(JLabel("    In bundle:"))
        add(JButton("Include all").apply {
            toolTipText = "Mark every model for inclusion in the assembled bundle."
            addActionListener { controller.setAllIncluded(true) }
        })
        add(JButton("Exclude all").apply {
            toolTipText = "Drop every model from the assembled bundle (the builders stay in the JAR)."
            addActionListener { controller.setAllIncluded(false) }
        })
    }

    private fun bulkApply(add: Boolean) {
        val kinds = bulkChecks.filterValues { it.isSelected }.keys
        if (kinds.isEmpty()) return
        val ids = table.selectedRows.toList().mapNotNull { idx -> tableModel.rows.getOrNull(idx)?.modelId }
        ids.forEach { id ->
            controller.updateModel(id) {
                it.copy(supportedApps = if (add) it.supportedApps + kinds else it.supportedApps - kinds)
            }
        }
    }

    private fun legend(): JComponent = JLabel(
        "Select a row to edit it in the Catalog tab · edit the display name and app boxes in place · " +
            "uncheck In bundle to leave a model out of the assembled bundle · double-click a row to rename and inspect it"
    ).apply { border = BorderFactory.createEmptyBorder(4, 2, 0, 2) }

    // ── table population ──────────────────────────────────────────────────────

    private fun setRows(models: List<ModelView>) {
        updating = true
        try {
            tableModel.rows = models
            tableModel.fireTableDataChanged()
            selectRowFor(controller.selectedModelId.value)
        } finally {
            updating = false
        }
    }

    private fun selectRowFor(id: String?) {
        val idx = tableModel.rows.indexOfFirst { it.modelId == id }
        val wasUpdating = updating
        updating = true
        try {
            if (idx >= 0) {
                if (table.selectionModel.leadSelectionIndex != idx) table.setRowSelectionInterval(idx, idx)
            } else {
                table.clearSelection()
            }
        } finally {
            updating = wasUpdating
        }
    }

    // ── detail dialog (double-click) ──────────────────────────────────────────

    private fun openDetail(row: ModelView) {
        // Make this the current model so its descriptor is available for the info tree.
        controller.selectModel(row.modelId)
        val descriptor = controller.currentDescriptor.value

        val modelId = JTextField(row.modelId, 24).apply {
            toolTipText = "Filesystem-safe id, unique within the bundle (used as the in-JAR directory name)."
        }
        val displayName = JTextField(row.displayName, 24).apply {
            toolTipText = "Human-readable model name shown in pickers."
        }
        val appChecks = appKinds.associateWith { kind ->
            JCheckBox(kind.name, row.supportedApps.contains(kind)).apply { toolTipText = appTooltip(kind) }
        }

        val form = JPanel(GridBagLayout())
        var r = 0
        fun add(label: String, comp: JComponent) {
            form.add(JLabel(label), GridBagConstraints().apply {
                gridx = 0; gridy = r; anchor = GridBagConstraints.WEST; insets = Insets(4, 4, 4, 8)
            })
            form.add(comp, GridBagConstraints().apply {
                gridx = 1; gridy = r; anchor = GridBagConstraints.WEST; fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0; insets = Insets(4, 0, 4, 4)
            })
            r++
        }
        add("Builder class:", JLabel(row.builderClass))
        add("Model id:", modelId)
        add("Display name:", displayName)
        add("Supported apps:", JPanel().apply { appChecks.values.forEach { add(it) } })

        val infoTree = JTree(descriptor?.let { buildInfoRoot(it) } ?: DefaultMutableTreeNode()).apply {
            isRootVisible = false
            showsRootHandles = true
        }
        val content = JPanel(BorderLayout(0, 6)).apply {
            add(form, BorderLayout.NORTH)
            add(JPanel(BorderLayout()).apply {
                border = BorderFactory.createTitledBorder("Model information")
                add(JScrollPane(infoTree), BorderLayout.CENTER)
            }, BorderLayout.CENTER)
            preferredSize = java.awt.Dimension(560, 460)
        }

        val ok = JOptionPane.showConfirmDialog(
            this, content, "Model — ${row.modelId}",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
        ) == JOptionPane.OK_OPTION
        if (ok) {
            controller.updateModel(row.modelId) {
                it.copy(
                    modelId = modelId.text.trim(),
                    displayName = displayName.text.trim(),
                    supportedApps = appChecks.filterValues { box -> box.isSelected }.keys.toSet(),
                )
            }
        }
    }

    // ── read-only descriptor detail tree ──────────────────────────────────────

    /** Builds the (invisible-root) detail tree: family → parent element → items. */
    private fun buildInfoRoot(d: ModelDescriptor): DefaultMutableTreeNode {
        val root = DefaultMutableTreeNode()
        addControlFamily(root, "Numeric controls", d.controls.numericControls, { it.elementName }, { it.propertyName })
        addControlFamily(root, "String controls", d.controls.stringControls, { it.elementName }, { it.propertyName })
        addControlFamily(root, "JSON controls", d.controls.jsonControls, { it.elementName }, { it.propertyName })

        val rv = d.rvParameterData
        if (rv.isNotEmpty()) {
            val header = DefaultMutableTreeNode("RV parameters (${rv.size})")
            rv.groupBy { it.parentElementName ?: it.rvName }.toSortedMap().forEach { (parent, group) ->
                val node = DefaultMutableTreeNode(parent)
                group.sortedBy { "${it.rvName}.${it.paramName}" }
                    .forEach { node.add(DefaultMutableTreeNode("${it.rvName}: ${it.paramName}")) }
                header.add(node)
            }
            root.add(header)
        }

        if (d.responseNames.isNotEmpty()) {
            val header = DefaultMutableTreeNode("Responses (${d.responseNames.size})")
            d.responseNames.sorted().forEach { header.add(DefaultMutableTreeNode(it)) }
            root.add(header)
        }
        return root
    }

    private fun <T> addControlFamily(
        root: DefaultMutableTreeNode,
        title: String,
        items: List<T>,
        element: (T) -> String,
        prop: (T) -> String,
    ) {
        if (items.isEmpty()) return
        val header = DefaultMutableTreeNode("$title (${items.size})")
        items.groupBy(element).toSortedMap().forEach { (elem, group) ->
            val node = DefaultMutableTreeNode(elem)
            group.sortedBy(prop).forEach { node.add(DefaultMutableTreeNode(prop(it))) }
            header.add(node)
        }
        root.add(header)
    }

    private fun appTooltip(kind: KSLAppKind): String = when (kind) {
        KSLAppKind.SINGLE -> "Single-run app: run one configuration of this model."
        KSLAppKind.SCENARIO -> "Scenario app: a batch of named control-setting scenarios."
        KSLAppKind.EXPERIMENT -> "Experiment app: a designed experiment (needs ≥ 2 numeric factors)."
        KSLAppKind.SIMOPT -> "Simulation-optimization app (needs numeric inputs with bounds and an objective response)."
    }

    // ── table model ───────────────────────────────────────────────────────────

    /**
     * Columns: model id (read-only), display name (editable), builder class
     * (read-only), then one editable boolean column per [KSLAppKind]. Edits delegate
     * to [BundleWorkbenchController.updateModel].
     */
    private inner class ModelsTableModel : AbstractTableModel() {
        var rows: List<ModelView> = emptyList()

        // Column 0 is the "In bundle" include toggle; the model-id/name/class are
        // string columns; the trailing columns are one app-support checkbox per kind.
        private val fixedColumns = listOf("In bundle", "Model id", "Display name", "Builder class")
        private val appColumnBase = fixedColumns.size

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = fixedColumns.size + appKinds.size

        override fun getColumnName(column: Int): String =
            fixedColumns.getOrNull(column) ?: appKinds[column - appColumnBase].name

        override fun getColumnClass(columnIndex: Int): Class<*> = when {
            columnIndex == 0 -> java.lang.Boolean::class.java          // In bundle
            columnIndex >= appColumnBase -> java.lang.Boolean::class.java
            else -> String::class.java
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean =
            columnIndex == 0 || columnIndex == 2 || columnIndex >= appColumnBase

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val row = rows[rowIndex]
            return when (columnIndex) {
                0 -> row.included
                1 -> row.modelId
                2 -> row.displayName
                3 -> row.builderClass
                else -> row.supportedApps.contains(appKinds[columnIndex - appColumnBase])
            }
        }

        override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
            if (updating) return
            val row = rows.getOrNull(rowIndex) ?: return
            when {
                columnIndex == 0 -> controller.setIncluded(row.modelId, value as? Boolean ?: return)
                columnIndex == 2 -> controller.updateModel(row.modelId) {
                    it.copy(displayName = (value as? String)?.trim() ?: it.displayName)
                }
                columnIndex >= appColumnBase -> {
                    val kind = appKinds[columnIndex - appColumnBase]
                    val on = value as? Boolean ?: return
                    controller.updateModel(row.modelId) {
                        it.copy(supportedApps = if (on) it.supportedApps + kind else it.supportedApps - kind)
                    }
                }
            }
        }
    }
}
