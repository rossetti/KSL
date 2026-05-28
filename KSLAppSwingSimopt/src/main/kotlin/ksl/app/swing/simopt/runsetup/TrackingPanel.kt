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
 * Three toggles + two text fields:
 *   - CSV-trace toggle + file-name stem (when stem is blank,
 *     "default" semantics use the solver's name).
 *   - Resolved-path read-only label, updated live.
 *   - Mirror-to-console toggle.
 *   - Experiment label (defaults to "Run1").
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
    private val enableConsoleCheckbox = JCheckBox("Mirror trace to console")
    private val experimentLabelField = JTextField(16)

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

        add(enableConsoleCheckbox, gbc(0, 3, width = 2, anchor = GridBagConstraints.WEST,
            insets = Insets(8, 4, 2, 4)))

        add(JLabel("Experiment label:"), gbc(0, 4, anchor = GridBagConstraints.WEST))
        add(experimentLabelField, gbc(1, 4, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        val help = JLabel(
            "<html><i>Tracking is a preference — editing these settings marks the " +
                "document dirty but does NOT invalidate the previous run's results.  " +
                "The experiment label tags every emitted tracker row so multiple runs " +
                "into the same trace file can be distinguished.</i></html>"
        ).apply { foreground = Color(0x55, 0x55, 0x55); font = font.deriveFont(Font.PLAIN, 11f) }
        add(help, gbc(0, 5, width = 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL,
            insets = Insets(6, 4, 2, 4)))

        wireCheckboxes()
        wireFields()
        wireCollectors()

        refreshFromController()
        refreshResolvedPath()
    }

    private fun wireCheckboxes() {
        enableCsvCheckbox.addActionListener { commitFromUi() }
        enableConsoleCheckbox.addActionListener { commitFromUi() }
    }

    private fun wireFields() {
        fileNameField.addActionListener { commitFromUi() }
        fileNameField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent) { commitFromUi() }
        })
        experimentLabelField.addActionListener { commitFromUi() }
        experimentLabelField.addFocusListener(object : java.awt.event.FocusAdapter() {
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
        val console = enableConsoleCheckbox.isSelected
        val label = experimentLabelField.text.trim().ifBlank { "Run1" }
        val updated = try {
            cur.copy(
                enableCsvTrace = csv,
                csvFileName = stem,
                enableConsoleTrace = console,
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
            enableConsoleCheckbox.isSelected = s.enableConsoleTrace
            if (!experimentLabelField.hasFocus()) {
                experimentLabelField.text = s.experimentLabel
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
