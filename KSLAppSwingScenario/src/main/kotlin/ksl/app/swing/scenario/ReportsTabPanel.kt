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
import ksl.app.swing.common.comparison.ComparisonAnalyzerFrame
import ksl.app.swing.common.notification.NotificationSeverity
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel

/**
 *  *Reports* tab — on-demand report generation for the most recent
 *  scenario run.
 *
 *  Sections:
 *
 *  - **Report formats** — checkbox row picking which formats the
 *    on-demand report buttons will produce
 *    ([ksl.app.config.OutputConfig.reports]).
 *  - **On-demand reports** — buttons that render reports against
 *    the most recent run.  Disabled until a completed run is
 *    available.
 *
 *  The *Enable KSL database* checkbox lives on the run toolbar
 *  alongside the *Simulate* button — it's a pre-run setup decision,
 *  not a report-format decision.  Per-scenario CSV toggles live on
 *  the per-scenario editor.  The execution-mode toggle lives on the
 *  run toolbar.
 */
class ReportsTabPanel(
    private val controller: ScenarioAppController,
    /** Lets the panel surface success/error messages through the
     *  frame's notifications overlay.  Optional — null silences feedback. */
    private val onMessage: (message: String, severity: NotificationSeverity) -> Unit =
        { _, _ -> }
) : JPanel() {

    private val formatBoxes: Map<ReportFormat, JCheckBox> = ReportFormat.entries.associateWith { fmt ->
        JCheckBox(fmt.name, fmt in controller.outputConfig.value.reports)
    }

    private val scenarioReportsButton = JButton("Scenario Reports…").apply {
        isEnabled = false
        toolTipText = "Pick which scenarios to include and choose between a consolidated " +
            "summary or a full per-scenario report (one document per scenario)."
    }

    private val comparisonAnalyzerButton = JButton("Open Comparison Analyzer…").apply {
        isEnabled = false
        toolTipText = "Launch the cross-scenario Comparison Analyzer — pick experiments, " +
            "then configure Box Plot, Multiple Comparison Analysis, or Confidence Intervals " +
            "in its own dialog with full per-analysis options."
    }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createEmptyBorder(16, 16, 16, 16)

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
                "format checked above."
        ).apply {
            alignmentX = LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(0, 16, 8, 0)
        })
        val buttonRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createEmptyBorder(0, 16, 0, 0)
            alignmentX = LEFT_ALIGNMENT
            add(scenarioReportsButton)
            add(Box.createHorizontalStrut(8))
            add(comparisonAnalyzerButton)
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

    private fun wireEvents() {
        formatBoxes.forEach { (fmt, cb) ->
            cb.addActionListener {
                val current = controller.outputConfig.value.reports
                controller.setReportFormats(
                    if (cb.isSelected) current + fmt else current - fmt
                )
            }
        }
        scenarioReportsButton.addActionListener { onScenarioReports() }
        comparisonAnalyzerButton.addActionListener { onOpenComparisonAnalyzer() }
    }

    /**
     *  Unified entry point that replaced the earlier two buttons.
     *  Opens [ScenarioReportDialog] for the user to pick which
     *  scenarios to include and whether to produce a consolidated
     *  summary or one full report per scenario, then dispatches
     *  accordingly.
     */
    private fun onScenarioReports() {
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
        val outcome = ScenarioReportDialog.showDialog(this, names)
        if (outcome !is ScenarioReportDialog.Result.Generate) return
        when (outcome.style) {
            ScenarioReportDialog.Style.SUMMARY -> {
                runAndReport(outputDir = reportsDir()) {
                    ScenarioReports.renderScenarioSummaries(
                        result = result,
                        outputDir = reportsDir(),
                        formats = formats,
                        scenarioNames = outcome.pickedNames.toSet()
                    )
                }
            }
            ScenarioReportDialog.Style.FULL_PER_SCENARIO -> {
                // One document per picked scenario.  Suppress the
                // browser-open for all but the first HTML so the user
                // doesn't get N simultaneous tabs; the runAndReport
                // notification still lists every file written.
                val combined = mutableListOf<java.nio.file.Path>()
                val errors = mutableListOf<String>()
                var openInBrowserNext = true
                for (name in outcome.pickedNames) {
                    val perOutcome = try {
                        ScenarioReports.renderScenarioSummary(
                            result = result,
                            scenarioName = name,
                            outputDir = reportsDir(),
                            formats = formats,
                            openHtmlInBrowser = openInBrowserNext
                        )
                    } catch (t: Throwable) {
                        errors.add(
                            "Render failed for scenario '$name': ${t.message ?: t::class.simpleName}"
                        )
                        continue
                    }
                    combined.addAll(perOutcome.written)
                    perOutcome.errors.forEach { errors.add("[$name] $it") }
                    // Only the first successful HTML write opens the
                    // browser.  After that, any subsequent invocation
                    // passes false.
                    if (perOutcome.written.any { it.toString().endsWith(".html") }) {
                        openInBrowserNext = false
                    }
                }
                reportCombinedOutcome(reportsDir(), combined, errors)
            }
        }
    }

    /** Single notification covering the combined outcome of a
     *  per-scenario batch render.  Mirrors `runAndReport` for the
     *  Summary path but accepts pre-aggregated paths and errors. */
    private fun reportCombinedOutcome(
        outputDir: java.nio.file.Path,
        written: List<java.nio.file.Path>,
        errors: List<String>
    ) {
        errors.forEach { onMessage("Report error: $it", NotificationSeverity.WARNING) }
        if (written.isNotEmpty()) {
            val files = written.joinToString(", ") { it.fileName.toString() }
            onMessage(
                "Wrote ${written.size} report file(s) to $outputDir: $files",
                NotificationSeverity.INFO
            )
        }
    }

    /** Launch the cross-scenario [ComparisonAnalyzerFrame] over the
     *  most-recent batch result.  The frame owns the analysis flow
     *  end-to-end: experiment selection, per-analysis dialogs, and
     *  report rendering.  This panel just supplies the data source
     *  and the notification bridge. */
    private fun onOpenComparisonAnalyzer() {
        val result = batchResultOrWarn() ?: return
        val source = BatchCompletedComparisonSource(result)
        val frame = ComparisonAnalyzerFrame(
            sources = listOf(source),
            defaultOutputDir = reportsDir(),
            defaultFormats = controller.outputConfig.value.reports,
            onMessage = { msg, sev -> onMessage(msg, mapSeverity(sev)) }
        )
        frame.isVisible = true
    }

    /** Map the analyzer's [ComparisonAnalyzerFrame.Severity] to the
     *  panel's [NotificationSeverity].  Enum values currently align
     *  one-for-one (INFO / WARNING / ERROR); kept as an explicit
     *  mapping so a future divergence in either enum doesn't
     *  silently swap a level. */
    private fun mapSeverity(s: ComparisonAnalyzerFrame.Severity): NotificationSeverity = when (s) {
        ComparisonAnalyzerFrame.Severity.INFO -> NotificationSeverity.INFO
        ComparisonAnalyzerFrame.Severity.WARNING -> NotificationSeverity.WARNING
        ComparisonAnalyzerFrame.Severity.ERROR -> NotificationSeverity.ERROR
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
                formatBoxes.forEach { (fmt, cb) ->
                    val want = fmt in cfg.reports
                    if (cb.isSelected != want) cb.isSelected = want
                }
            }
        }
        // Enable the on-demand buttons once a terminal batch result
        // exists with at least one completed snapshot.  The
        // Comparison Analyzer additionally requires per-replication
        // data to be present (the substrate populates
        // `replicationsByItem` for scenario runs but not for other
        // run modes).
        controller.edtScope.launch {
            controller.lastResult.collect { result ->
                val batch = result as? RunResult.BatchCompleted
                val hasSnapshots = batch != null && batch.snapshots.isNotEmpty()
                val hasReplications = batch != null && batch.replicationsByItem.isNotEmpty()
                scenarioReportsButton.isEnabled = hasSnapshots
                comparisonAnalyzerButton.isEnabled = hasReplications
            }
        }
    }
}
