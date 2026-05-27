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
import ksl.app.config.optimization.SolverSpec
import ksl.app.swing.simopt.SimoptAppController
import java.awt.Color
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Read-only summary of the assembled document.  Three live labels:
 *
 *  - **Total simulation runs (estimated upper bound)** — algorithm-
 *    dependent: SHC/SA/CE multiply maxIterations by reps-per-eval;
 *    RSpline multiplies by max-num-replications; random restart
 *    multiplies by max-num-restarts.  Caching usually lowers the
 *    actual count.
 *  - **Output directory** — `<appWorkspace>/output/<sanitizedAnalysisName>/`.
 *  - **Trace file** — full path when tracking enabled; "(disabled)"
 *    otherwise.
 *
 *  All values update live on any relevant StateFlow emission; no
 *  user input here.
 */
class RunPreviewPanel(
    private val controller: SimoptAppController
) : JPanel(GridBagLayout()) {

    private val totalRunsLabel = JLabel(" ")
    private val outputDirLabel = JLabel(" ").apply {
        foreground = Color(0x33, 0x33, 0x33)
    }
    private val tracePathLabel = JLabel(" ").apply {
        foreground = Color(0x33, 0x33, 0x33)
    }

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Run preview"),
            BorderFactory.createEmptyBorder(2, 6, 6, 6)
        )

        add(JLabel("Estimated total simulation runs:").apply {
            font = font.deriveFont(Font.BOLD)
        }, gbc(0, 0, anchor = GridBagConstraints.WEST))
        add(totalRunsLabel, gbc(1, 0, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        add(JLabel("Output directory:").apply {
            font = font.deriveFont(Font.BOLD)
        }, gbc(0, 1, anchor = GridBagConstraints.WEST))
        add(outputDirLabel, gbc(1, 1, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        add(JLabel("Trace file:").apply {
            font = font.deriveFont(Font.BOLD)
        }, gbc(0, 2, anchor = GridBagConstraints.WEST))
        add(tracePathLabel, gbc(1, 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        val help = JLabel(
            "<html><i>Estimated upper bound — caching often lowers the actual count.  " +
                "Output directory is created at submit time.</i></html>"
        ).apply { foreground = Color(0x55, 0x55, 0x55); font = font.deriveFont(Font.PLAIN, 11f) }
        add(help, gbc(0, 3, width = 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL,
            insets = Insets(6, 4, 2, 4)))

        wireCollectors()
        refresh()
    }

    private fun wireCollectors() {
        // Total runs depends on solverSpec + randomRestart.
        controller.solverSpec.onEach { _ -> refresh() }.launchIn(controller.edtScope)
        controller.randomRestart.onEach { _ -> refresh() }.launchIn(controller.edtScope)
        // Output dir depends on analysisName.
        controller.output.onEach { _ -> refresh() }.launchIn(controller.edtScope)
        // Trace path depends on trackingSpec + solverSpec + analysisName.
        controller.trackingSpec.onEach { _ -> refresh() }.launchIn(controller.edtScope)
    }

    private fun refresh() {
        // Total runs
        totalRunsLabel.text = estimateTotalRuns()
        // Output dir
        val outDir = RunSetupPaths.outputDir(
            controller.appWorkspace, controller.output.value.analysisName
        )
        outputDirLabel.text = outDir.toString()
        outputDirLabel.toolTipText = outDir.toString()
        // Trace path
        val tracePath = RunSetupPaths.traceFilePath(
            appWorkspace = controller.appWorkspace,
            analysisName = controller.output.value.analysisName,
            trackingSpec = controller.trackingSpec.value,
            solverSpec = controller.solverSpec.value
        )
        tracePathLabel.text = tracePath?.toString() ?: "(disabled)"
        tracePathLabel.toolTipText = tracePath?.toString() ?: "Enable CSV trace on the Tracking panel."
    }

    private fun estimateTotalRuns(): String {
        val spec = controller.solverSpec.value ?: return "(select an algorithm first)"
        val (perEval, descriptor) = when (spec) {
            is SolverSpec.StochasticHillClimbing ->
                spec.replicationsPerEvaluation to "max iter × reps/eval"
            is SolverSpec.SimulatedAnnealing ->
                spec.replicationsPerEvaluation to "max iter × reps/eval"
            is SolverSpec.CrossEntropy ->
                spec.replicationsPerEvaluation to "max iter × reps/eval"
            is SolverSpec.RSpline ->
                spec.maxNumReplications to "max iter × max reps (RSpline upper bound)"
        }
        val baseRuns = spec.maxIterations.toLong() * perEval.toLong()
        val restarts = spec.randomRestart?.maxNumRestarts ?: 1
        val total = baseRuns * restarts
        val restartNote = if (spec.randomRestart != null)
            " × ${spec.randomRestart!!.maxNumRestarts} restarts" else ""
        return "≈ ${formatNumber(total)}  ($descriptor$restartNote)"
    }

    private fun formatNumber(n: Long): String =
        "%,d".format(n)

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
