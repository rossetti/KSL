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

/**
 * Shows algorithm-specific state from
 * [ksl.simopt.solvers.SolverStateSnapshot.solverSpecificState] —
 * for example, Simulated Annealing emits `"temperature"`, R-SPLINE
 * emits `"splineCalls"`, etc.
 *
 * Visibility is bound to whether the latest iteration event carries
 * a non-null, non-empty `solverSpecificState` map.  Solvers that
 * never populate this field (Stochastic Hill Climbing, Cross-Entropy
 * in their stock configuration) will keep this panel hidden — so
 * the Execute step doesn't waste screen real-estate on an empty
 * box.
 */
class AlgorithmStatePanel(
    private val controller: SimoptAppController
) : JPanel(GridBagLayout()) {

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Algorithm state"),
            BorderFactory.createEmptyBorder(2, 6, 6, 6)
        )
        wireCollectors()
        refresh()
    }

    private fun wireCollectors() {
        controller.latestIteration.onEach { refresh() }.launchIn(controller.edtScope)
        controller.lastResult.onEach { refresh() }.launchIn(controller.edtScope)
    }

    private fun refresh() {
        removeAll()
        val state = currentState()
        isVisible = !state.isNullOrEmpty()
        if (state != null) {
            for ((row, entry) in state.entries.sortedBy { it.key }.withIndex()) {
                add(
                    JLabel("${entry.key}:").apply {
                        font = font.deriveFont(Font.BOLD, 12f)
                    },
                    gbc(0, row, anchor = GridBagConstraints.WEST)
                )
                add(
                    JLabel(formatValue(entry.value)).apply {
                        foreground = Color(0x33, 0x33, 0x33)
                        font = font.deriveFont(Font.PLAIN, 12f)
                    },
                    gbc(1, row, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL)
                )
            }
        }
        revalidate()
        repaint()
    }

    private fun currentState(): Map<String, Double>? {
        // Prefer the live event; fall back to the terminal snapshot.
        controller.latestIteration.value?.solverSpecificState?.let { return it }
        return controller.lastResult.value
            ?.bestSolution?.solverSpecificState
    }

    private fun formatValue(v: Double): String =
        if (v.isFinite()) "%.4g".format(v) else v.toString()

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
