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
import javax.swing.JLabel
import javax.swing.JPanel

/**
 *  *Reports* tab — on-demand report generation for the most recent
 *  scenario run.
 *
 *  Sections:
 *
 *  - **On-demand reports** — buttons that render reports against
 *    the most recent run.  Disabled until a completed run is
 *    available.  Format choice (HTML / Markdown / Text) is made
 *    inside the *Scenario Reports* dialog itself (single-format,
 *    HTML default, in-session only) — it is intentionally not
 *    persisted to `OutputConfig.reports`, which now drives only
 *    the Single app's pre-run auto-render.
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

    private val scenarioReportsButton = JButton("Scenario Reports…").apply {
        isEnabled = false
        toolTipText = "Pick which scenarios to include and choose between a consolidated " +
            "summary or a full per-scenario report (one document per scenario)."
    }

    /** Pinned open dialog instance — non-null while a
     *  [ScenarioReportDialog] is on screen.  Re-clicking the
     *  "Scenario Reports…" button raises this dialog instead of
     *  opening a duplicate.  The dialog's window-closed event clears
     *  it so the next click opens a fresh one. */
    private var openScenarioReportDialog: javax.swing.JDialog? = null

    private val comparisonAnalyzerButton = JButton("Open Comparison Analyzer…").apply {
        isEnabled = false
        toolTipText = "Launch the cross-scenario Comparison Analyzer — pick experiments, " +
            "then configure Box Plot, Multiple Comparison Analysis, or Confidence Intervals " +
            "in its own dialog with full per-analysis options."
    }

    /** Empty-state explainer that fills the void above the buttons
     *  when there's nothing reportable.  Three messages:
     *  - no batch reportable & not running → "Run the scenarios…"
     *  - run in progress → "Simulation in progress…"
     *  - batch reportable → hidden (buttons carry the affordance)
     *
     *  Reads as muted secondary text so the disabled buttons remain
     *  the visual focus once the tab has populated. */
    private val emptyStateLabel: JLabel = JLabel().apply {
        alignmentX = LEFT_ALIGNMENT
        border = BorderFactory.createEmptyBorder(0, 16, 8, 0)
        foreground = java.awt.Color(0x66, 0x66, 0x66)
        isVisible = false
    }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createEmptyBorder(16, 16, 16, 16)

        add(sectionLabel("On-demand reports"))
        add(emptyStateLabel)
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
        scenarioReportsButton.addActionListener { onScenarioReports() }
        comparisonAnalyzerButton.addActionListener { onOpenComparisonAnalyzer() }
    }

    /**
     *  Unified entry point that replaced the earlier two buttons.
     *  Opens (or raises) the modeless [ScenarioReportDialog].  The
     *  dialog owns the per-scenario / summary action affordances —
     *  this method just supplies callbacks the dialog uses to query
     *  existing files and trigger renders.
     */
    private fun onScenarioReports() {
        openScenarioReportDialog?.let { existing ->
            if (existing.isDisplayable) {
                existing.toFront()
                existing.requestFocus()
                return
            }
        }
        val result = batchResultOrWarn() ?: return
        val names = ScenarioReports.availableScenarioNames(result)
        if (names.isEmpty()) {
            onMessage(
                "No completed scenarios in the most recent run.",
                NotificationSeverity.WARNING
            )
            return
        }
        val dialog = ScenarioReportDialog.showDialog(
            parent = this,
            scenarioNames = names,
            initialReportsDir = reportsDir(),
            // File-probe callbacks: return the most-recently-modified
            // file matching the scenario's name pattern (base stem or
            // any APPEND_TIMESTAMP variant).
            perScenarioFile = { name, currentDir ->
                ScenarioReports.mostRecentPerScenarioFile(currentDir, name)
            },
            summaryFile = { currentDir ->
                ScenarioReports.mostRecentSummaryFile(currentDir)
            },
            // Render callbacks: the dialog supplies the single
            // user-picked format per click (in-session only,
            // HTML default).  Thread the user-picked file-handling
            // policy through to the substrate.
            onGenerateSummary = { pickedNames, currentDir, policy, format ->
                val outcome = ScenarioReports.renderScenarioSummaries(
                    result = result,
                    outputDir = currentDir,
                    formats = setOf(format),
                    scenarioNames = pickedNames.toSet(),
                    openHtmlInBrowser = false,
                    existingFilePolicy = policy
                )
                surfaceOutcome(outcome)
                ScenarioReportDialog.GenerateResult(outcome.written, outcome.errors, outcome.skipped)
            },
            onGeneratePerScenario = { scenarioName, currentDir, policy, format ->
                val outcome = ScenarioReports.renderScenarioSummary(
                    result = result,
                    scenarioName = scenarioName,
                    outputDir = currentDir,
                    formats = setOf(format),
                    openHtmlInBrowser = false,
                    existingFilePolicy = policy
                )
                surfaceOutcome(outcome, prefix = "[$scenarioName] ")
                ScenarioReportDialog.GenerateResult(outcome.written, outcome.errors, outcome.skipped)
            },
            // Delete callbacks: symmetric with Open — act on the
            // most-recently-modified file only, not every matching
            // variant.  Older timestamped variants (under the
            // Append-timestamp policy) stay on disk; the user can
            // click Delete again to peel back the next-most-recent,
            // or use Reveal… + the OS file manager for bulk cleanup.
            onDeletePerScenario = { scenarioName, currentDir ->
                val target = ScenarioReports.mostRecentPerScenarioFile(currentDir, scenarioName)
                deleteFiles(listOfNotNull(target))
            },
            onDeleteSummary = { currentDir ->
                val target = ScenarioReports.mostRecentSummaryFile(currentDir)
                deleteFiles(listOfNotNull(target))
            }
        )
        openScenarioReportDialog = dialog
        dialog.addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosed(e: java.awt.event.WindowEvent) {
                if (openScenarioReportDialog === dialog) openScenarioReportDialog = null
            }
        })
    }

    /** Delete every supplied file; aggregate result for the dialog's
     *  Delete callback. */
    private fun deleteFiles(files: List<java.nio.file.Path>): ScenarioReportDialog.DeleteResult {
        val deleted = mutableListOf<java.nio.file.Path>()
        val errors = mutableListOf<String>()
        for (file in files) {
            try {
                if (java.nio.file.Files.deleteIfExists(file)) deleted.add(file)
            } catch (t: Throwable) {
                errors.add(
                    "Could not delete ${file.fileName}: ${t.message ?: t::class.simpleName}"
                )
            }
        }
        errors.forEach { onMessage("Report delete error: $it", NotificationSeverity.WARNING) }
        return ScenarioReportDialog.DeleteResult(deleted, errors)
    }

    /** Push a [ScenarioReports.WriteOutcome] through the panel's
     *  notification channel.  The dialog provides its own Status /
     *  Open feedback for the artifacts themselves; these
     *  notifications carry **error** detail (render failures) and
     *  let users with the dialog already closed still see render
     *  activity.
     *
     *  Skip-if-exists outcomes are intentionally not surfaced here —
     *  the substrate flags them via `outcome.skipped`, and the dialog's
     *  status strip already states the skip in-place.  Sending a
     *  second notification through the main-app overlay for the same
     *  event would be redundant and distracting; only one of the
     *  three policy options would otherwise produce a notification
     *  on success, which the user found inconsistent. */
    private fun surfaceOutcome(
        outcome: ScenarioReports.WriteOutcome,
        prefix: String = ""
    ) {
        if (outcome.skipped) return
        outcome.errors.forEach {
            onMessage("Report error: $prefix$it", NotificationSeverity.WARNING)
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

    /** Returns the current batch result or surfaces a warning notification. */
    private fun batchResultOrWarn(): RunResult.BatchCompleted? {
        val r = controller.lastResult.value
        if (r !is RunResult.BatchCompleted) {
            onMessage("No completed run available to report on.", NotificationSeverity.WARNING)
            return null
        }
        return r
    }

    private fun wireCollectors() {
        // Enable the on-demand buttons once a terminal batch result
        // exists with at least one completed snapshot.  The
        // Comparison Analyzer additionally requires per-replication
        // data to be present (the substrate populates
        // `replicationsByItem` for scenario runs but not for other
        // run modes).  The empty-state label is driven by the same
        // inputs combined with [ScenarioAppController.runningFlow] so
        // a run-in-progress reads as "running" rather than "nothing
        // here yet" under R1 (which nulls `lastResult` on Simulate).
        controller.edtScope.launch {
            controller.lastResult.collect { _ -> refreshEnablement() }
        }
        controller.edtScope.launch {
            controller.runningFlow.collect { _ -> refreshEnablement() }
        }
    }

    /** Recompute enablement + empty-state copy from the controller's
     *  current state.  Single source of truth so the two collectors
     *  can't race into inconsistent states. */
    private fun refreshEnablement() {
        val batch = controller.lastResult.value as? RunResult.BatchCompleted
        val hasSnapshots = batch != null && batch.snapshots.isNotEmpty()
        val hasReplications = batch != null && batch.replicationsByItem.isNotEmpty()
        scenarioReportsButton.isEnabled = hasSnapshots
        comparisonAnalyzerButton.isEnabled = hasReplications

        if (hasSnapshots) {
            emptyStateLabel.isVisible = false
        } else {
            emptyStateLabel.text = if (controller.runningFlow.value) {
                "<html>Simulation in progress &mdash; reports will appear when the batch completes.</html>"
            } else {
                "<html>No completed scenario batch yet &mdash; run the scenarios to populate this tab.</html>"
            }
            emptyStateLabel.isVisible = true
        }
    }
}
