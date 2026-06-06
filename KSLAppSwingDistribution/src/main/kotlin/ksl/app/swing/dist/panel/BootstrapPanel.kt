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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ksl.app.dist.config.DistributionKind
import ksl.app.dist.config.EvaluationMethod
import ksl.app.dist.config.FamilyBootstrapConfig
import ksl.app.dist.data.NamedDataset
import ksl.app.dist.reporting.toDocument
import ksl.app.dist.result.FamilyFrequencyResult
import ksl.app.dist.result.IntegerFrequencyCellDTO
import ksl.app.dist.runner.FittingRunner
import ksl.app.swing.common.io.openHtmlInBrowser
import ksl.app.swing.dist.DistributionAppController
import ksl.utilities.io.report.writeHtml
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.UIManager
import javax.swing.table.AbstractTableModel

/**
 * Bootstrap tab: the family-frequency bootstrap — a standalone, continuous
 * analysis (separate from the fit). The user picks a continuous dataset and a
 * resample count; the analysis re-fits each resample and tallies how often each
 * family is the recommended fit. Results show in a table and can be opened as an
 * HTML report. The analysis runs off the EDT.
 */
class BootstrapPanel(private val controller: DistributionAppController) : JPanel(BorderLayout()) {

    private val errColor = Color(0xC6, 0x28, 0x28)
    private val okColor = Color(0x2E, 0x7D, 0x32)

    private val datasetCombo = JComboBox<String>()
    private val resamplesField = JTextField("400", 6)
    private val streamField = JTextField("1", 4)
    private val runButton = JButton("Run analysis")
    private val openButton = JButton("Open in browser").apply { isEnabled = false }
    private val statusLabel = JLabel(" ")

    private val tableModel = FrequencyTableModel()
    private val table = JTable(tableModel)

    private var lastResult: FamilyFrequencyResult? = null

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        add(buildControls(), BorderLayout.NORTH)
        add(buildResult(), BorderLayout.CENTER)
        wireListeners()
        bindState()
    }

    private fun buildControls(): Component {
        val infoRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            add(JLabel("Family-frequency bootstrap — re-fits each resample and tallies the recommended family."))
        }
        val paramRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            add(JLabel("Dataset:")); add(datasetCombo)
            add(JLabel("Resamples:")); add(resamplesField)
            add(JLabel("Stream:")); add(streamField)
        }
        val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            add(runButton); add(openButton)
        }
        val statusRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply { add(statusLabel) }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(leftAlign(infoRow))
            add(leftAlign(paramRow))
            add(leftAlign(buttonRow))
            add(leftAlign(statusRow))
        }
    }

    private fun buildResult(): Component = JPanel(BorderLayout()).apply {
        border = BorderFactory.createTitledBorder("Recommended-family frequency")
        add(JScrollPane(table), BorderLayout.CENTER)
    }

    private fun wireListeners() {
        runButton.addActionListener { runAnalysis() }
        openButton.addActionListener { openInBrowser() }
    }

    private fun bindState() {
        controller.edtScope.launch { controller.collection.collect { refreshDatasets() } }
        refreshDatasets()
    }

    /** Family-frequency fits continuous families, so only continuous datasets are offered. */
    private fun refreshDatasets() {
        val settings = controller.settings.value
        val names = controller.collection.value
            .filter { (settings[it.name]?.kind ?: DistributionKind.CONTINUOUS) == DistributionKind.CONTINUOUS }
            .map { it.name }
        val prev = datasetCombo.selectedItem as? String
        datasetCombo.model = DefaultComboBoxModel(names.toTypedArray())
        if (prev != null && prev in names) datasetCombo.selectedItem = prev
        runButton.isEnabled = names.isNotEmpty()
    }

    private fun runAnalysis() {
        val name = datasetCombo.selectedItem as? String ?: return
        val entry = controller.collection.value.firstOrNull { it.name == name } ?: return
        val n = resamplesField.text.trim().toIntOrNull()
        if (n == null || n <= 0) {
            showError("Resamples must be a positive integer."); return
        }
        val stream = streamField.text.trim().toIntOrNull()
        if (stream == null || stream < 0) {
            showError("Stream must be a non-negative integer."); return
        }
        val s = controller.settings.value[name]
        statusLabel.foreground = disabledForeground()
        statusLabel.text = "Running $n resamples…"
        runButton.isEnabled = false
        openButton.isEnabled = false
        controller.edtScope.launch {
            val res = runCatching {
                withContext(Dispatchers.Default) {
                    FittingRunner.familyFrequencyBootstrap(
                        NamedDataset(name, entry.data),
                        FamilyBootstrapConfig(numSamples = n, streamNumber = stream),
                        estimatorIds = s?.estimatorIds ?: emptySet(),
                        scoringModelIds = s?.scoringModelIds ?: emptySet(),
                        evaluationMethod = s?.evaluationMethod ?: EvaluationMethod.SCORING,
                        automaticShifting = s?.automaticShift ?: true
                    )
                }
            }
            runButton.isEnabled = true
            res.onSuccess { result ->
                lastResult = result
                tableModel.setCells(result.frequency.cells.sortedByDescending { it.count })
                openButton.isEnabled = true
                statusLabel.foreground = okColor
                statusLabel.text = "Done: ${result.numSamples} resamples of '${result.datasetName}'."
            }.onFailure { t ->
                statusLabel.foreground = errColor
                statusLabel.text = "⚠ ${t.message ?: t::class.simpleName}"
            }
        }
    }

    private fun openInBrowser() {
        val result = lastResult ?: return
        controller.edtScope.launch {
            val res = runCatching {
                withContext(Dispatchers.Default) {
                    val file = controller.datasetOutputDir(result.datasetName).resolve("family-frequency.html")
                    result.toDocument().writeHtml(path = file).toPath()
                }
            }
            res.onSuccess { path ->
                statusLabel.foreground = okColor
                statusLabel.text = "Opened ${path.fileName}"
                runCatching { openHtmlInBrowser(path) }.onFailure {
                    statusLabel.foreground = errColor
                    statusLabel.text = "⚠ wrote ${path.fileName}, but could not open the browser: ${it.message}"
                }
            }.onFailure { t ->
                statusLabel.foreground = errColor
                statusLabel.text = "⚠ ${t.message ?: t::class.simpleName}"
            }
        }
    }

    private fun showError(message: String) {
        statusLabel.foreground = errColor
        statusLabel.text = "⚠ $message"
    }

    private fun disabledForeground(): Color = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
    private fun leftAlign(panel: JPanel): JPanel = panel.apply { alignmentX = Component.LEFT_ALIGNMENT }

    private inner class FrequencyTableModel : AbstractTableModel() {
        private val columns = arrayOf("Family", "Count", "Proportion")
        private var cells: List<IntegerFrequencyCellDTO> = emptyList()

        fun setCells(newCells: List<IntegerFrequencyCellDTO>) {
            cells = newCells
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = cells.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]
        override fun isCellEditable(row: Int, column: Int): Boolean = false

        override fun getValueAt(row: Int, column: Int): Any {
            val c = cells[row]
            return when (column) {
                0 -> c.cellLabel.ifBlank { c.value.toString() }
                1 -> c.count.toInt().toString()
                2 -> "%.3f".format(c.proportion)
                else -> ""
            }
        }
    }
}
