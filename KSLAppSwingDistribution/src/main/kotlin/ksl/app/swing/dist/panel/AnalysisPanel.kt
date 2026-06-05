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
import ksl.app.swing.common.io.openHtmlInBrowser
import ksl.app.swing.dist.DistributionAppController
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.GridBagLayout
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JTextField
import javax.swing.UIManager

/**
 * Analysis (EDA) tab: per-dataset exploratory analysis. Pick a dataset, then
 * generate summary statistics, a histogram (automatic or manual binning), an
 * observations plot, an ACF plot, or a shift analysis. Each result is written
 * to an HTML file under the workspace and opened in the system browser, matching
 * the sibling apps' rendering approach.
 */
class AnalysisPanel(private val controller: DistributionAppController) : JPanel(BorderLayout()) {

    private val errColor = Color(0xC6, 0x28, 0x28)

    private var updating = false

    private val datasetCombo = JComboBox<String>()
    private val hintLabel = JLabel("Add datasets on the Data tab to enable analysis.")

    private val statisticsButton = JButton("Statistics")
    private val histogramButton = JButton("Histogram")
    private val observationsButton = JButton("Observation plot")
    private val acfButton = JButton("ACF plot")
    private val shiftButton = JButton("Shift analysis")
    private val fullReportButton = JButton("Full analysis report")
    private val analysisButtons =
        listOf(statisticsButton, histogramButton, observationsButton, acfButton, shiftButton, fullReportButton)

    private val autoRadio = JRadioButton("automatic", true)
    private val manualRadio = JRadioButton("manual")
    private val lowerField = JTextField(8)
    private val widthField = JTextField(8)
    private val numBinsField = JTextField(5)

    private val shiftLevelField = JTextField("0.95", 6)
    private val shiftSamplesField = JTextField("399", 6)

    private val statusLabel = JLabel(" ")

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        add(buildControls(), BorderLayout.NORTH)
        add(buildDescription(), BorderLayout.CENTER)
        wireListeners()
        bindState()
        updateBinningEnabled()
    }

    // --- construction --------------------------------------------------------

    private fun buildControls(): Component {
        val datasetRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            add(JLabel("Dataset:"))
            add(datasetCombo)
            add(hintLabel.apply { foreground = disabledForeground() })
        }
        val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            add(statisticsButton)
            add(histogramButton)
            add(observationsButton)
            add(acfButton)
            add(shiftButton)
        }
        val fullReportRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply { add(fullReportButton) }
        val statusRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply { add(statusLabel) }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(leftAlign(datasetRow))
            add(leftAlign(buttonRow))
            add(leftAlign(buildBinning()))
            add(leftAlign(buildShiftOptions()))
            add(leftAlign(fullReportRow))
            add(leftAlign(statusRow))
        }
    }

    private fun buildShiftOptions(): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
        border = BorderFactory.createTitledBorder("Shift analysis (confidence interval on the minimum)")
        add(JLabel("Confidence level:"))
        add(shiftLevelField)
        add(JLabel("Bootstrap samples:"))
        add(shiftSamplesField)
    }

    private fun buildBinning(): JPanel {
        ButtonGroup().apply { add(autoRadio); add(manualRadio) }
        val manualRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(JLabel("lower:"))
            add(lowerField)
            add(JLabel("bin width:"))
            add(widthField)
            add(JLabel("# bins:"))
            add(numBinsField)
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createTitledBorder("Histogram binning")
            add(leftAlign(JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
                add(autoRadio)
                add(manualRadio)
            }))
            add(leftAlign(manualRow))
        }
    }

    private fun buildDescription(): Component = JPanel(GridBagLayout()).apply {
        val label = JLabel("Results open in your web browser.")
        label.foreground = disabledForeground()
        add(label)
    }

    // --- wiring --------------------------------------------------------------

    private fun wireListeners() {
        statisticsButton.addActionListener { runAnalysis { n, d, dir -> AnalysisReports.statistics(n, d, dir) } }
        observationsButton.addActionListener { runAnalysis { n, d, dir -> AnalysisReports.observations(n, d, dir) } }
        acfButton.addActionListener { runAnalysis { n, d, dir -> AnalysisReports.acf(n, d, dir) } }
        histogramButton.addActionListener {
            val binning = currentBinning() ?: return@addActionListener
            runAnalysis { n, d, dir -> AnalysisReports.histogram(n, d, dir, binning) }
        }
        shiftButton.addActionListener {
            val ci = currentShiftCi() ?: return@addActionListener
            runAnalysis { n, d, dir -> AnalysisReports.shift(n, d, dir, ci.first, ci.second) }
        }
        fullReportButton.addActionListener {
            val binning = currentBinning() ?: return@addActionListener
            val ci = currentShiftCi() ?: return@addActionListener
            runAnalysis { n, d, dir -> AnalysisReports.fullReport(n, d, dir, binning, ci.first, ci.second) }
        }
        autoRadio.addActionListener { updateBinningEnabled() }
        manualRadio.addActionListener {
            updateBinningEnabled()
            if (manualRadio.isSelected) prefillManual()
        }
        datasetCombo.addActionListener { if (!updating) updateEnabled() }
    }

    private fun bindState() {
        controller.edtScope.launch { controller.collection.collect { refreshDatasets() } }
        refreshDatasets()
    }

    // --- actions -------------------------------------------------------------

    private fun runAnalysis(generator: (String, DoubleArray, Path) -> Path) {
        val name = datasetCombo.selectedItem as? String ?: return
        val data = dataFor(name) ?: return
        val dir = controller.datasetOutputDir(name)
        statusLabel.foreground = disabledForeground()
        statusLabel.text = "Generating…"
        controller.edtScope.launch {
            val result = runCatching { withContext(Dispatchers.Default) { generator(name, data, dir) } }
            result.onSuccess { path ->
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

    private fun currentBinning(): HistogramBinning? {
        if (autoRadio.isSelected) return HistogramBinning.Auto
        val lower = lowerField.text.trim().toDoubleOrNull()
        val width = widthField.text.trim().toDoubleOrNull()
        val numBins = numBinsField.text.trim().toIntOrNull()
        if (lower == null || width == null || numBins == null || width <= 0.0 || numBins <= 0) {
            statusLabel.foreground = errColor
            statusLabel.text = "⚠ enter a numeric lower limit, a positive bin width, and a positive # bins"
            return null
        }
        return HistogramBinning.Manual(lower, width, numBins)
    }

    private fun currentShiftCi(): Pair<Int, Double>? {
        val level = shiftLevelField.text.trim().toDoubleOrNull()
        val samples = shiftSamplesField.text.trim().toIntOrNull()
        if (level == null || level <= 0.0 || level >= 1.0 || samples == null || samples <= 0) {
            statusLabel.foreground = errColor
            statusLabel.text = "⚠ enter a confidence level in (0,1) and a positive bootstrap sample count"
            return null
        }
        return samples to level
    }

    private fun prefillManual() {
        val data = (datasetCombo.selectedItem as? String)?.let { dataFor(it) } ?: return
        val rec = AnalysisReports.recommendedBinning(data)
        lowerField.text = fmt(rec.lowerLimit)
        widthField.text = fmt(rec.binWidth)
        numBinsField.text = rec.numBins.toString()
    }

    // --- rendering -----------------------------------------------------------

    private fun refreshDatasets() {
        val names = controller.collection.value.map { it.name }
        val selected = datasetCombo.selectedItem as? String
        withUpdating {
            datasetCombo.model = DefaultComboBoxModel(names.toTypedArray())
            if (selected != null && selected in names) datasetCombo.selectedItem = selected
        }
        updateEnabled()
    }

    private fun updateEnabled() {
        val hasSelection = datasetCombo.selectedItem != null
        analysisButtons.forEach { it.isEnabled = hasSelection }
        autoRadio.isEnabled = hasSelection
        manualRadio.isEnabled = hasSelection
        shiftLevelField.isEnabled = hasSelection
        shiftSamplesField.isEnabled = hasSelection
        hintLabel.isVisible = controller.collection.value.isEmpty()
        updateBinningEnabled()
    }

    private fun updateBinningEnabled() {
        val manual = manualRadio.isSelected && datasetCombo.selectedItem != null
        lowerField.isEnabled = manual
        widthField.isEnabled = manual
        numBinsField.isEnabled = manual
    }

    // --- helpers -------------------------------------------------------------

    private fun dataFor(name: String): DoubleArray? =
        controller.collection.value.firstOrNull { it.name == name }?.data

    private fun fmt(v: Double): String = if (v.isFinite()) String.format("%.6g", v) else v.toString()

    private fun disabledForeground(): Color = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY

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
}
