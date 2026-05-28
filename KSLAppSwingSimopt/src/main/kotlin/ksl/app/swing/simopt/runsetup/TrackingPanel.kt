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

package ksl.app.swing.simopt.runsetup

import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import ksl.app.swing.common.notification.NotificationSeverity
import ksl.app.swing.simopt.SimoptAppController
import java.awt.Color
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

/**
 * Editor for [ksl.app.config.optimization.SolverTrackingSpec].
 *
 * Two toggles + two text fields:
 *   - CSV-trace toggle + file-name stem (when stem is blank,
 *     "default" semantics use the solver's name).
 *   - Resolved-path read-only label, updated live.
 *   - Trace row label (defaults to "Run1") — tags every emitted
 *     CSV row so manually-combined traces can distinguish runs.
 *
 * The substrate's `enableConsoleTrace` field is intentionally not
 * surfaced in the GUI — `SolverTrackingSpec` still carries it for
 * TOML round-trip compatibility, but the GUI never sets it true, so
 * the console tracker is never attached.
 *
 * Each edit calls [SimoptAppController.setTrackingSpec], a
 * *preference* mutator (marks dirty but does NOT drop `lastResult`).
 */
class TrackingPanel(
    private val controller: SimoptAppController,
    private val onMessage: (String, NotificationSeverity) -> Unit = { _, _ -> }
) : JPanel(GridBagLayout()) {

    private val enableCsvCheckbox = JCheckBox("Write CSV trace")
    private val fileNameField = JTextField(16)
    private val resolvedPathLabel = JLabel(" ").apply {
        foreground = Color(0x55, 0x55, 0x55)
        font = font.deriveFont(Font.PLAIN, 11f)
    }
    private val traceLabelField = JTextField(16).apply {
        toolTipText = "Tagged onto every row in the CSV trace file. Distinguishes " +
            "rows when you manually combine multiple trace files into one analysis. " +
            "Default: Run1."
    }

    private val fileNameLabel = JLabel("File name (stem):")
    private val resolvedHeader = JLabel("Resolved path:").apply {
        font = font.deriveFont(Font.PLAIN, 11f)
        foreground = Color(0x55, 0x55, 0x55)
    }

    @Volatile private var suppress = false

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Tracking & trace"),
            BorderFactory.createEmptyBorder(2, 6, 6, 6)
        )

        add(enableCsvCheckbox, gbc(0, 0, width = 2, anchor = GridBagConstraints.WEST))

        add(fileNameLabel, gbc(0, 1, anchor = GridBagConstraints.WEST,
            insets = Insets(2, 24, 2, 8)))
        add(fileNameField, gbc(1, 1, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        add(resolvedHeader, gbc(0, 2, anchor = GridBagConstraints.NORTHWEST,
            insets = Insets(2, 24, 2, 8)))
        add(resolvedPathLabel, gbc(1, 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        add(JLabel("Trace row label:"), gbc(0, 3, anchor = GridBagConstraints.WEST,
            insets = Insets(8, 4, 2, 4)))
        add(traceLabelField, gbc(1, 3, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL,
            insets = Insets(8, 4, 2, 4)))

        val help = JLabel(
            "<html><i>Tracking is a preference — editing these settings marks the " +
                "document dirty but does NOT invalidate the previous run's results.  " +
                "The trace row label is written into each CSV row so multiple traces " +
                "manually combined into one file can still be distinguished.</i></html>"
        ).apply { foreground = Color(0x55, 0x55, 0x55); font = font.deriveFont(Font.PLAIN, 11f) }
        add(help, gbc(0, 4, width = 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL,
            insets = Insets(6, 4, 2, 4)))

        wireCheckboxes()
        wireFields()
        wireCollectors()

        refreshFromController()
        refreshResolvedPath()
    }

    private fun wireCheckboxes() {
        enableCsvCheckbox.addActionListener { commitFromUi() }
    }

    private fun wireFields() {
        fileNameField.addActionListener { commitFromUi() }
        fileNameField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent) { commitFromUi() }
        })
        traceLabelField.addActionListener { commitFromUi() }
        traceLabelField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent) { commitFromUi() }
        })
    }

    private fun wireCollectors() {
        controller.trackingSpec.onEach { _ ->
            refreshFromController()
            refreshResolvedPath()
        }.launchIn(controller.edtScope)

        // Resolved path also depends on output (analysisName), the
        // current solver spec (for the default stem), and the live
        // run output directory (which factors the auto run-NNN slot).
        controller.output.onEach { _ -> refreshResolvedPath() }.launchIn(controller.edtScope)
        controller.solverSpec.onEach { _ -> refreshResolvedPath() }.launchIn(controller.edtScope)
        controller.runOutputDir.onEach { _ -> refreshResolvedPath() }.launchIn(controller.edtScope)
    }

    private fun commitFromUi() {
        if (suppress) return
        val cur = controller.trackingSpec.value
        val csv = enableCsvCheckbox.isSelected
        val stem = fileNameField.text.trim().takeIf { it.isNotBlank() }
        val label = traceLabelField.text.trim().ifBlank { "Run1" }
        val updated = try {
            cur.copy(
                enableCsvTrace = csv,
                csvFileName = stem,
                // enableConsoleTrace is not editable from the GUI;
                // preserve whatever value the spec already carries
                // (false for fresh documents; whatever TOML load
                // brought in for round-tripped documents).
                experimentLabel = label
            )
        } catch (ex: IllegalArgumentException) {
            onMessage(ex.message ?: "Invalid tracking setting", NotificationSeverity.WARNING)
            refreshFromController()
            return
        }
        if (cur == updated) return
        controller.setTrackingSpec(updated)
    }

    private fun refreshFromController() {
        suppress = true
        try {
            val s = controller.trackingSpec.value
            enableCsvCheckbox.isSelected = s.enableCsvTrace
            fileNameField.text = s.csvFileName.orEmpty()
            fileNameField.isEnabled = s.enableCsvTrace
            fileNameLabel.isEnabled = s.enableCsvTrace
            resolvedPathLabel.isEnabled = s.enableCsvTrace
            resolvedHeader.isEnabled = s.enableCsvTrace
            if (!traceLabelField.hasFocus()) {
                traceLabelField.text = s.experimentLabel
            }
        } finally { suppress = false }
    }

    private fun refreshResolvedPath() {
        val tracking = controller.trackingSpec.value
        if (!tracking.enableCsvTrace) {
            resolvedPathLabel.text = "(disabled — enable tracking to see the path)"
            return
        }
        val path = RunSetupPaths.traceFilePath(
            runOutputDir = controller.runOutputDir.value,
            trackingSpec = tracking,
            solverSpec = controller.solverSpec.value
        )
        resolvedPathLabel.text = path?.toString() ?: " "
        resolvedPathLabel.toolTipText = resolvedPathLabel.text
    }

    private fun gbc(
        col: Int,
        row: Int,
        weightx: Double = 0.0,
        width: Int = 1,
        anchor: Int = GridBagConstraints.CENTER,
        fill: Int = GridBagConstraints.NONE,
        insets: Insets = Insets(2, 4, 2, 4)
    ): GridBagConstraints = GridBagConstraints().apply {
        this.gridx = col
        this.gridy = row
        this.gridwidth = width
        this.weightx = weightx
        this.anchor = anchor
        this.fill = fill
        this.insets = insets
    }
}
