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

package ksl.app.swing.scenario

import kotlinx.coroutines.launch
import ksl.app.config.ReportFormat
import ksl.app.session.RunResult
import ksl.app.swing.common.notification.NotificationSeverity
import java.awt.Component
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel

/**
 *  *Reports* tab — combines the document-level output configuration
 *  with on-demand report generation.
 *
 *  Sections:
 *
 *  - **Database** — `Enable KSL database` checkbox flipping
 *    [ksl.app.config.OutputConfig.enableKSLDatabase].  This is the
 *    shared SQLite database that captures run data across every
 *    scenario; on by default.
 *  - **Report formats** — checkbox row picking which formats the
 *    on-demand report buttons will produce
 *    ([ksl.app.config.OutputConfig.reports]).
 *  - **On-demand reports** — buttons that render cross-scenario
 *    reports against the most recent run.  Disabled until a
 *    completed run is available (Phase H ships the scaffolding;
 *    the concrete render-action wiring lands as a follow-up).
 *
 *  Per-scenario CSV toggles live on the per-scenario editor, not
 *  here.  The execution-mode toggle lives on the run toolbar.
 */
class ReportsTabPanel(
    private val controller: ScenarioAppController,
    /** Lets the panel surface success/error messages through the
     *  frame's notifications overlay.  Optional — null silences feedback. */
    private val onMessage: (message: String, severity: NotificationSeverity) -> Unit =
        { _, _ -> }
) : JPanel() {

    private val dbCheckbox = JCheckBox(
        "Enable KSL database (shared across all scenarios)",
        controller.outputConfig.value.enableKSLDatabase
    )

    private val formatBoxes: Map<ReportFormat, JCheckBox> = ReportFormat.entries.associateWith { fmt ->
        JCheckBox(fmt.name, fmt in controller.outputConfig.value.reports)
    }

    private val perScenarioButton = JButton("Per-Scenario Summary…").apply {
        isEnabled = false
        toolTipText = "Across-replication statistics, histograms, frequencies, and time-series " +
            "stats for one scenario.  Pick which scenario after clicking."
    }

    private val crossScenarioBoxPlotButton = JButton("Cross-Scenario Box Plot…").apply {
        isEnabled = false
        toolTipText = "Per-replication distributions of one response across every scenario."
    }

    private val multipleComparisonButton = JButton("Multiple Comparison Analysis…").apply {
        isEnabled = false
        toolTipText = "Pairwise differences, MCB intervals, and screening for one response " +
            "across scenarios.  Requires every scenario to have the same number of replications."
    }

    private val replicationTraceButton = JButton("Per-Replication Trace…").apply {
        isEnabled = false
        toolTipText = "Per-replication values for one (scenario, response) pair, plus the " +
            "across-replication summary."
    }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createEmptyBorder(16, 16, 16, 16)

        add(sectionLabel("Database"))
        add(indent(dbCheckbox))
        add(Box.createVerticalStrut(14))

        add(sectionLabel("Report formats"))
        val formatRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createEmptyBorder(0, 16, 0, 0)
            alignmentX = LEFT_ALIGNMENT
        }
        formatBoxes.values.forEach {
            formatRow.add(it)
            formatRow.add(Box.createHorizontalStrut(12))
        }
        formatRow.add(Box.createHorizontalGlue())
        add(formatRow)
        add(Box.createVerticalStrut(14))

        add(sectionLabel("On-demand reports"))
        add(JLabel(
            "<html>Reports are written under <i>&lt;workspace&gt;/output/reports/</i> in every " +
                "format checked above.  Nothing renders automatically — pick what you want " +
                "to see, when you want it."
        ).apply {
            alignmentX = LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(0, 16, 8, 0)
        })
        val buttonRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createEmptyBorder(0, 16, 0, 0)
            alignmentX = LEFT_ALIGNMENT
            add(perScenarioButton)
            add(Box.createHorizontalStrut(8))
            add(crossScenarioBoxPlotButton)
            add(Box.createHorizontalStrut(8))
            add(multipleComparisonButton)
            add(Box.createHorizontalStrut(8))
            add(replicationTraceButton)
            add(Box.createHorizontalGlue())
        }
        add(buttonRow)

        add(Box.createVerticalGlue())

        wireEvents()
        wireCollectors()
    }

    private fun sectionLabel(text: String): JLabel = JLabel(text).apply {
        font = font.deriveFont(font.style or java.awt.Font.BOLD)
        alignmentX = LEFT_ALIGNMENT
        border = BorderFactory.createEmptyBorder(0, 0, 4, 0)
    }

    private fun indent(c: Component): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        alignmentX = LEFT_ALIGNMENT
        add(Box.createHorizontalStrut(16))
        add(c)
        add(Box.createHorizontalGlue())
    }

    private fun wireEvents() {
        dbCheckbox.addActionListener {
            controller.setEnableKSLDatabase(dbCheckbox.isSelected)
        }
        formatBoxes.forEach { (fmt, cb) ->
            cb.addActionListener {
                val current = controller.outputConfig.value.reports
                controller.setReportFormats(
                    if (cb.isSelected) current + fmt else current - fmt
                )
            }
        }
        perScenarioButton.addActionListener { onPerScenarioSummary() }
        crossScenarioBoxPlotButton.addActionListener { onCrossScenarioBoxPlot() }
        multipleComparisonButton.addActionListener { onMultipleComparison() }
        replicationTraceButton.addActionListener { onReplicationTrace() }
    }

    private fun onPerScenarioSummary() {
        val result = batchResultOrWarn() ?: return
        val formats = formatsOrWarn() ?: return
        val names = ScenarioReports.availableScenarioNames(result)
        if (names.isEmpty()) {
            onMessage(
                "No completed scenarios in the most recent run.",
                NotificationSeverity.WARNING
            )
            return
        }
        val scenario = if (names.size == 1) {
            names.single()
        } else {
            JOptionPane.showInputDialog(
                this,
                "Pick a scenario to summarise:",
                "Per-Scenario Summary",
                JOptionPane.QUESTION_MESSAGE,
                null,
                names.toTypedArray(),
                names.first()
            ) as? String ?: return
        }
        runAndReport(outputDir = reportsDir()) {
            ScenarioReports.renderPerScenarioSummary(result, scenario, reportsDir(), formats)
        }
    }

    private fun onCrossScenarioBoxPlot() {
        val result = batchResultOrWarn() ?: return
        val formats = formatsOrWarn() ?: return
        val responses = ScenarioReports.responsesCommonAcrossScenarios(result)
        if (responses.isEmpty()) {
            onMessage(
                "No response is recorded in every scenario — cannot build a cross-scenario plot.",
                NotificationSeverity.WARNING
            )
            return
        }
        val response = pickResponse(responses, "Cross-Scenario Box Plot") ?: return
        runAndReport(outputDir = reportsDir()) {
            ScenarioReports.renderCrossScenarioBoxPlot(result, response, reportsDir(), formats)
        }
    }

    private fun onMultipleComparison() {
        val result = batchResultOrWarn() ?: return
        val formats = formatsOrWarn() ?: return
        val responses = ScenarioReports.responsesCommonAcrossScenarios(result)
        if (responses.isEmpty()) {
            onMessage(
                "No response is recorded in every scenario — cannot build a multiple-comparison report.",
                NotificationSeverity.WARNING
            )
            return
        }
        val response = pickResponse(responses, "Multiple Comparison Analysis") ?: return
        runAndReport(outputDir = reportsDir()) {
            ScenarioReports.renderMultipleComparison(result, response, reportsDir(), formats)
        }
    }

    private fun onReplicationTrace() {
        val result = batchResultOrWarn() ?: return
        val formats = formatsOrWarn() ?: return
        val scenarios = ScenarioReports.availableScenarioNames(result)
            .filter { result.replicationsByItem[it]?.isNotEmpty() == true }
        if (scenarios.isEmpty()) {
            onMessage(
                "No scenario has per-replication data — cannot build a trace.",
                NotificationSeverity.WARNING
            )
            return
        }
        val pair = pickScenarioAndResponse(scenarios, result) ?: return
        runAndReport(outputDir = reportsDir()) {
            ScenarioReports.renderReplicationTrace(
                result = result,
                scenarioName = pair.first,
                responseName = pair.second,
                outputDir = reportsDir(),
                formats = formats
            )
        }
    }

    /** Two-combo modal picker: scenario first, then a response combo
     *  populated from that scenario's recorded within-rep stats.
     *  Returns `null` if the user cancels. */
    private fun pickScenarioAndResponse(
        scenarios: List<String>,
        result: RunResult.BatchCompleted
    ): Pair<String, String>? {
        val owner = javax.swing.SwingUtilities.getWindowAncestor(this) ?: JOptionPane.getRootFrame()
        val dialog = javax.swing.JDialog(owner, "Per-Replication Trace", java.awt.Dialog.ModalityType.APPLICATION_MODAL)
        dialog.defaultCloseOperation = javax.swing.WindowConstants.DISPOSE_ON_CLOSE

        val scenarioCombo = javax.swing.JComboBox(javax.swing.DefaultComboBoxModel(scenarios.toTypedArray()))
        val responseCombo = javax.swing.JComboBox<String>()
        fun refreshResponses() {
            val sel = scenarioCombo.selectedItem as? String ?: return
            val responses = ScenarioReports.responsesFor(result, sel)
            responseCombo.model = javax.swing.DefaultComboBoxModel(responses.toTypedArray())
        }
        scenarioCombo.addActionListener { refreshResponses() }
        refreshResponses()

        var chosen: Pair<String, String>? = null
        val ok = JButton("Render").apply {
            addActionListener {
                val s = scenarioCombo.selectedItem as? String
                val r = responseCombo.selectedItem as? String
                if (s == null || r == null) {
                    JOptionPane.showMessageDialog(
                        dialog, "Pick a scenario and response first.", "Per-Replication Trace",
                        JOptionPane.WARNING_MESSAGE
                    )
                    return@addActionListener
                }
                chosen = s to r
                dialog.dispose()
            }
        }
        val cancel = JButton("Cancel").apply { addActionListener { dialog.dispose() } }

        val form = JPanel(java.awt.GridBagLayout()).apply {
            border = BorderFactory.createEmptyBorder(12, 12, 8, 12)
            val gbc = java.awt.GridBagConstraints().apply {
                anchor = java.awt.GridBagConstraints.LINE_START
                insets = java.awt.Insets(2, 2, 2, 8)
                gridx = 0; gridy = 0
            }
            add(JLabel("Scenario:"), gbc)
            gbc.gridx = 1; gbc.fill = java.awt.GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
            add(scenarioCombo, gbc)
            gbc.gridx = 0; gbc.gridy = 1; gbc.fill = java.awt.GridBagConstraints.NONE; gbc.weightx = 0.0
            add(JLabel("Response:"), gbc)
            gbc.gridx = 1; gbc.fill = java.awt.GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
            add(responseCombo, gbc)
        }
        val buttons = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createEmptyBorder(0, 12, 12, 12)
            add(Box.createHorizontalGlue()); add(cancel)
            add(Box.createHorizontalStrut(8)); add(ok)
        }
        dialog.contentPane.layout = java.awt.BorderLayout()
        dialog.contentPane.add(form, java.awt.BorderLayout.CENTER)
        dialog.contentPane.add(buttons, java.awt.BorderLayout.SOUTH)
        dialog.rootPane.defaultButton = ok
        dialog.pack()
        dialog.minimumSize = java.awt.Dimension(360, dialog.height)
        dialog.setLocationRelativeTo(this)
        dialog.isVisible = true
        return chosen
    }

    /** Modal combo-style picker for a single response.  Auto-selects
     *  the only entry when [responses] has size 1; cancels when the
     *  user dismisses. */
    private fun pickResponse(responses: List<String>, title: String): String? {
        if (responses.size == 1) return responses.single()
        return JOptionPane.showInputDialog(
            this,
            "Pick a response:",
            title,
            JOptionPane.QUESTION_MESSAGE,
            null,
            responses.toTypedArray(),
            responses.first()
        ) as? String
    }

    private fun reportsDir(): java.nio.file.Path =
        controller.appWorkspace.resolve("output").resolve("reports")

    /** Common success/error notification handling for any renderer. */
    private fun runAndReport(
        outputDir: java.nio.file.Path,
        block: () -> ScenarioReports.WriteOutcome
    ) {
        val outcome = try { block() } catch (t: Throwable) {
            onMessage(
                "Report generation failed: ${t.message ?: t::class.simpleName}",
                NotificationSeverity.ERROR
            )
            return
        }
        outcome.errors.forEach { onMessage("Report error: $it", NotificationSeverity.WARNING) }
        if (outcome.written.isNotEmpty()) {
            val files = outcome.written.joinToString(", ") { it.fileName.toString() }
            onMessage(
                "Wrote ${outcome.written.size} report file(s) to $outputDir: $files",
                NotificationSeverity.INFO
            )
        }
    }

    /** Returns the current batch result or surfaces a warning notification. */
    private fun batchResultOrWarn(): RunResult.BatchCompleted? {
        val r = controller.lastResult.value
        if (r !is RunResult.BatchCompleted) {
            onMessage("No completed run available to report on.", NotificationSeverity.WARNING)
            return null
        }
        return r
    }

    /** Returns the current report-format set or surfaces a warning notification. */
    private fun formatsOrWarn(): Set<ReportFormat>? {
        val formats = controller.outputConfig.value.reports
        if (formats.isEmpty()) {
            onMessage(
                "Pick at least one report format above before generating.",
                NotificationSeverity.WARNING
            )
            return null
        }
        return formats
    }

    private fun wireCollectors() {
        controller.edtScope.launch {
            controller.outputConfig.collect { cfg ->
                if (dbCheckbox.isSelected != cfg.enableKSLDatabase) {
                    dbCheckbox.isSelected = cfg.enableKSLDatabase
                }
                formatBoxes.forEach { (fmt, cb) ->
                    val want = fmt in cfg.reports
                    if (cb.isSelected != want) cb.isSelected = want
                }
            }
        }
        // Enable the on-demand buttons once a terminal batch result
        // exists with at least one completed snapshot.  Cross-scenario
        // buttons additionally require per-replication data to be
        // present (the substrate populates `replicationsByItem` for
        // scenario runs but not for other run modes).
        controller.edtScope.launch {
            controller.lastResult.collect { result ->
                val batch = result as? RunResult.BatchCompleted
                val hasSnapshots = batch != null && batch.snapshots.isNotEmpty()
                val hasReplications = batch != null && batch.replicationsByItem.isNotEmpty()
                perScenarioButton.isEnabled = hasSnapshots
                crossScenarioBoxPlotButton.isEnabled = hasReplications
                multipleComparisonButton.isEnabled = hasReplications
                replicationTraceButton.isEnabled = hasReplications
            }
        }
    }
}
