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

package ksl.app.swing.results.panel

import ksl.app.notification.NotificationSink
import ksl.app.swing.results.ResultsAppController
import ksl.app.swing.results.ResultsExportController
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.table.DefaultTableModel

/**
 *  Experiments to-responses **metadata navigator** for the open
 *  database — deliberately not a raw-row table browser.  The left
 *  table lists the experiments available for analysis; selecting one
 *  shows its responses (name, category, replications, and whether the
 *  response was recorded as a time series) on the right.  This is the
 *  picker the analysis tabs read their experiment/response choices
 *  from.
 *
 *  All data comes from [ResultsAppController.experiments] (backed by
 *  the database comparison source) and
 *  [ResultsAppController.timeSeriesResponseNames]; the panel holds no
 *  database logic of its own.
 */
class DatabasePanel(
    private val controller: ResultsAppController,
    private val exportController: ResultsExportController,
    private val notifier: NotificationSink
) : JPanel(BorderLayout()) {

    private val hintLabel = JLabel(HINT_EMPTY)
    private val experimentsModel = readOnlyModel("Experiment", "Model", "Reps")
    private val responsesModel = readOnlyModel("Response", "Type", "Reps", "Time Series")
    private val experimentsTable = JTable(experimentsModel).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
    }
    private val responsesTable = JTable(responsesModel)
    private val exportButton = JButton("Export…").apply {
        isEnabled = false
        addActionListener { openExportDialog() }
    }

    init {
        border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        hintLabel.border = BorderFactory.createEmptyBorder(0, 0, 8, 0)
        add(hintLabel, BorderLayout.NORTH)

        val split = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            titled("Experiments", experimentsTable),
            titled("Responses", responsesTable)
        ).apply { resizeWeight = 0.45 }
        add(split, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply { add(exportButton) }, BorderLayout.SOUTH)

        experimentsTable.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) showResponsesForSelection()
        }
        controller.addListener { reload() }
    }

    private fun openExportDialog() {
        if (!exportController.canExport) {
            notifier.warn("Open a database first.")
            return
        }
        val owner = SwingUtilities.getWindowAncestor(this)
        ExportDialog(owner, exportController, notifier, controller.appWorkspace.resolve("export")).isVisible = true
    }

    private fun reload() {
        experimentsModel.rowCount = 0
        responsesModel.rowCount = 0
        val experiments = controller.experiments()
        for (exp in experiments) {
            experimentsModel.addRow(arrayOf<Any?>(exp.name, exp.modelIdentifier, exp.numReplications))
        }
        hintLabel.text = if (experiments.isEmpty()) HINT_EMPTY else HINT_LOADED
        exportButton.isEnabled = controller.isDatabaseOpen
        if (experiments.isNotEmpty()) {
            experimentsTable.setRowSelectionInterval(0, 0)
        }
    }

    private fun showResponsesForSelection() {
        responsesModel.rowCount = 0
        val row = experimentsTable.selectedRow
        if (row < 0) return
        val expName = experimentsModel.getValueAt(row, 0) as String
        val exp = controller.experiments().firstOrNull { it.name == expName } ?: return
        val timeSeriesNames = controller.timeSeriesResponseNames(expName)
        for (response in exp.responses) {
            responsesModel.addRow(
                arrayOf<Any?>(
                    response.name,
                    response.category.name,
                    exp.numReplications,
                    if (response.name in timeSeriesNames) "✓" else ""
                )
            )
        }
        // In-simulation HistogramResponse / IntegerFrequencyResponse outputs
        // live in their own tables (not within-rep stats), so list them as
        // their own rows so the analyst can see they exist.
        for (name in controller.histogramResponseNames(expName)) {
            responsesModel.addRow(arrayOf<Any?>(name, "HISTOGRAM", exp.numReplications, ""))
        }
        for (name in controller.frequencyResponseNames(expName)) {
            responsesModel.addRow(arrayOf<Any?>(name, "FREQUENCY", exp.numReplications, ""))
        }
    }

    private fun titled(title: String, table: JTable): JPanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createTitledBorder(title)
        add(JScrollPane(table), BorderLayout.CENTER)
    }

    private fun readOnlyModel(vararg columns: String): DefaultTableModel =
        object : DefaultTableModel() {
            init {
                for (c in columns) addColumn(c)
            }
            override fun isCellEditable(row: Int, column: Int): Boolean = false
        }

    private companion object {
        const val HINT_EMPTY = "Open a database to begin."
        const val HINT_LOADED =
            "Select an experiment to see its responses. " +
            "This is a metadata navigator, not a table browser — use the analysis tabs to generate reports."
    }
}
