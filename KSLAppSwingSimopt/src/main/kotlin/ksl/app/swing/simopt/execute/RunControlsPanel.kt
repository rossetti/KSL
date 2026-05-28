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
import ksl.app.swing.simopt.SimoptAppController
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Run / Cancel controls + status line + stale-results banner.
 *
 * Gates the Run button on the live validation state hoisted to
 * [SimoptAppController]:
 *
 *  - Run is enabled iff all of:
 *    - `!runningFlow.value` (no active run)
 *    - `documentValidation.value.isValid` (no document-level errors)
 *    - the cached `modelAwareValidation` is either null or
 *      `isValid` (a null cache is permissive — user can run without
 *      explicit re-check; an invalid cache blocks).
 *
 *  - Cancel is enabled iff `runningFlow.value` is `true`.
 *
 * The stale-results banner appears when [SimoptAppController.editedSinceLastRun]
 * is `true` and a [SimoptAppController.lastResult] exists — i.e. the
 * user has a completed run but edited the document since.
 */
class RunControlsPanel(
    private val controller: SimoptAppController
) : JPanel(GridBagLayout()) {

    private val runButton = JButton("Run").apply {
        font = font.deriveFont(Font.BOLD)
    }
    private val cancelButton = JButton("Cancel")
    private val statusLabel = JLabel(" ").apply {
        font = font.deriveFont(Font.PLAIN, 13f)
    }
    private val staleBanner = JLabel(" ").apply {
        font = font.deriveFont(Font.PLAIN, 12f)
        foreground = Color(0xB5, 0x80, 0x00)
    }

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Run"),
            BorderFactory.createEmptyBorder(2, 6, 6, 6)
        )

        val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(runButton)
            add(cancelButton)
            add(statusLabel)
        }
        add(buttonRow, gbc(0, 0, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
        add(staleBanner, gbc(0, 1, weightx = 1.0,
            fill = GridBagConstraints.HORIZONTAL, insets = Insets(2, 8, 2, 8)))

        wireButtons()
        wireCollectors()
        refresh()
    }

    private fun wireButtons() {
        runButton.addActionListener { controller.submit() }
        cancelButton.addActionListener { controller.cancel() }
    }

    private fun wireCollectors() {
        controller.runningFlow.onEach { refresh() }.launchIn(controller.edtScope)
        controller.documentValidation.onEach { refresh() }.launchIn(controller.edtScope)
        controller.modelAwareValidation.onEach { refresh() }.launchIn(controller.edtScope)
        controller.modelAwareStale.onEach { refresh() }.launchIn(controller.edtScope)
        controller.editedSinceLastRun.onEach { refresh() }.launchIn(controller.edtScope)
        controller.lastResult.onEach { refresh() }.launchIn(controller.edtScope)
    }

    private fun refresh() {
        val running = controller.runningFlow.value
        val docValid = controller.documentValidation.value.isValid
        val modelCache = controller.modelAwareValidation.value
        val modelStale = controller.modelAwareStale.value
        // A model-aware result that's *current* and *invalid* blocks
        // the run; a stale cache is treated as "not yet checked" and
        // does not block (matches the validation panel's display
        // logic — stale results aren't merged into the visible set).
        val modelBlocks = modelCache != null && !modelStale && !modelCache.isValid

        runButton.isEnabled = !running && docValid && !modelBlocks
        cancelButton.isEnabled = running

        statusLabel.text = when {
            running -> "<html><i>Running…</i></html>"
            !docValid -> "<html><i>Document has validation errors — fix to enable Run.</i></html>"
            modelBlocks -> "<html><i>Model-aware validation found errors — fix to enable Run.</i></html>"
            controller.lastResult.value != null -> "<html><i>Last run completed.</i></html>"
            else -> "<html><i>Ready.</i></html>"
        }

        // Stale banner — only meaningful when a result exists but
        // the document has changed since.
        val showStale = controller.editedSinceLastRun.value && controller.lastResult.value != null
        staleBanner.text = if (showStale) {
            "<html>⚠ Results are stale — re-run to refresh.</html>"
        } else " "
    }

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
