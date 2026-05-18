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
import ksl.app.config.ExecutionMode
import ksl.app.config.ReportFormat
import java.awt.Component
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton

/**
 *  Document-level *Output Options* tab.  Hosts:
 *
 *  - **Enable KSL database** checkbox — flips
 *    [ksl.app.config.OutputConfig.enableKSLDatabase].  This is the
 *    shared SQLite database that captures run data across every
 *    scenario; on by default.
 *  - **Execution mode** radio group — Sequential vs Concurrent.
 *  - **Report formats** checkbox row — picks which formats the
 *    on-demand Reports tab is permitted to render
 *    ([ksl.app.config.OutputConfig.reports]).
 *
 *  Per-scenario CSV toggles live on the per-scenario editor, not
 *  here — the document-level CSV fields on `OutputConfig` are not
 *  used by the Scenario app.
 */
class OutputOptionsPanel(
    private val controller: ScenarioAppController
) : JPanel() {

    private val dbCheckbox = JCheckBox(
        "Enable KSL database (shared across all scenarios)",
        controller.outputConfig.value.enableKSLDatabase
    )

    private val sequentialRadio = JRadioButton(
        "Sequential — run scenarios one at a time",
        controller.executionMode.value == ExecutionMode.SEQUENTIAL
    )
    private val concurrentRadio = JRadioButton(
        "Concurrent — run scenarios in parallel",
        controller.executionMode.value == ExecutionMode.CONCURRENT
    )

    private val formatBoxes: Map<ReportFormat, JCheckBox> = ReportFormat.entries.associateWith { fmt ->
        JCheckBox(fmt.name, fmt in controller.outputConfig.value.reports)
    }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createEmptyBorder(16, 16, 16, 16)

        add(sectionLabel("Database"))
        add(indent(dbCheckbox))
        add(Box.createVerticalStrut(14))

        add(sectionLabel("Execution mode"))
        val group = ButtonGroup().apply {
            add(sequentialRadio); add(concurrentRadio)
        }
        add(indent(sequentialRadio))
        add(indent(concurrentRadio))
        // group is held by the JRadioButtons; reference suppresses unused-warning
        @Suppress("UNUSED_EXPRESSION") group
        add(Box.createVerticalStrut(14))

        add(sectionLabel("Report formats (for on-demand rendering)"))
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
        sequentialRadio.addActionListener {
            if (sequentialRadio.isSelected) controller.setExecutionMode(ExecutionMode.SEQUENTIAL)
        }
        concurrentRadio.addActionListener {
            if (concurrentRadio.isSelected) controller.setExecutionMode(ExecutionMode.CONCURRENT)
        }
        formatBoxes.forEach { (fmt, cb) ->
            cb.addActionListener {
                val current = controller.outputConfig.value.reports
                controller.setReportFormats(
                    if (cb.isSelected) current + fmt else current - fmt
                )
            }
        }
    }

    private fun wireCollectors() {
        // Mirror controller → UI so save/load and external resets show through.
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
        controller.edtScope.launch {
            controller.executionMode.collect { mode ->
                val wantSeq = mode == ExecutionMode.SEQUENTIAL
                if (sequentialRadio.isSelected != wantSeq) sequentialRadio.isSelected = wantSeq
                if (concurrentRadio.isSelected == wantSeq) concurrentRadio.isSelected = !wantSeq
            }
        }
    }
}
