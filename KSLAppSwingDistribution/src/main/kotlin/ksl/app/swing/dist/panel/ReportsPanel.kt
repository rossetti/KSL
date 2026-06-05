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
import ksl.app.dist.session.FitResult
import ksl.app.swing.common.io.openHtmlInBrowser
import ksl.app.swing.dist.DistributionAppController
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.GridBagLayout
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.UIManager

private const val BATCH_SUMMARY = "Batch summary"
private const val REPORT_RECOMMENDED = "Recommended distribution"
private const val REPORT_ALL_FITS = "All fitted distributions"

/**
 * Reports tab: renders the last fit result through the substrate's report
 * builders and opens it in the system browser. A single fit offers one report;
 * a batch offers a cross-dataset summary plus per-dataset drill-down. Per-dataset
 * reports pass the dataset's raw observations so the fit-quality plots rebuild.
 */
class ReportsPanel(private val controller: DistributionAppController) : JPanel(BorderLayout()) {

    private val errColor = Color(0xC6, 0x28, 0x28)

    private val infoLabel = JLabel("No fit results yet.")
    private val viewCombo = JComboBox<String>()
    private val reportTypeCombo = JComboBox(arrayOf(REPORT_RECOMMENDED, REPORT_ALL_FITS))
    private val openButton = JButton("Open in browser")
    private val statusLabel = JLabel(" ")

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        add(buildControls(), BorderLayout.NORTH)
        add(buildDescription(), BorderLayout.CENTER)
        wireListeners()
        bindState()
    }

    // --- construction --------------------------------------------------------

    private fun buildControls(): Component {
        val infoRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply { add(infoLabel) }
        val viewRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            add(JLabel("View:"))
            add(viewCombo)
            add(openButton)
        }
        val reportRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            add(JLabel("Report:"))
            add(reportTypeCombo)
        }
        val statusRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply { add(statusLabel) }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(leftAlign(infoRow))
            add(leftAlign(viewRow))
            add(leftAlign(reportRow))
            add(leftAlign(statusRow))
        }
    }

    private fun buildDescription(): Component = JPanel(GridBagLayout()).apply {
        val label = JLabel("Reports open in your web browser.")
        label.foreground = disabledForeground()
        add(label)
    }

    // --- wiring --------------------------------------------------------------

    private fun wireListeners() {
        openButton.addActionListener { openSelected() }
        viewCombo.addActionListener { updateReportTypeEnabled() }
    }

    /**
     * The report-type selector applies only to per-dataset reports; it is
     * disabled while the cross-dataset batch summary is the chosen view.
     */
    private fun updateReportTypeEnabled() {
        reportTypeCombo.isEnabled = (viewCombo.selectedItem as? String) != BATCH_SUMMARY
    }

    private fun allFitsSelected(): Boolean = reportTypeCombo.selectedItem == REPORT_ALL_FITS

    private fun bindState() {
        controller.edtScope.launch { controller.lastResult.collect { updateForResult(it) } }
        updateForResult(controller.lastResult.value)
    }

    // --- actions -------------------------------------------------------------

    private fun openSelected() {
        val result = controller.lastResult.value ?: return
        val selected = viewCombo.selectedItem as? String ?: return
        statusLabel.foreground = disabledForeground()
        statusLabel.text = "Generating…"
        controller.edtScope.launch {
            val res = runCatching {
                withContext(Dispatchers.Default) { generate(result, selected) }
            }
            res.onSuccess { path ->
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

    private fun generate(result: FitResult, selected: String): java.nio.file.Path = when (result) {
        is FitResult.Completed -> {
            val r = result.report
            FitReports.single(
                r, dataFor(r.datasetName), controller.datasetOutputDir(r.datasetName),
                allGoodnessOfFit = allFitsSelected()
            )
        }
        is FitResult.BatchCompleted -> {
            val batch = result.report
            if (selected == BATCH_SUMMARY) {
                FitReports.batchSummary(batch, controller.analysisName.value, controller.analysisDir())
            } else {
                val r = batch.results.firstOrNull { it.datasetName == selected }
                    ?: error("no result for '$selected'")
                FitReports.single(
                    r, dataFor(selected), controller.datasetOutputDir(selected),
                    allGoodnessOfFit = allFitsSelected()
                )
            }
        }
        else -> error("no report available")
    }

    // --- rendering -----------------------------------------------------------

    private fun updateForResult(result: FitResult?) {
        val entries: List<String> = when (result) {
            null -> {
                infoLabel.text = "No fit results yet."
                emptyList()
            }
            is FitResult.Completed -> {
                infoLabel.text = "Last fit: ${result.report.datasetName} (single)"
                listOf(result.report.datasetName)
            }
            is FitResult.BatchCompleted -> {
                val b = result.report
                infoLabel.text = "Last fit: batch — ${b.results.size} succeeded, ${b.failures.size} failed"
                listOf(BATCH_SUMMARY) + b.results.map { it.datasetName }
            }
            is FitResult.Failed -> {
                infoLabel.text = "Last fit failed: ${result.error.message}"
                emptyList()
            }
            is FitResult.Cancelled -> {
                infoLabel.text = "Last fit cancelled: ${result.reason}"
                emptyList()
            }
        }
        viewCombo.model = DefaultComboBoxModel(entries.toTypedArray())
        openButton.isEnabled = entries.isNotEmpty()
        updateReportTypeEnabled()
    }

    // --- helpers -------------------------------------------------------------

    private fun dataFor(name: String): DoubleArray? =
        controller.collection.value.firstOrNull { it.name == name }?.data

    private fun disabledForeground(): Color = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY

    private fun leftAlign(panel: JPanel): JPanel = panel.apply { alignmentX = Component.LEFT_ALIGNMENT }
}
