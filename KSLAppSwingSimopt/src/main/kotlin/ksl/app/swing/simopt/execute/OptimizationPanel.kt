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

package ksl.app.swing.simopt.execute

import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import ksl.app.config.optimization.SolverSpec
import ksl.app.config.optimization.SolverTrackingSpec
import ksl.app.swing.simopt.SimoptAppController
import ksl.app.swing.simopt.runsetup.RunSetupPaths
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Always-visible top section of the Execute step.
 *
 * Holds everything the user needs to launch an optimization without
 * scrolling: the Optimize / Cancel buttons, a compact validation
 * pill (with a [View…] button that opens [ValidationDetailsDialog]
 * on demand), the resolved output directory with a [Change…]
 * affordance, the resolved trace path, and a stale-results banner
 * when the document has been edited since the last completed run.
 *
 * Replaces the prior split of `RunControlsPanel` + `RunPreviewPanel`
 * + the inline `PreRunValidationPanel`.
 */
class OptimizationPanel(
    private val controller: SimoptAppController
) : JPanel(GridBagLayout()) {

    private val optimizeButton = JButton("Optimize").apply {
        font = font.deriveFont(Font.BOLD)
    }
    private val cancelButton = JButton("Cancel")
    private val statusLabel = JLabel(" ").apply {
        font = font.deriveFont(Font.PLAIN, 13f)
    }

    private val validationPill = JLabel(" ").apply {
        font = font.deriveFont(Font.PLAIN, 12f)
    }
    private val viewIssuesButton = JButton("View…").apply {
        font = font.deriveFont(Font.PLAIN, 11f)
        margin = Insets(1, 6, 1, 6)
        addActionListener { openValidationDialog() }
    }
    private val recheckButton = JButton("Re-check against model").apply {
        font = font.deriveFont(Font.PLAIN, 11f)
        margin = Insets(1, 6, 1, 6)
        toolTipText = "Builds a probe model and runs model-aware validation. " +
            "Cached until the next document edit."
        addActionListener { controller.runModelAwareValidationNow() }
    }

    private val outputDirLabel = JLabel(" ").apply {
        foreground = Color(0x33, 0x33, 0x33)
        font = font.deriveFont(Font.PLAIN, 12f)
    }
    private val changeDirButton = JButton("Change…").apply {
        font = font.deriveFont(Font.PLAIN, 11f)
        margin = Insets(1, 6, 1, 6)
        addActionListener { openDirectoryChooser() }
    }
    private val tracePathLabel = JLabel(" ").apply {
        foreground = Color(0x55, 0x55, 0x55)
        font = font.deriveFont(Font.PLAIN, 11f)
    }

    private val staleBanner = JLabel(" ").apply {
        font = font.deriveFont(Font.PLAIN, 12f)
        foreground = Color(0xB5, 0x80, 0x00)
    }

    /** Lazily-built modal — created on first open so a session that
     *  never views validation details doesn't pay for the dialog
     *  construction up front. */
    private var validationDialog: ValidationDetailsDialog? = null

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Optimization"),
            BorderFactory.createEmptyBorder(2, 6, 6, 6)
        )

        // Row 0: Optimize / Cancel + status.
        add(buttonRow(), gbc(0, 0, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        // Row 1: validation pill + View / Re-check.
        add(validationRow(), gbc(0, 1, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL,
            insets = Insets(2, 2, 2, 2)))

        // Row 2: Output line + Change.
        add(outputRow(), gbc(0, 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL,
            insets = Insets(2, 2, 2, 2)))

        // Row 3: trace path.
        add(traceRow(), gbc(0, 3, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL,
            insets = Insets(0, 2, 2, 2)))

        // Row 4: stale banner (conditional).
        add(staleBanner, gbc(0, 4, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL,
            insets = Insets(2, 4, 2, 4)))

        wireButtons()
        wireCollectors()
        refresh()
    }

    // ── Row builders ──────────────────────────────────────────────────────

    private fun buttonRow(): Component = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
        isOpaque = false
        add(optimizeButton)
        add(cancelButton)
        add(statusLabel)
    }

    private fun validationRow(): Component = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
        isOpaque = false
        add(validationPill)
        add(viewIssuesButton)
        add(recheckButton)
    }

    private fun outputRow(): Component = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
        isOpaque = false
        add(JLabel("Output:").apply { font = font.deriveFont(Font.BOLD, 12f) })
        add(outputDirLabel)
        add(changeDirButton)
    }

    private fun traceRow(): Component = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
        isOpaque = false
        add(JLabel("Trace:").apply { font = font.deriveFont(Font.BOLD, 11f) })
        add(tracePathLabel)
    }

    // ── Wiring ────────────────────────────────────────────────────────────

    private fun wireButtons() {
        optimizeButton.addActionListener { controller.submit() }
        cancelButton.addActionListener { controller.cancel() }
    }

    private fun wireCollectors() {
        controller.runningFlow.onEach { refresh() }.launchIn(controller.edtScope)
        controller.documentValidation.onEach { refresh() }.launchIn(controller.edtScope)
        controller.modelAwareValidation.onEach { refresh() }.launchIn(controller.edtScope)
        controller.modelAwareStale.onEach { refresh() }.launchIn(controller.edtScope)
        controller.editedSinceLastRun.onEach { refresh() }.launchIn(controller.edtScope)
        controller.lastResult.onEach { refresh() }.launchIn(controller.edtScope)
        controller.runOutputDir.onEach { refresh() }.launchIn(controller.edtScope)
        controller.trackingSpec.onEach { refresh() }.launchIn(controller.edtScope)
        controller.solverSpec.onEach { refresh() }.launchIn(controller.edtScope)
    }

    // ── Refresh ───────────────────────────────────────────────────────────

    private fun refresh() {
        val running = controller.runningFlow.value
        val doc = controller.documentValidation.value
        val cached = controller.modelAwareValidation.value
        val modelStale = controller.modelAwareStale.value
        val modelEffective = cached?.takeIf { !modelStale }
        val errorCount = doc.errors.size + (modelEffective?.errors?.size ?: 0)
        val warnCount = doc.warnings.size + (modelEffective?.warnings?.size ?: 0)
        val hasErrors = errorCount > 0
        val hasIssues = errorCount > 0 || warnCount > 0

        // Button enablement.
        optimizeButton.isEnabled = !running && !hasErrors
        cancelButton.isEnabled = running

        // Status line.
        statusLabel.text = when {
            running -> "<html><i>Running…</i></html>"
            hasErrors -> "<html><i>Fix validation errors to enable Optimize.</i></html>"
            controller.lastResult.value != null -> "<html><i>Last run completed.</i></html>"
            else -> "<html><i>Ready.</i></html>"
        }

        // Validation pill.
        if (errorCount == 0 && warnCount == 0) {
            validationPill.text = "✓ Ready"
            validationPill.foreground = Color(0x2D, 0x6D, 0x40)
            viewIssuesButton.isVisible = false
        } else if (errorCount == 0) {
            validationPill.text = "⚠ $warnCount warning(s)"
            validationPill.foreground = Color(0xB5, 0x80, 0x00)
            viewIssuesButton.isVisible = true
        } else {
            validationPill.text = "✗ $errorCount error(s), $warnCount warning(s)"
            validationPill.foreground = Color(0xB5, 0x40, 0x40)
            viewIssuesButton.isVisible = true
        }

        // Output dir + trace path.
        val outDir = controller.runOutputDir.value
        outputDirLabel.text = outDir.toString()
        outputDirLabel.toolTipText = outDir.toString()
        val tracking: SolverTrackingSpec = controller.trackingSpec.value
        val solver: SolverSpec? = controller.solverSpec.value
        val tracePath = RunSetupPaths.traceFilePath(outDir, tracking, solver)
        tracePathLabel.text = tracePath?.toString() ?: "(disabled — enable on Run Setup)"
        tracePathLabel.toolTipText = tracePath?.toString()
            ?: "Enable CSV trace on the Tracking panel of the Run Setup step."

        // Stale banner.
        val showStale = controller.editedSinceLastRun.value && controller.lastResult.value != null
        staleBanner.text = if (showStale)
            "<html>⚠ Results are stale — re-run to refresh.</html>" else " "
    }

    // ── Action handlers ───────────────────────────────────────────────────

    private fun openValidationDialog() {
        if (validationDialog == null) {
            val owner = SwingUtilities.getWindowAncestor(this)
            validationDialog = ValidationDetailsDialog(owner, controller)
        }
        validationDialog!!.isVisible = true
    }

    private fun openDirectoryChooser() {
        val current = controller.runOutputDir.value.toFile()
        // Walk up to the first existing ancestor so the chooser
        // doesn't open at a path that hasn't been created yet.
        val startDir = generateSequence(current) { it.parentFile }
            .firstOrNull { it.exists() && it.isDirectory } ?: File(System.getProperty("user.home"))
        val chooser = JFileChooser(startDir).apply {
            dialogTitle = "Choose run output directory"
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isMultiSelectionEnabled = false
        }
        val result = chooser.showOpenDialog(this)
        if (result == JFileChooser.APPROVE_OPTION) {
            controller.setRunOutputDir(chooser.selectedFile.toPath())
        }
    }

    // ── Layout helpers ────────────────────────────────────────────────────

    private fun gbc(
        col: Int,
        row: Int,
        weightx: Double = 0.0,
        fill: Int = GridBagConstraints.NONE,
        insets: Insets = Insets(2, 4, 2, 4)
    ): GridBagConstraints = GridBagConstraints().apply {
        this.gridx = col
        this.gridy = row
        this.weightx = weightx
        this.fill = fill
        this.anchor = GridBagConstraints.WEST
        this.insets = insets
    }
}
