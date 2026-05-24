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
import ksl.app.config.DatabasePolicy
import ksl.app.config.ReportFormat
import ksl.app.swing.single.SingleAppController
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane

/**
 *  Default *Run Control* tab content for `kslSingleApp(...)`.
 *
 *  Wraps the existing developer-provided run-parameter editor
 *  (`parameterPanel`) and adds an **Output Options** titled-border
 *  section below it.  The Output Options section consolidates every
 *  pre-run toggle that used to live on the separate "Output Options"
 *  tab (CSV, database, auto-render formats) plus the database policy
 *  combo, so the analyst sees all "configure-before-Simulate" knobs
 *  on a single tab without having to discover a separate one.
 *
 *  Layout: parameter panel scrolls (it's variable-height); Output
 *  Options sits below in a fixed pane.  When the parameter editor is
 *  short, both fit without scrolling; when it's tall, the user scrolls
 *  the parameter editor and the Output Options stays anchored at the
 *  bottom.
 *
 *  All controls bidirectionally bind to `SingleAppController.outputConfig`
 *  via the same mutators the legacy `DefaultOutputOptionsPanel` used —
 *  no controller surface changes are required.
 *
 *  @param controller       owning [SingleAppController]
 *  @param parameterEditor  developer-provided run-parameter editor
 *                          (the original "Run Control" tab content)
 */
class RunControlTabPanel(
    private val controller: SingleAppController,
    parameterEditor: JComponent
) : JPanel(BorderLayout()) {

    private val dbCheckbox: JCheckBox
    private val dbPolicyCombo: JComboBox<DatabasePolicy>
    private val replicationCsvCheckbox: JCheckBox
    private val experimentCsvCheckbox: JCheckBox
    private val reportFormatCheckboxes: Map<ReportFormat, JCheckBox>

    init {
        // No BorderLayout-with-CENTER-stretching here: the prior shape
        // put the scrolled parameter editor in CENTER which greedily
        // filled the remaining vertical space, leaving a vast empty
        // band between the parameter rows and the Output Options
        // block at the bottom.  Switch to a vertical BoxLayout stack
        // wrapped in a single outer scroll pane — both siblings take
        // their preferred height, sit flush against each other, and
        // a scrollbar appears only when the combined height exceeds
        // the viewport.

        // ── Database cluster ──────────────────────────────────────────
        dbCheckbox = JCheckBox("Enable KSL database").apply {
            toolTipText = "Attach a KSLDatabaseObserver to the next Run.  The " +
                "SQLite database lands at <workspace>/output/dbDir/<modelName>.db " +
                "and captures per-replication response data plus across-replication " +
                "summaries."
            addActionListener { controller.setEnableKSLDatabase(isSelected) }
        }
        dbPolicyCombo = JComboBox(arrayOf(DatabasePolicy.OVERWRITE, DatabasePolicy.NEW)).apply {
            selectedItem = controller.outputConfig.value.databasePolicy
            toolTipText = "What to do when the KSL database file already exists.  " +
                "OVERWRITE replaces it; NEW writes a timestamped sibling and keeps " +
                "the existing file.  Only meaningful when the database checkbox is on."
            addActionListener {
                val selected = selectedItem as? DatabasePolicy ?: return@addActionListener
                controller.setDatabasePolicy(selected)
            }
        }

        // ── CSV cluster ───────────────────────────────────────────────
        replicationCsvCheckbox = JCheckBox("Per-replication CSV").apply {
            toolTipText = "Per-replication response data — one row per response per " +
                "replication.  Written to <workspace>/output/csvDir/."
            addActionListener { controller.setEnableReplicationCSV(isSelected) }
        }
        experimentCsvCheckbox = JCheckBox("Across-replication summary CSV").apply {
            toolTipText = "Across-replication summary statistics — one row per response " +
                "with mean / std-dev / etc.  Written to <workspace>/output/csvDir/."
            addActionListener { controller.setEnableExperimentCSV(isSelected) }
        }

        // ── Auto-render cluster ───────────────────────────────────────
        reportFormatCheckboxes = ReportFormat.values().associateWith { format ->
            JCheckBox(format.name).apply {
                toolTipText = "Auto-render a standard ${format.name} report after Simulate " +
                    "completes.  Reports land under <workspace>/reports/<analysisName>/" +
                    "<timestamp>/.  Use the Post-Run Reporting tab for additional " +
                    "reports with custom names, sections, and format combinations."
                addActionListener { controller.setReportFormatEnabled(format, isSelected) }
            }
        }

        val stack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            parameterEditor.alignmentX = Component.LEFT_ALIGNMENT
            add(parameterEditor)
            add(Box.createVerticalStrut(6))
            val output = buildOutputOptionsBlock().apply { alignmentX = Component.LEFT_ALIGNMENT }
            add(output)
            // Vertical glue absorbs any leftover space at the BOTTOM
            // (rather than between the two siblings) when the viewport
            // is taller than the content.
            add(Box.createVerticalGlue())
        }
        val scroll = JScrollPane(stack).apply {
            border = BorderFactory.createEmptyBorder()
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
        add(scroll, BorderLayout.CENTER)

        wireControllerBinding()
    }

    private fun buildOutputOptionsBlock(): JComponent {
        val outer = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Output Options"),
                BorderFactory.createEmptyBorder(4, 8, 8, 8)
            )
        }
        val body = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            insets = Insets(2, 4, 2, 4)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.NONE
        }

        // Row 0: During run header
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 4
        body.add(subHeader("During run"), gbc)

        // Row 1: CSV checkboxes
        gbc.gridy = 1
        val csvRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(replicationCsvCheckbox)
            add(Box.createHorizontalStrut(12))
            add(experimentCsvCheckbox)
        }
        body.add(csvRow, gbc)

        // Row 2: DB checkbox + policy combo
        gbc.gridy = 2
        val dbRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(dbCheckbox)
            add(Box.createHorizontalStrut(8))
            add(JLabel("DB policy:"))
            add(Box.createHorizontalStrut(4))
            add(dbPolicyCombo)
        }
        body.add(dbRow, gbc)

        // Row 3: spacer
        gbc.gridy = 3
        body.add(Box.createVerticalStrut(6), gbc)

        // Row 4: Auto-render header
        gbc.gridy = 4
        body.add(subHeader("Auto-render after Simulate"), gbc)

        // Row 5: format checkboxes
        gbc.gridy = 5
        val formatsRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JLabel("Generate a standard report in:"))
            add(Box.createHorizontalStrut(8))
            for ((_, cb) in reportFormatCheckboxes) {
                add(cb)
                add(Box.createHorizontalStrut(8))
            }
        }
        body.add(formatsRow, gbc)

        // Row 6: pointer to Post-Run Reporting
        gbc.gridy = 6
        body.add(
            JLabel(
                "<html>Reports land in <code>&lt;workspace&gt;/reports/&lt;analysisName&gt;/" +
                    "&lt;timestamp&gt;/</code>. " +
                    "Use the <i>Post-Run Reporting</i> tab to save additional " +
                    "reports with custom names and sections.</html>"
            ).apply {
                font = font.deriveFont(font.size * 0.9f)
                foreground = Color(0x66, 0x66, 0x66)
            },
            gbc
        )

        outer.add(body, BorderLayout.WEST)
        return outer
    }

    private fun subHeader(text: String): JLabel = JLabel(text).apply {
        font = font.deriveFont(java.awt.Font.BOLD)
        foreground = Color(0x33, 0x33, 0x33)
        border = BorderFactory.createEmptyBorder(2, 0, 2, 0)
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private fun wireControllerBinding() {
        controller.edtScope.launch {
            controller.outputConfig.collect { cfg ->
                if (dbCheckbox.isSelected != cfg.enableKSLDatabase) {
                    dbCheckbox.isSelected = cfg.enableKSLDatabase
                }
                if (dbPolicyCombo.selectedItem != cfg.databasePolicy) {
                    dbPolicyCombo.selectedItem = cfg.databasePolicy
                }
                // DB policy combo only matters when the DB observer is on.
                dbPolicyCombo.isEnabled = cfg.enableKSLDatabase
                if (replicationCsvCheckbox.isSelected != cfg.enableReplicationCSV) {
                    replicationCsvCheckbox.isSelected = cfg.enableReplicationCSV
                }
                if (experimentCsvCheckbox.isSelected != cfg.enableExperimentCSV) {
                    experimentCsvCheckbox.isSelected = cfg.enableExperimentCSV
                }
                for ((format, cb) in reportFormatCheckboxes) {
                    val want = format in cfg.reports
                    if (cb.isSelected != want) cb.isSelected = want
                }
            }
        }
    }
}
