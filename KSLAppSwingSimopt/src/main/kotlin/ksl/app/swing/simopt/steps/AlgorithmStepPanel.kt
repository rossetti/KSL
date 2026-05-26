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

import ksl.app.swing.simopt.SimoptAppController

/**
 * Step 3 of 6 — Algorithm.
 *
 * **Phase O2 shell.**  Replaced in Phase O6 with the algorithm
 * picker, body-swap CardLayout per `SolverSpec` variant
 * (SHC / SA / CE / RSpline), and the random-restart wrapper editor.
 * See `.claude/plans/simopt-app-plan.md` §5.5.
 */
class AlgorithmStepPanel(controller: SimoptAppController) : PlaceholderStepPanel(
    title = "Algorithm",
    summary = "Choose one of the four solvers (Stochastic Hill " +
        "Climbing, Simulated Annealing, Cross-Entropy, R-SPLINE) " +
        "and configure its parameters.  Optional random-restart " +
        "wrapper available for any algorithm.",
    pendingPhase = "Phase O6"
)
