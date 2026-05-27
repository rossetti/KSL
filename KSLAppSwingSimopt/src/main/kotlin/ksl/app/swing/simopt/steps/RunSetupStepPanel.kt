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

package ksl.app.swing.simopt.steps

import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import ksl.app.config.optimization.EvaluationSpec
import ksl.app.config.optimization.SolverTrackingSpec
import ksl.app.swing.common.notification.NotificationSeverity
import ksl.app.swing.simopt.SimoptAppController
import ksl.app.swing.simopt.runsetup.DisclosurePanel
import ksl.app.swing.simopt.runsetup.EvaluationSettingsPanel
import ksl.app.swing.simopt.runsetup.TrackingPanel
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel

/**
 *  *Run Setup* step — Phase O7a (revised).
 *
 *  Most users won't change anything here — the substrate's defaults
 *  for evaluation and tracking are sensible — so the step is now
 *  designed for fast pass-through:
 *
 *   1. A short banner explains the step.
 *   2. **Evaluation settings** — collapsed [DisclosurePanel] wrapping
 *      the full [EvaluationSettingsPanel] editor; summary line shows
 *      "(defaults)" or "(customized)".
 *   3. **Tracking & trace** — collapsed [DisclosurePanel] wrapping
 *      the full [TrackingPanel]; summary line shows the current
 *      tracking state ("disabled", "CSV: stem", "console", etc.).
 *
 *  Validation and run preview moved to the Execute step (Phase O7b)
 *  — they co-locate naturally with the Run button.  A footer note
 *  on this step points the user there.
 */
class RunSetupStepPanel(
    private val controller: SimoptAppController,
    private val onMessage: (String, NotificationSeverity) -> Unit = { _, _ -> }
) : JPanel(BorderLayout()) {

    private val evaluationPanel = EvaluationSettingsPanel(controller)
    private val trackingPanel = TrackingPanel(controller, onMessage)

    private val evaluationDisclosure = DisclosurePanel(
        title = "Evaluation settings",
        body = evaluationPanel
    )
    private val trackingDisclosure = DisclosurePanel(
        title = "Tracking & trace",
        body = trackingPanel
    )

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        val stack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        // Banner
        stack.add(JLabel(
            "<html><i>Most runs use the defaults below.  Tweak Evaluation or Tracking " +
                "only if you need to.  Validation and a run preview are shown on the " +
                "Execute step (next).</i></html>"
        ).apply {
            foreground = Color(0x55, 0x55, 0x55)
            font = font.deriveFont(Font.PLAIN, 12f)
            border = BorderFactory.createEmptyBorder(4, 8, 12, 8)
        })

        // Disclosures
        stack.add(evaluationDisclosure)
        stack.add(Box.createVerticalStrut(8))
        stack.add(trackingDisclosure)
        stack.add(Box.createVerticalGlue())

        add(stack, BorderLayout.NORTH)

        wireSummaryCollectors()
        refreshSummaries()
    }

    private fun wireSummaryCollectors() {
        controller.evaluationSpec.onEach { _ -> refreshEvaluationSummary() }
            .launchIn(controller.edtScope)
        controller.trackingSpec.onEach { _ -> refreshTrackingSummary() }
            .launchIn(controller.edtScope)
        // Tracking summary also reflects the solver's name (default
        // trace stem derivation), so refresh when the spec changes.
        controller.solverSpec.onEach { _ -> refreshTrackingSummary() }
            .launchIn(controller.edtScope)
    }

    private fun refreshSummaries() {
        refreshEvaluationSummary()
        refreshTrackingSummary()
    }

    private fun refreshEvaluationSummary() {
        val cur = controller.evaluationSpec.value
        val def = EvaluationSpec()
        evaluationDisclosure.setSummary(
            if (cur == def) "defaults" else describeEvaluationCustomization(cur, def)
        )
    }

    private fun describeEvaluationCustomization(cur: EvaluationSpec, def: EvaluationSpec): String {
        // Build a short list of differences from default.  Keeps the
        // header readable while telling the user which knobs they
        // turned.
        val diffs = buildList {
            if (cur.useSolutionCache != def.useSolutionCache)
                add("solution cache ${if (cur.useSolutionCache) "on" else "off"}")
            if (cur.useSimulationRunCache != def.useSimulationRunCache)
                add("sim-run cache ${if (cur.useSimulationRunCache) "on" else "off"}")
            if (cur.snapshotFrequency != def.snapshotFrequency)
                add("snapshot=${cur.snapshotFrequency}")
            if (cur.ensureProblemFeasibleRequests != def.ensureProblemFeasibleRequests)
                add("ensure-feasible ${if (cur.ensureProblemFeasibleRequests) "on" else "off"}")
            if (cur.maxFeasibleSamplingIterations != def.maxFeasibleSamplingIterations)
                add("max-feasible=${cur.maxFeasibleSamplingIterations}")
            if (cur.solutionPrecision != def.solutionPrecision)
                add("precision=${cur.solutionPrecision}")
        }
        return if (diffs.isEmpty()) "defaults" else diffs.joinToString(", ")
    }

    private fun refreshTrackingSummary() {
        val s: SolverTrackingSpec = controller.trackingSpec.value
        val csv = s.enableCsvTrace
        val con = s.enableConsoleTrace
        val summary = when {
            !csv && !con -> "disabled"
            csv && con -> {
                val stem = s.csvFileName ?: "<default>"
                "CSV: $stem + console"
            }
            csv -> {
                val stem = s.csvFileName ?: "<default>"
                "CSV: $stem"
            }
            else -> "console only"
        }
        trackingDisclosure.setSummary(summary)
    }
}
