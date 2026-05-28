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
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.Timer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Numeric live-progress display.
 *
 * Shows four rows of bold-key / dynamic-value pairs:
 *
 *  - **Iteration:** `n / max` (or `n` when max is unbounded)
 *  - **Elapsed:** wall-clock duration since the active run started
 *  - **ETA:** linear-extrapolation estimate; visible only mid-run
 *  - **Best objective:** value from the latest `IterationCompleted`
 *
 * Phase O7b deliberately does not render a live convergence chart —
 * the full iteration history is available in
 * [SimoptAppController.lastResult] after the run terminates, and the
 * Results step (Phase O8) plots it once with `lets-plot`.
 */
class LiveProgressPanel(
    private val controller: SimoptAppController
) : JPanel(GridBagLayout()) {

    private val iterLabel = mkValue()
    private val elapsedLabel = mkValue()
    private val etaLabel = mkValue()
    private val objLabel = mkValue()

    /** Captured when [SimoptAppController.runningFlow] flips true;
     *  drives elapsed + ETA computation. */
    private var runStart: TimeSource.Monotonic.ValueTimeMark? = null

    /** Captures the elapsed time at terminal-state so the display
     *  freezes on the final value instead of resetting to "0s". */
    private var frozenElapsed: Duration? = null

    /** Timer ticks once per second so Elapsed / ETA stay current
     *  even when no new iteration events arrive. */
    private val tick = Timer(1_000) { refresh() }.apply { isRepeats = true }

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Live progress"),
            BorderFactory.createEmptyBorder(2, 6, 6, 6)
        )

        var row = 0
        addPair("Iteration:", iterLabel, row++)
        addPair("Elapsed:", elapsedLabel, row++)
        addPair("ETA:", etaLabel, row++)
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
                // Capture the elapsed at the moment of termination so
                // the display freezes there.
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
        val running = controller.runningFlow.value
        val latest = controller.latestIteration.value
        val maxIter = controller.activeSolver.value?.maximumNumberIterations
        val terminalResult = controller.lastResult.value
        val terminalSnapshot = terminalResult?.bestSolution

        // Iteration count: prefer terminal snapshot's count when run
        // has completed and we have no further live iteration events.
        val iter = latest?.iteration ?: terminalSnapshot?.iterationNumber
        iterLabel.text = when {
            iter == null -> "—"
            maxIter != null -> "$iter / $maxIter"
            else -> "$iter"
        }

        // Elapsed
        val elapsed = when {
            running -> runStart?.elapsedNow()
            frozenElapsed != null -> frozenElapsed
            else -> null
        }
        elapsedLabel.text = elapsed?.let { formatDuration(it) } ?: "—"

        // ETA — only while running
        val etaText: String = if (running && elapsed != null && iter != null
            && maxIter != null && iter > 0 && iter < maxIter
        ) {
            val perIter = elapsed.inWholeMilliseconds.toDouble() / iter
            val remaining = ((maxIter - iter) * perIter).toLong().milliseconds
            "~ ${formatDuration(remaining)}"
        } else "—"
        etaLabel.text = etaText

        // Best objective: prefer the latest live event when running;
        // fall back to the terminal snapshot once the run completes.
        val obj = latest?.estimatedObjectiveValue
            ?: terminalSnapshot?.estimatedObjFncValue
        objLabel.text = obj?.let { formatObjective(it) } ?: "—"
    }

    private fun formatDuration(d: Duration): String {
        val secs = d.inWholeSeconds
        return if (secs < 60) "${secs}s"
        else {
            val m = secs / 60
            val s = secs % 60
            "%d:%02d".format(m, s)
        }
    }

    private fun formatObjective(v: Double): String =
        if (v.isFinite()) "%.4f".format(v) else v.toString()

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
