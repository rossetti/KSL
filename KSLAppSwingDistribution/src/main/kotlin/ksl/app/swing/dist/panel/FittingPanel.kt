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

package ksl.app.swing.dist.panel

import kotlinx.coroutines.launch
import ksl.app.dist.catalog.FittingCatalog
import ksl.app.dist.config.DistributionKind
import ksl.app.swing.dist.DatasetFitSettings
import ksl.app.swing.dist.DatasetRunStatus
import ksl.app.swing.dist.DistributionAppController
import ksl.app.swing.dist.RunState
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultCellEditor
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel

/**
 * Fitting tab: set up and run the fit. A per-dataset table shows each dataset's
 * include flag, kind, automatic-shift flag, estimator/scoring counts, and run
 * status; the estimators and scoring models are edited in modal dialogs opened
 * for the selected row. Bulk actions apply settings across all datasets, and
 * Fit/Cancel drive the session with per-dataset completion feedback.
 */
class FittingPanel(private val controller: DistributionAppController) : JPanel(BorderLayout()) {

    private var updating = false

    private val fitButton = JButton("▶ Fit")
    private val cancelButton = JButton("◼ Cancel")
    private val clearResultsButton = JButton("Clear results")
    private val progressBar = JProgressBar()
    private val progressLabel = JLabel(" ")

    private val bulkKindCombo = JComboBox(arrayOf("Continuous", "Discrete"))

    private val model = FitTableModel()
    private val table = JTable(model)

    private val selectedLabel = JLabel("Selected: (none)")
    private val editEstimatorsButton = JButton("Edit estimators…")
    private val editScoringButton = JButton("Edit scoring…")
    private val editBootstrapButton = JButton("Edit parameter bootstrap…")

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        add(buildTop(), BorderLayout.NORTH)
        add(buildTable(), BorderLayout.CENTER)
        add(buildDetail(), BorderLayout.SOUTH)
        wireListeners()
        bindState()
    }

    // --- construction --------------------------------------------------------

    private fun buildTop(): JComponent {
        progressBar.isVisible = false
        progressBar.preferredSize = Dimension(180, 18)
        val controlRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            add(fitButton)
            add(cancelButton)
            add(clearResultsButton)
            add(Box.createHorizontalStrut(12))
            add(progressBar)
            add(progressLabel)
        }
        val bulkRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            add(JLabel("Set kind:"))
            add(bulkKindCombo)
            add(JButton("apply to all").apply { addActionListener { applyKindToAll() } })
            add(Box.createHorizontalStrut(12))
            add(JLabel("Auto-shift:"))
            add(JButton("all").apply { addActionListener { controller.setShiftForAll(true) } })
            add(JButton("none").apply { addActionListener { controller.setShiftForAll(false) } })
            add(Box.createHorizontalStrut(12))
            add(JButton("Estimators: defaults → all").apply { addActionListener { controller.resetEstimatorsForAll() } })
            add(JButton("Scoring: defaults → all").apply { addActionListener { controller.resetScoringForAll() } })
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(leftAlign(controlRow))
            add(leftAlign(bulkRow))
        }
    }

    private fun buildTable(): JComponent {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.fillsViewportHeight = true
        table.columnModel.getColumn(0).maxWidth = 44
        table.columnModel.getColumn(3).maxWidth = 44
        table.columnModel.getColumn(2).cellEditor =
            DefaultCellEditor(JComboBox(arrayOf("Continuous", "Discrete")))
        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Datasets")
            add(JScrollPane(table), BorderLayout.CENTER)
        }
    }

    private fun buildDetail(): JComponent = JPanel(FlowLayout(FlowLayout.LEFT, 8, 6)).apply {
        add(selectedLabel)
        add(Box.createHorizontalStrut(12))
        add(editEstimatorsButton)
        add(editScoringButton)
        add(editBootstrapButton)
    }

    // --- wiring --------------------------------------------------------------

    private fun wireListeners() {
        fitButton.addActionListener { controller.fit() }
        cancelButton.addActionListener { controller.cancel() }
        clearResultsButton.addActionListener { controller.clearResults() }
        editEstimatorsButton.addActionListener { editEstimators() }
        editScoringButton.addActionListener { editScoring() }
        editBootstrapButton.addActionListener { editBootstrap() }
        table.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) updateDetailButtons() }
    }

    private fun bindState() {
        val scope = controller.edtScope
        scope.launch { controller.collection.collect { refreshTable() } }
        scope.launch { controller.settings.collect { refreshTable() } }
        scope.launch { controller.datasetStatus.collect { refreshTable() } }
        scope.launch { controller.runState.collect { renderRunState(it) } }
        scope.launch { controller.validation.collect { refreshButtons() } }

        refreshTable()
        renderRunState(controller.runState.value)
        refreshButtons()
    }

    // --- actions -------------------------------------------------------------

    private fun applyKindToAll() {
        controller.setKindForAll(parseKind(bulkKindCombo.selectedItem as String))
    }

    private fun editEstimators() {
        val name = selectedDatasetName() ?: return
        val s = controller.settings.value[name] ?: return
        val dialog = EstimatorSelectionDialog(SwingUtilities.getWindowAncestor(this), name, s.kind, s.estimatorIds)
        dialog.isVisible = true
        val choice = dialog.choice ?: return
        if (choice.applyToAll) {
            controller.setEstimatorsForAllOfKind(s.kind, choice.ids)
        } else {
            controller.setDatasetEstimators(name, choice.ids)
        }
    }

    private fun editScoring() {
        val name = selectedDatasetName() ?: return
        val s = controller.settings.value[name] ?: return
        if (s.kind == DistributionKind.DISCRETE) return
        val dialog = ScoringSelectionDialog(
            SwingUtilities.getWindowAncestor(this), name, s.kind,
            s.scoringModelIds, s.rankingMethod, s.evaluationMethod
        )
        dialog.isVisible = true
        val choice = dialog.choice ?: return
        if (choice.applyToAll) {
            controller.setScoringForAllOfKind(s.kind, choice.ids, choice.ranking, choice.evaluation)
        } else {
            controller.setDatasetScoring(name, choice.ids, choice.ranking, choice.evaluation)
        }
    }

    private fun editBootstrap() {
        val name = selectedDatasetName() ?: return
        val s = controller.settings.value[name] ?: return
        if (s.kind == DistributionKind.DISCRETE) return  // parameter bootstrap is continuous-only
        val dialog = BootstrapSelectionDialog(SwingUtilities.getWindowAncestor(this), name, s.bootstrap)
        dialog.isVisible = true
        val choice = dialog.choice ?: return
        if (choice.applyToAll) {
            controller.setBootstrapForAllOfKind(s.kind, choice.config)
        } else {
            controller.setDatasetBootstrap(name, choice.config)
        }
    }

    // --- rendering -----------------------------------------------------------

    private fun refreshTable() {
        val settings = controller.settings.value
        val status = controller.datasetStatus.value
        val rows = controller.collection.value.map { entry ->
            val s = settings[entry.name] ?: DatasetFitSettings()
            FitRow(
                name = entry.name,
                included = s.includeInFit,
                kind = s.kind,
                shift = s.automaticShift,
                estText = estText(s),
                scoringText = scoringText(s),
                bootstrapText = bootstrapText(s),
                statusText = statusText(status[entry.name] ?: DatasetRunStatus.IDLE)
            )
        }
        val selectedName = selectedDatasetName()
        withUpdating {
            model.setRows(rows)
            if (selectedName != null) {
                val idx = model.rowIndexOf(selectedName)
                if (idx >= 0) table.setRowSelectionInterval(idx, idx)
            }
        }
        updateDetailButtons()
        refreshButtons()
    }

    private fun renderRunState(state: RunState) {
        when (state) {
            RunState.Idle -> {
                progressBar.isVisible = false
                progressBar.isIndeterminate = false
                progressLabel.text = " "
            }
            is RunState.Running -> {
                progressBar.isVisible = true
                progressLabel.text = state.label
                if (state.total > 0) {
                    progressBar.isIndeterminate = false
                    progressBar.maximum = state.total
                    progressBar.value = state.current
                } else {
                    progressBar.isIndeterminate = true
                }
            }
        }
        refreshButtons()
    }

    private fun refreshButtons() {
        val idle = controller.runState.value is RunState.Idle
        fitButton.isEnabled = idle && controller.validation.value.isValid
        cancelButton.isEnabled = !idle
        clearResultsButton.isEnabled = idle && controller.datasetStatus.value.isNotEmpty()
    }

    private fun updateDetailButtons() {
        val name = selectedDatasetName()
        val s = name?.let { controller.settings.value[it] }
        selectedLabel.text = if (name != null) "Selected: $name" else "Selected: (none)"
        editEstimatorsButton.isEnabled = name != null
        editScoringButton.isEnabled = s != null && s.kind == DistributionKind.CONTINUOUS
        editBootstrapButton.isEnabled = s != null && s.kind == DistributionKind.CONTINUOUS
    }

    // --- helpers -------------------------------------------------------------

    private fun selectedDatasetName(): String? {
        val row = table.selectedRow
        return if (row in 0 until model.rowCount) model.nameAt(row) else null
    }

    private fun estText(s: DatasetFitSettings): String {
        val n = s.estimatorIds.size
        return if (s.estimatorIds == FittingCatalog.defaultEstimatorIds(s.kind)) "$n" else "$n*"
    }

    private fun scoringText(s: DatasetFitSettings): String {
        if (s.kind == DistributionKind.DISCRETE) return "n/a"
        val n = s.scoringModelIds.size
        return if (s.scoringModelIds == FittingCatalog.defaultScoringModelIds()) "$n" else "$n*"
    }

    private fun bootstrapText(s: DatasetFitSettings): String =
        if (s.kind == DistributionKind.DISCRETE) "n/a"
        else s.bootstrap?.let { "${it.sampleSize}" } ?: "—"

    private fun statusText(status: DatasetRunStatus): String = when (status) {
        DatasetRunStatus.IDLE -> "—"
        DatasetRunStatus.QUEUED -> "queued"
        DatasetRunStatus.RUNNING -> "running…"
        DatasetRunStatus.DONE -> "✓ done"
        DatasetRunStatus.FAILED -> "✗ failed"
    }

    private fun parseKind(label: String): DistributionKind =
        if (label == "Discrete") DistributionKind.DISCRETE else DistributionKind.CONTINUOUS

    private fun leftAlign(panel: JPanel): JPanel = panel.apply { alignmentX = Component.LEFT_ALIGNMENT }

    private inline fun withUpdating(block: () -> Unit) {
        val prev = updating
        updating = true
        try {
            block()
        } finally {
            updating = prev
        }
    }

    private data class FitRow(
        val name: String,
        val included: Boolean,
        val kind: DistributionKind,
        val shift: Boolean,
        val estText: String,
        val scoringText: String,
        val bootstrapText: String,
        val statusText: String
    )

    private inner class FitTableModel : AbstractTableModel() {
        private val columns = arrayOf("incl", "name", "kind", "shift", "estimators", "scoring", "bootstrap", "status")
        private var rows: List<FitRow> = emptyList()

        fun setRows(newRows: List<FitRow>) {
            rows = newRows
            fireTableDataChanged()
        }

        fun nameAt(row: Int): String = rows[row].name
        fun rowIndexOf(name: String): Int = rows.indexOfFirst { it.name == name }

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]
        override fun isCellEditable(row: Int, column: Int): Boolean = column == 0 || column == 2 || column == 3

        override fun getColumnClass(column: Int): Class<*> = when (column) {
            0, 3 -> java.lang.Boolean::class.java
            else -> java.lang.String::class.java
        }

        override fun getValueAt(row: Int, column: Int): Any {
            val r = rows[row]
            return when (column) {
                0 -> r.included
                1 -> r.name
                2 -> kindLabel(r.kind)
                3 -> r.shift
                4 -> r.estText
                5 -> r.scoringText
                6 -> r.bootstrapText
                7 -> r.statusText
                else -> ""
            }
        }

        override fun setValueAt(value: Any?, row: Int, column: Int) {
            if (updating) return
            val name = rows[row].name
            when (column) {
                0 -> if (value is Boolean) controller.setDatasetIncluded(name, value)
                2 -> if (value is String) controller.setDatasetKind(name, parseKind(value))
                3 -> if (value is Boolean) controller.setDatasetShift(name, value)
            }
        }
    }
}
