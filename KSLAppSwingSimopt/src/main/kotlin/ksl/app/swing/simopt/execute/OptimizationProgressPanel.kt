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
import ksl.app.optimization.formatObjective
import ksl.app.swing.simopt.SimoptAppController
import java.awt.Color
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.Timer
import kotlin.time.Duration
import kotlin.time.TimeSource

// `formatObjective` is now a substrate-level helper in
// `ksl.app.optimization.OptimizationFormatting.kt` so it can be
// shared between the live UI panels and the HTML report writer.

/**
 * Numeric progress display for a running or completed optimization.
 *
 * Three live rows:
 *
 *  - **Iteration:** `n / max` (or `n` when max is unbounded)
 *  - **Elapsed:** wall-clock duration since the active run started,
 *    formatted with 1/10-second precision under 60 s
 *    (e.g. `12.3 s`) and `m:ss` above
 *  - **Best objective:** value from the latest `IterationCompleted`
 *    or the terminal snapshot once the run completes
 *
 * No ETA — the framework provides none and per-iteration cost
 * varies enough across algorithms (cooling schedules in SA, growing
 * replication counts in R-SPLINE, population-based work in CE) that
 * a linear extrapolation would mislead more often than help.
 *
 * A 100 ms `Timer` advances Elapsed during a live run so the
 * displayed value tracks decisecond changes; the timer stops the
 * moment the run terminates and the display freezes on the final
 * elapsed.
 */
class OptimizationProgressPanel(
    private val controller: SimoptAppController
) : JPanel(GridBagLayout()) {

    private val iterLabel = mkValue()
    private val elapsedLabel = mkValue()
    private val objLabel = mkValue()

    private var runStart: TimeSource.Monotonic.ValueTimeMark? = null
    private var frozenElapsed: Duration? = null

    private val tick = Timer(100) { refresh() }.apply { isRepeats = true }

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Optimization Progress"),
            BorderFactory.createEmptyBorder(2, 6, 6, 6)
        )

        var row = 0
        addPair("Iteration:", iterLabel, row++)
        addPair("Elapsed:", elapsedLabel, row++)
        addPair("Best objective:", objLabel, row++)

        wireCollectors()
        refresh()
    }

    private fun wireCollectors() {
        controller.runningFlow.onEach { running ->
            if (running) {
                runStart = TimeSource.Monotonic.markNow()
                frozenElapsed = null
                tick.start()
            } else {
                frozenElapsed = runStart?.elapsedNow()
                tick.stop()
            }
            refresh()
        }.launchIn(controller.edtScope)
        controller.latestIteration.onEach { refresh() }.launchIn(controller.edtScope)
        controller.activeSolver.onEach { refresh() }.launchIn(controller.edtScope)
        controller.lastResult.onEach { refresh() }.launchIn(controller.edtScope)
    }

    private fun refresh() {
        val latest = controller.latestIteration.value
        val maxIter = controller.activeSolver.value?.maximumNumberIterations
        val terminalResult = controller.lastResult.value
        val terminalSnapshot = terminalResult?.bestSolution

        val iter = latest?.iteration ?: terminalSnapshot?.iterationNumber
        iterLabel.text = when {
            iter == null -> "—"
            maxIter != null -> "$iter / $maxIter"
            else -> "$iter"
        }

        val elapsed = when {
            controller.runningFlow.value -> runStart?.elapsedNow()
            frozenElapsed != null -> frozenElapsed
            else -> null
        }
        elapsedLabel.text = elapsed?.let { formatDuration(it) } ?: "—"

        val obj = latest?.estimatedObjectiveValue
            ?: terminalSnapshot?.estimatedObjFncValue
        objLabel.text = obj?.let { formatObjective(it) + objectiveUnitSuffix() } ?: "—"
    }

    /** " <unit>" when the objective response is nominated with a unit, else "". */
    private fun objectiveUnitSuffix(): String {
        val name = controller.objectiveResponseName.value ?: return ""
        val unit = controller.currentModelDescriptor.value?.catalog
            ?.nominatedOutputs?.firstOrNull { it.name == name }?.unit?.takeIf { it.isNotBlank() }
        return if (unit == null) "" else " $unit"
    }

    /** Format duration with decisecond precision under 60 s,
     *  `m:ss` (whole-second) above.  Examples:
     *  - 350 ms → `0.3 s`
     *  - 12,345 ms → `12.3 s`
     *  - 75,432 ms → `1:15`
     */
    private fun formatDuration(d: Duration): String {
        val totalSecs = d.inWholeMilliseconds / 1000.0
        return if (totalSecs < 60.0) {
            "%.1f s".format(totalSecs)
        } else {
            val ws = d.inWholeSeconds
            "%d:%02d".format(ws / 60, ws % 60)
        }
    }

    // formatObjective is a top-level helper above; the class
    // delegates to it so the same formatting can be unit-tested
    // without a Swing instance.

    // ── Layout helpers ────────────────────────────────────────────────────

    private fun mkValue(): JLabel = JLabel("—").apply {
        font = font.deriveFont(Font.PLAIN, 13f)
        foreground = Color(0x33, 0x33, 0x33)
    }

    private fun addPair(key: String, value: JLabel, row: Int) {
        add(JLabel(key).apply { font = font.deriveFont(Font.BOLD, 13f) },
            gbc(0, row, anchor = GridBagConstraints.WEST))
        add(value, gbc(1, row, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
    }

    private fun gbc(
        col: Int,
        row: Int,
        weightx: Double = 0.0,
        anchor: Int = GridBagConstraints.WEST,
        fill: Int = GridBagConstraints.NONE,
        insets: Insets = Insets(2, 4, 2, 8)
    ): GridBagConstraints = GridBagConstraints().apply {
        this.gridx = col
        this.gridy = row
        this.weightx = weightx
        this.anchor = anchor
        this.fill = fill
        this.insets = insets
    }
}
