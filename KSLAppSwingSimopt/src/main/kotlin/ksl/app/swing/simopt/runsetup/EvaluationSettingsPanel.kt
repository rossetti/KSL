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
import ksl.app.config.optimization.EvaluationSpec
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
 * Editor for [EvaluationSpec].
 *
 * Six fields:
 *   - `useSolutionCache` (default true)
 *   - `useSimulationRunCache` (default false)
 *   - `snapshotFrequency` (default 1)
 *   - `ensureProblemFeasibleRequests` (default false)
 *   - `maxFeasibleSamplingIterations`: `Int?` (default null = "solver default")
 *   - `solutionPrecision`: `Double?` (default null = "solver default")
 *
 * The two nullable fields are presented as "override" checkboxes
 * paired with numeric input — unchecked = `null`, checked = the
 * field's parsed value.  Editing any field calls
 * [SimoptAppController.setEvaluationSpec], which is a *preference*
 * mutator (marks dirty but does NOT drop `lastResult`).
 */
class EvaluationSettingsPanel(
    private val controller: SimoptAppController
) : JPanel(GridBagLayout()) {

    private val solutionCacheCheckbox = JCheckBox("Use solution cache")
    private val simulationRunCacheCheckbox = JCheckBox("Use simulation-run cache")
    private val snapshotFrequencyField = JTextField(10)
    private val ensureFeasibleCheckbox = JCheckBox("Ensure problem-feasible requests")

    private val overrideMaxFeasibleCheckbox = JCheckBox("Override max feasible sampling iterations")
    private val maxFeasibleField = JTextField(10)
    private val overrideSolutionPrecisionCheckbox = JCheckBox("Override solution precision")
    private val solutionPrecisionField = JTextField(10)

    @Volatile private var suppress = false

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Evaluation settings"),
            BorderFactory.createEmptyBorder(2, 6, 6, 6)
        )

        add(solutionCacheCheckbox, gbc(0, 0, width = 2, anchor = GridBagConstraints.WEST))
        add(simulationRunCacheCheckbox, gbc(0, 1, width = 2, anchor = GridBagConstraints.WEST))

        add(JLabel("Snapshot frequency:"), gbc(0, 2, anchor = GridBagConstraints.WEST))
        add(snapshotFrequencyField, gbc(1, 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        add(ensureFeasibleCheckbox, gbc(0, 3, width = 2, anchor = GridBagConstraints.WEST))

        add(overrideMaxFeasibleCheckbox, gbc(0, 4, anchor = GridBagConstraints.WEST))
        add(maxFeasibleField, gbc(1, 4, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        add(overrideSolutionPrecisionCheckbox, gbc(0, 5, anchor = GridBagConstraints.WEST))
        add(solutionPrecisionField, gbc(1, 5, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        val help = JLabel(
            "<html><i>Evaluation settings are a preference — editing them marks the " +
                "document dirty but does NOT invalidate the previous run's results.</i></html>"
        ).apply { foreground = Color(0x55, 0x55, 0x55); font = font.deriveFont(Font.PLAIN, 11f) }
        add(help, gbc(0, 6, width = 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL,
            insets = Insets(6, 4, 2, 4)))

        wireCheckboxes()
        wireFields()
        wireOverrideToggles()
        wireCollectors()

        refreshFromController()
    }

    private fun wireCheckboxes() {
        solutionCacheCheckbox.addActionListener { commitBoolean() }
        simulationRunCacheCheckbox.addActionListener { commitBoolean() }
        ensureFeasibleCheckbox.addActionListener { commitBoolean() }
    }

    private fun wireFields() {
        snapshotFrequencyField.addActionListener { commitSnapshotFreq() }
        snapshotFrequencyField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent) { commitSnapshotFreq() }
        })
        maxFeasibleField.addActionListener { commitMaxFeasible() }
        maxFeasibleField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent) { commitMaxFeasible() }
        })
        solutionPrecisionField.addActionListener { commitSolutionPrecision() }
        solutionPrecisionField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent) { commitSolutionPrecision() }
        })
    }

    private fun wireOverrideToggles() {
        overrideMaxFeasibleCheckbox.addActionListener {
            maxFeasibleField.isEnabled = overrideMaxFeasibleCheckbox.isSelected
            commitMaxFeasible()
        }
        overrideSolutionPrecisionCheckbox.addActionListener {
            solutionPrecisionField.isEnabled = overrideSolutionPrecisionCheckbox.isSelected
            commitSolutionPrecision()
        }
    }

    private fun wireCollectors() {
        controller.evaluationSpec.onEach { _ -> refreshFromController() }
            .launchIn(controller.edtScope)
    }

    // ── Commit helpers ────────────────────────────────────────────────────

    private fun commitBoolean() {
        if (suppress) return
        val cur = controller.evaluationSpec.value
        controller.setEvaluationSpec(cur.copy(
            useSolutionCache = solutionCacheCheckbox.isSelected,
            useSimulationRunCache = simulationRunCacheCheckbox.isSelected,
            ensureProblemFeasibleRequests = ensureFeasibleCheckbox.isSelected
        ))
    }

    private fun commitSnapshotFreq() {
        if (suppress) return
        val parsed = snapshotFrequencyField.text.trim().toIntOrNull()?.takeIf { it > 0 } ?: run {
            refreshFromController()
            return
        }
        val cur = controller.evaluationSpec.value
        if (cur.snapshotFrequency == parsed) return
        controller.setEvaluationSpec(cur.copy(snapshotFrequency = parsed))
    }

    private fun commitMaxFeasible() {
        if (suppress) return
        val cur = controller.evaluationSpec.value
        val newValue: Int? = if (overrideMaxFeasibleCheckbox.isSelected) {
            maxFeasibleField.text.trim().toIntOrNull()?.takeIf { it > 0 } ?: run {
                refreshFromController()
                return
            }
        } else null
        if (cur.maxFeasibleSamplingIterations == newValue) return
        controller.setEvaluationSpec(cur.copy(maxFeasibleSamplingIterations = newValue))
    }

    private fun commitSolutionPrecision() {
        if (suppress) return
        val cur = controller.evaluationSpec.value
        val newValue: Double? = if (overrideSolutionPrecisionCheckbox.isSelected) {
            solutionPrecisionField.text.trim().toDoubleOrNull()
                ?.takeIf { it > 0.0 && it.isFinite() } ?: run {
                refreshFromController()
                return
            }
        } else null
        if (cur.solutionPrecision == newValue) return
        controller.setEvaluationSpec(cur.copy(solutionPrecision = newValue))
    }

    // ── Refresh ───────────────────────────────────────────────────────────

    private fun refreshFromController() {
        suppress = true
        try {
            val s = controller.evaluationSpec.value
            solutionCacheCheckbox.isSelected = s.useSolutionCache
            simulationRunCacheCheckbox.isSelected = s.useSimulationRunCache
            if (!snapshotFrequencyField.hasFocus()) {
                snapshotFrequencyField.text = s.snapshotFrequency.toString()
            }
            ensureFeasibleCheckbox.isSelected = s.ensureProblemFeasibleRequests
            overrideMaxFeasibleCheckbox.isSelected = s.maxFeasibleSamplingIterations != null
            maxFeasibleField.isEnabled = overrideMaxFeasibleCheckbox.isSelected
            if (!maxFeasibleField.hasFocus()) {
                maxFeasibleField.text = s.maxFeasibleSamplingIterations?.toString().orEmpty()
            }
            overrideSolutionPrecisionCheckbox.isSelected = s.solutionPrecision != null
            solutionPrecisionField.isEnabled = overrideSolutionPrecisionCheckbox.isSelected
            if (!solutionPrecisionField.hasFocus()) {
                solutionPrecisionField.text = s.solutionPrecision?.toString().orEmpty()
            }
        } finally { suppress = false }
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
