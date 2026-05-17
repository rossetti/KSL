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

package ksl.app.swing.single.defaults

import kotlinx.coroutines.launch
import ksl.app.config.ReportFormat
import ksl.app.swing.single.SingleAppController
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.SwingConstants

/**
 * Default *Output Options* tab content for `kslSingleApp(...)`.
 *
 * Two stacked groups of related controls:
 *
 *  1. **Pre-run output configuration** — three checkbox sections
 *     governing what the framework writes during the run:
 *      - *Database*: toggle the SQLite KSLDatabase observer.
 *      - *CSV files*: independent toggles for per-replication and
 *        across-replication CSV outputs.
 *      - *Auto-render after Simulate*: which standard-report formats
 *        get materialized automatically after a successful run.
 *  2. **On-demand reports** — buttons that materialize a Standard
 *     report from the most recent snapshot.  Disabled until a
 *     completed simulation has produced a snapshot.
 *
 * The pre-run controls bind bidirectionally to
 * `SingleAppController.outputConfig`: edits push into the controller's
 * StateFlow; external state changes (Reset to Model Defaults, Open
 * Configuration, programmatic resets) refresh the checkbox display.
 *
 * @param controller owning [SingleAppController].
 * @param onStandardReport invoked when an on-demand *Standard …*
 *   button is clicked.  Argument is one of `"HTML"`, `"Markdown"`,
 *   `"Text"` (matching `StandardReportFormat.labelForButton`).
 * @param onAdvanced invoked when *Advanced…* is clicked.  Reserved
 *   for a future per-render options dialog (N5).
 * @param snapshotAvailable observable signal — `true` when the
 *   controller has a snapshot the on-demand buttons can target.
 *   The buttons enable / disable from this flow.
 */
class DefaultOutputOptionsPanel(
    private val controller: SingleAppController,
    onStandardReport: (format: String) -> Unit,
    onAdvanced: () -> Unit,
    snapshotAvailable: kotlinx.coroutines.flow.StateFlow<Boolean>
) : JPanel() {

    // Pre-run option checkboxes — referenced so the controller-side
    // sync can update their state when the StateFlow emits a fresh
    // OutputConfig.
    private val dbCheckbox: JCheckBox
    private val replicationCsvCheckbox: JCheckBox
    private val experimentCsvCheckbox: JCheckBox
    private val reportFormatCheckboxes: Map<ReportFormat, JCheckBox>

    // On-demand report buttons — referenced so snapshot-availability
    // gating can flip their enabled state.
    private val onDemandButtons: List<JButton>

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createEmptyBorder(8, 12, 8, 12)

        // ── Database section ───────────────────────────────────────────
        dbCheckbox = JCheckBox("Save run data to SQLite database (KSLDatabase)").apply {
            toolTipText = "Attaches a KSLDatabaseObserver during the run.  The " +
                "database lands at <workspace>/output/dbDir/<modelName>.db."
            addActionListener { controller.setEnableKSLDatabase(isSelected) }
        }
        add(sectionHeader("Database"))
        add(indentedRow(dbCheckbox))
        add(Box.createVerticalStrut(SECTION_GAP))

        // ── CSV section ────────────────────────────────────────────────
        replicationCsvCheckbox = JCheckBox("Replication-level data").apply {
            toolTipText = "Per-replication response data — one row per response per " +
                "replication.  Written to <workspace>/output/csvDir/."
            addActionListener { controller.setEnableReplicationCSV(isSelected) }
        }
        experimentCsvCheckbox = JCheckBox("Across-replication summary").apply {
            toolTipText = "Across-replication summary statistics — one row per response " +
                "with mean / std-dev / etc.  Written to <workspace>/output/csvDir/."
            addActionListener { controller.setEnableExperimentCSV(isSelected) }
        }
        add(sectionHeader("CSV files"))
        add(indentedRow(replicationCsvCheckbox))
        add(indentedRow(experimentCsvCheckbox))
        add(Box.createVerticalStrut(SECTION_GAP))

        // ── Auto-render reports section ────────────────────────────────
        reportFormatCheckboxes = ReportFormat.values().associateWith { format ->
            JCheckBox(format.name).apply {
                toolTipText = "Auto-render a Standard ${format.name} report after " +
                    "Simulate completes.  Lands at " +
                    "<workspace>/output/<runId>/reports/."
                addActionListener { controller.setReportFormatEnabled(format, isSelected) }
            }
        }
        add(sectionHeader("Auto-render after Simulate"))
        add(indentedRow(*reportFormatCheckboxes.values.toTypedArray()))
        add(Box.createVerticalStrut(SECTION_GAP))

        // ── Separator + on-demand reports section ──────────────────────
        add(JSeparator(SwingConstants.HORIZONTAL).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, 2)
        })
        add(Box.createVerticalStrut(SECTION_GAP))
        add(sectionHeader("On-demand reports"))
        val onDemandStrip = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val standardButtons = listOf(
            "Standard HTML report" to "HTML",
            "Standard Markdown report" to "Markdown",
            "Standard Text report" to "Text"
        ).map { (label, format) ->
            JButton(label).apply { addActionListener { onStandardReport(format) } }
        }
        val advancedButton = JButton("Advanced…").apply {
            addActionListener { onAdvanced() }
        }
        onDemandButtons = standardButtons + advancedButton
        for (b in onDemandButtons) onDemandStrip.add(b)
        add(onDemandStrip)
        add(Box.createVerticalGlue())

        // ── Bidirectional binding ──────────────────────────────────────
        controller.edtScope.launch {
            controller.outputConfig.collect { cfg ->
                dbCheckbox.isSelected = cfg.enableKSLDatabase
                replicationCsvCheckbox.isSelected = cfg.enableReplicationCSV
                experimentCsvCheckbox.isSelected = cfg.enableExperimentCSV
                for ((format, cb) in reportFormatCheckboxes) {
                    cb.isSelected = format in cfg.reports
                }
            }
        }
        controller.edtScope.launch {
            snapshotAvailable.collect { available ->
                for (b in onDemandButtons) b.isEnabled = available
            }
        }
    }

    private fun sectionHeader(text: String): JLabel =
        JLabel(text).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            font = font.deriveFont(Font.BOLD)
            foreground = Color(0x33, 0x33, 0x33)
            border = BorderFactory.createEmptyBorder(0, 0, 2, 0)
        }

    private fun indentedRow(vararg components: Component): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(0, 16, 0, 0)
            for (c in components) add(c)
        }

    companion object {
        private const val SECTION_GAP: Int = 10
    }
}
